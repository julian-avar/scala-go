package scalago.linker

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Go types, as reported by the `goapi` helper (see `runtime/goapi`). */
enum GoType:
  /** A predeclared basic type (`int`, `string`, `byte`, `untyped-float`, ...). */
  case Basic(name: String)
  /** A defined (named) type. `pkg == ""` for predeclared ones (`error`). */
  case Named(pkg: String, name: String)
  case Ptr(elem: GoType)
  case Slice(elem: GoType)
  case ArrayOf(length: Long, elem: GoType)
  case MapOf(key: GoType, value: GoType)
  /** `dir`: "" (both directions), "recv" (`<-chan`) or "send" (`chan<-`). */
  case Chan(elem: GoType, dir: String = "")
  case Func(sig: GoSignature)
  /** `any` / `interface{}`. */
  case EmptyInterface
  /** `struct{}`, which Scala sees as `Unit` (in Go data: `GoChan[Unit]`). */
  case EmptyStruct
  /** A type parameter of a generic function or type. */
  case TypeParam(name: String)
  /** An instance of a generic type (`iter.Seq[string]`). */
  case Generic(pkg: String, name: String, args: List[GoType])
  /** A constraint with a type set: its core type (`~[]E`), the types of its unions (`~int |
   *  ~string`), and whether it is comparable.
   */
  case Constraint(core: Option[GoType], terms: List[GoType], comparable: Boolean)
  /** Anything the linker cannot convert yet: interface and struct literals, generics. */
  case Other(description: String)

  override def toString: String = this match
    case Basic(n)          => n.replace('-', ' ')
    case Named("", n)      => n
    case Named(p, n)       => s"$p.$n"
    case Ptr(e)            => s"*$e"
    case Slice(e)          => s"[]$e"
    case ArrayOf(n, e)     => s"[$n]$e"
    case MapOf(k, v)       => s"map[$k]$v"
    case Chan(e, "recv")   => s"<-chan $e"
    case Chan(e, "send")   => s"chan<- $e"
    case Chan(e, _)        => s"chan $e"
    case Func(sig)         => s"func$sig"
    case EmptyInterface    => "any"
    case EmptyStruct       => "struct{}"
    case TypeParam(n)      => n
    case Generic(p, n, as) => as.mkString(s"$p.$n[", ", ", "]")
    case Constraint(c, ts, comparable) =>
      (c.map(t => s"~$t").toList ++ ts.map(_.toString).filterNot(t => c.exists(_.toString == t)) ++
        Option.when(comparable && ts.isEmpty)("comparable")).mkString(" | ")
    case Other(d)          => d

  /** This type with the type parameters replaced (those `bindings` has). */
  def substitute(bindings: Map[String, GoType]): GoType =
    if bindings.isEmpty then this
    else this match
      case TypeParam(n)          => bindings.getOrElse(n, this)
      case Ptr(e)                => Ptr(e.substitute(bindings))
      case Slice(e)              => Slice(e.substitute(bindings))
      case ArrayOf(n, e)         => ArrayOf(n, e.substitute(bindings))
      case MapOf(k, v)           => MapOf(k.substitute(bindings), v.substitute(bindings))
      case Chan(e, d)            => Chan(e.substitute(bindings), d)
      case Func(sig)             => Func(sig.substitute(bindings))
      case Generic(p, n, as)     => Generic(p, n, as.map(_.substitute(bindings)))
      case Constraint(c, ts, cm) => Constraint(c.map(_.substitute(bindings)), ts.map(_.substitute(bindings)), cm)
      case other                 => other

  /** Whether this type mentions type parameter `name`. */
  def mentions(name: String): Boolean = this match
    case TypeParam(n)          => n == name
    case Ptr(e)                => e.mentions(name)
    case Slice(e)              => e.mentions(name)
    case ArrayOf(_, e)         => e.mentions(name)
    case MapOf(k, v)           => k.mentions(name) || v.mentions(name)
    case Chan(e, _)            => e.mentions(name)
    case Func(sig)             => (sig.params ++ sig.results).exists(_.mentions(name))
    case Generic(_, _, as)     => as.exists(_.mentions(name))
    case _                     => false

object GoType:
  val Error: GoType = Named("", "error")

final case class GoSignature(params: List[GoType], results: List[GoType], variadic: Boolean, generic: Boolean,
    /** Parameter names ("_" for unnamed ones; empty when unknown). */
    paramNames: List[String] = Nil,
    /** Type parameters of a generic function, with their constraints: `EmptyInterface` (`any`), a
     *  named interface type, or `Other` for constraints with type sets (`~int | string`, `comparable`).
     */
    typeParams: List[(String, GoType)] = Nil):
  /** This signature with type parameters replaced (it stays generic if some are left). */
  def substitute(bindings: Map[String, GoType]): GoSignature =
    if bindings.isEmpty then this
    else
      val left = typeParams.filterNot((n, _) => bindings.contains(n)).map((n, c) => n -> c.substitute(bindings))
      copy(params = params.map(_.substitute(bindings)), results = results.map(_.substitute(bindings)),
        generic = left.nonEmpty, typeParams = left)

  override def toString: String =
    val ps = params.zipWithIndex.map((t, i) => if variadic && i == params.size - 1 then s"...${t.asInstanceOf[GoType.Slice].elem}" else t.toString)
    val rs = results match
      case Nil       => ""
      case List(one) => s" $one"
      case several   => several.mkString(" (", ", ", ")")
    s"(${ps.mkString(", ")})$rs"

enum GoTypeKind:
  case Struct, Interface, Other

/** A defined Go type: its kind, its method set (of `*T`, or of `T` for interfaces; exported
 *  methods only), its fields, and for kind `Other` its underlying type (`type Celsius float64`,
 *  `type Cmd func() Msg`); for interfaces without any methods, `EmptyInterface`.
 *
 *  @param pointerMethods for kind `Other`, the methods with pointer receivers, which are not in
 *                        `methods`: Scala holds such values as copies, which they would change
 *  @param typeParams     for a generic type, its type parameters with their constraints; its
 *                        methods, fields and underlying type mention them
 */
final case class GoTypeDecl(kind: GoTypeKind, methods: Map[String, GoSignature], fields: Map[String, GoType],
    underlying: Option[GoType] = None, pointerMethods: Map[String, GoSignature] = Map.empty,
    typeParams: List[(String, GoType)] = Nil):
  def isGeneric: Boolean = typeParams.nonEmpty

  /** The declaration of instance `T[args]` of a generic type (this one, if it is not generic). */
  def instantiate(args: List[GoType]): GoTypeDecl =
    if typeParams.isEmpty || args.size != typeParams.size then this
    else
      val b = typeParams.map(_._1).zip(args).toMap
      GoTypeDecl(kind, methods.map((m, s) => m -> s.substitute(b)), fields.map((f, t) => f -> t.substitute(b)),
        underlying.map(_.substitute(b)), pointerMethods.map((m, s) => m -> s.substitute(b)), Nil)

  /** The signature of a named func type (`type Cmd func() Msg`). */
  def funcSignature: Option[GoSignature] = underlying.collect { case GoType.Func(sig) => sig }
  /** The basic type of a named basic type (`type Color string`). */
  def basic: Option[String] = underlying.collect { case GoType.Basic(b) => b }
  /** Whether this is an interface without methods (`type Msg interface{}`), which holds any value. */
  def isEmptyInterface: Boolean = kind == GoTypeKind.Interface && underlying.contains(GoType.EmptyInterface)

/** The exported API of one Go package. */
final case class GoPackage(
    path: String,
    name: String,
    funcs: Map[String, GoSignature],
    vars: Map[String, GoType],
    consts: Map[String, GoType],
    /** The exact values of the constants (Go syntax: `3`, `1e+100`, `"a"`, `true`). */
    constValues: Map[String, String],
    types: Map[String, GoTypeDecl],
    /** `type A = B`: the aliased type. */
    aliases: Map[String, GoType],
    /** Types the APIs use that code outside the package cannot name (unexported, or of an
     *  `internal` package), with the exported interfaces of the package they implement.
     */
    opaque: Map[String, List[GoType]] = Map.empty
)

/** The APIs of the Go packages a program uses. */
final class GoApi(val packages: Map[String, GoPackage]):
  /** For a type that code outside its package cannot name (see [[GoPackage.opaque]]): the
   *  interfaces it implements.
   */
  def opaque(pkg: String, name: String): Option[List[GoType]] =
    if GoApi.isNameable(pkg, name) then None else Some(packages.get(pkg).flatMap(_.opaque.get(name)).getOrElse(Nil))

  /** Whether `t` mentions a type that code outside its package cannot name. */
  def mentionsOpaque(t: GoType): Boolean = t match
    case GoType.Named(p, n)        => !GoApi.isNameable(p, n)
    case GoType.Generic(p, n, as)  => !GoApi.isNameable(p, n) || as.exists(mentionsOpaque)
    case GoType.Ptr(e)             => mentionsOpaque(e)
    case GoType.Slice(e)           => mentionsOpaque(e)
    case GoType.ArrayOf(_, e)      => mentionsOpaque(e)
    case GoType.MapOf(k, v)        => mentionsOpaque(k) || mentionsOpaque(v)
    case GoType.Chan(e, _)         => mentionsOpaque(e)
    case GoType.Func(s)            => (s.params ++ s.results).exists(mentionsOpaque)
    case _                         => false

  /** The declaration of a named type; aliases are followed. Predeclared `error` is built in. */
  def typeDecl(pkg: String, name: String): Option[GoTypeDecl] =
    if pkg == "" && name == "error" then Some(GoApi.errorDecl)
    else
      packages.get(pkg).flatMap { p =>
        p.types.get(name).orElse(p.aliases.get(name).collect { case GoType.Named(p2, n2) => typeDecl(p2, n2) }.flatten)
      }

object GoApi:
  /** Whether code outside package `pkg` can name its type `name`. */
  def isNameable(pkg: String, name: String): Boolean =
    pkg.isEmpty || (name.head.isUpper && !pkg.split('/').exists(e => e == "internal" || e == "vendor"))

  val errorDecl: GoTypeDecl =
    GoTypeDecl(GoTypeKind.Interface, Map("Error" -> GoSignature(Nil, List(GoType.Basic("string")), false, false)), Map.empty)

  val empty: GoApi = GoApi(Map.empty)

  final class GoApiException(message: String) extends Exception(message)

  private val HelperResource = "scalago/goapi/main.go"

  /** Runs the `goapi` helper on `packages`, resolved in the Go module in `moduleDir`. */
  def load(moduleDir: Path, packages: Seq[String]): GoApi =
    if packages.isEmpty then empty
    else
      val tmp = Files.createTempDirectory("scalago-goapi-")
      try
        val helper = tmp.resolve("main.go")
        val in = getClass.getClassLoader.getResourceAsStream(HelperResource)
        if in == null then throw IllegalStateException(s"missing resource $HelperResource")
        Using.resource(in)(i => Files.write(helper, i.readAllBytes()))
        // -mod=mod lets `go list` add missing requirements of required modules to go.mod.
        val command = List("go", "run", helper.toString, "-dir", moduleDir.toString, "-mod", "mod") ++ packages
        val process = ProcessBuilder(command.asJava).directory(tmp.toFile).redirectErrorStream(false).start()
        process.getOutputStream.close()
        val errFuture = java.util.concurrent.CompletableFuture.supplyAsync(() => String(process.getErrorStream.readAllBytes(), UTF_8))
        val output = String(process.getInputStream.readAllBytes(), UTF_8)
        val stderr = errFuture.get()
        if process.waitFor() != 0 then throw GoApiException(s"running the goapi helper failed:\n$stderr$output")
        parse(output)
      finally
        Using.resource(Files.walk(tmp))(_.iterator.asScala.toList.reverse.foreach(Files.deleteIfExists))

  /** Adds the requirements that building the Go module in `moduleDir` needs to its `go.mod` (and
   *  their checksums to `go.sum`): those of the modules its packages import indirectly. Unlike
   *  `go mod tidy`, this does not need the dependencies' test-only requirements.
   */
  def completeGoMod(moduleDir: Path): Unit =
    val process = ProcessBuilder(List("go", "list", "-mod=mod", "-deps", "./...").asJava)
      .directory(moduleDir.toFile).redirectErrorStream(true).start()
    process.getOutputStream.close()
    val output = String(process.getInputStream.readAllBytes(), UTF_8)
    if process.waitFor() != 0 then throw GoApiException(s"completing go.mod failed:\n$output")

  // ---- Output parsing -------------------------------------------------------------------------

  private enum SExpr:
    case Atom(value: String)
    case Str(value: String)
    case SList(items: List[SExpr])

  import SExpr.*

  def parse(output: String): GoApi =
    final class Builder(val path: String, val name: String):
      val funcs = mutable.LinkedHashMap.empty[String, GoSignature]
      val vars = mutable.LinkedHashMap.empty[String, GoType]
      val consts = mutable.LinkedHashMap.empty[String, GoType]
      val constValues = mutable.LinkedHashMap.empty[String, String]
      val kinds = mutable.LinkedHashMap.empty[String, GoTypeKind]
      val methods = mutable.LinkedHashMap.empty[String, mutable.LinkedHashMap[String, GoSignature]]
      val pointerMethods = mutable.LinkedHashMap.empty[String, mutable.LinkedHashMap[String, GoSignature]]
      val typeParams = mutable.LinkedHashMap.empty[String, List[(String, GoType)]]
      val opaque = mutable.LinkedHashMap.empty[String, List[GoType]]
      val fields = mutable.LinkedHashMap.empty[String, mutable.LinkedHashMap[String, GoType]]
      val aliases = mutable.LinkedHashMap.empty[String, GoType]
      val underlying = mutable.LinkedHashMap.empty[String, GoType]
      def build = GoPackage(path, name, funcs.toMap, vars.toMap, consts.toMap, constValues.toMap,
        kinds.map((t, k) => t -> GoTypeDecl(k, methods.get(t).fold(Map.empty)(_.toMap), fields.get(t).fold(Map.empty)(_.toMap),
          underlying.get(t), pointerMethods.get(t).fold(Map.empty)(_.toMap), typeParams.getOrElse(t, Nil))).toMap,
        aliases.toMap, opaque.toMap)

    val builders = mutable.LinkedHashMap.empty[String, Builder]
    val errors = mutable.ListBuffer.empty[String]
    def pkg(path: String) = builders.getOrElseUpdate(path, Builder(path, path.split('/').last))
    for line <- output.linesIterator if line.trim.nonEmpty do
      parseSExpr(line) match
        case SList(Atom("package") :: Str(path) :: Atom(name) :: Nil) => builders(path) = Builder(path, name)
        case SList(Atom("func") :: Str(path) :: Atom(name) :: sig :: Nil) => pkg(path).funcs(name) = signature(sig)
        case SList(Atom("var") :: Str(path) :: Atom(name) :: t :: Nil) => pkg(path).vars(name) = goType(t)
        case SList(Atom("const") :: Str(path) :: Atom(name) :: t :: rest) =>
          pkg(path).consts(name) = goType(t)
          rest.collectFirst { case Str(v) => v }.foreach(pkg(path).constValues(name) = _)
        case SList(Atom("alias") :: Str(path) :: Atom(name) :: t :: Nil) => pkg(path).aliases(name) = goType(t)
        case SList(Atom("opaque") :: Str(path) :: Atom(name) :: ifaces) => pkg(path).opaque(name) = ifaces.map(goType)
        case SList(Atom("tparams") :: Str(path) :: Atom(name) :: tps) =>
          pkg(path).typeParams(name) = tps.collect { case SList(List(Atom(n), c)) => n -> goType(c) }
        case SList(Atom("type") :: Str(path) :: Atom(name) :: Atom(kind) :: rest) =>
          rest.headOption.foreach(u => pkg(path).underlying(name) = goType(u))
          pkg(path).kinds(name) = kind match
            case "struct"    => GoTypeKind.Struct
            case "interface" => GoTypeKind.Interface
            case _           => GoTypeKind.Other
        case SList(Atom("method") :: Str(path) :: Atom(tpe) :: Atom(name) :: sig :: Nil) =>
          pkg(path).methods.getOrElseUpdate(tpe, mutable.LinkedHashMap.empty)(name) = signature(sig)
        case SList(Atom("method") :: Str(path) :: Atom(tpe) :: Atom(name) :: sig :: Atom("pointer") :: Nil) =>
          pkg(path).pointerMethods.getOrElseUpdate(tpe, mutable.LinkedHashMap.empty)(name) = signature(sig)
        case SList(Atom("field") :: Str(path) :: Atom(tpe) :: Atom(name) :: t :: Nil) =>
          pkg(path).fields.getOrElseUpdate(tpe, mutable.LinkedHashMap.empty)(name) = goType(t)
        case SList(Atom("error") :: Str(message) :: Nil) => errors += message
        case _ => throw GoApiException(s"unexpected goapi output: $line")
    if errors.nonEmpty then throw GoApiException(errors.mkString("\n"))
    GoApi(builders.map((p, b) => p -> b.build).toMap)

  private def signature(e: SExpr): GoSignature = e match
    case SList(Atom("sig") :: SList(params) :: SList(results) :: Atom(variadic) :: rest) =>
      val names = rest.collectFirst { case SList(Atom("names") :: ns) => ns.collect { case Atom(n) => n } }.getOrElse(Nil)
      val typeParams = rest.collectFirst { case SList(Atom("tparams") :: tps) =>
        tps.collect { case SList(List(Atom(n), c)) => n -> goType(c) }
      }.getOrElse(Nil)
      GoSignature(params.map(goType), results.map(goType), variadic == "true", rest.contains(Atom("generic")), names, typeParams)
    case other => throw GoApiException(s"bad signature: $other")

  private def goType(e: SExpr): GoType = e match
    case SList(List(Atom("basic"), Atom(name)))           => GoType.Basic(name)
    case SList(List(Atom("named"), Str(pkg), Atom(name))) => GoType.Named(pkg, name)
    case SList(List(Atom("ptr"), t))                       => GoType.Ptr(goType(t))
    case SList(List(Atom("slice"), t))                     => GoType.Slice(goType(t))
    case SList(List(Atom("array"), Atom(n), t))            => GoType.ArrayOf(n.toLong, goType(t))
    case SList(List(Atom("map"), k, v))                    => GoType.MapOf(goType(k), goType(v))
    case SList(List(Atom("chan"), t))                      => GoType.Chan(goType(t))
    case SList(List(Atom("recv-chan"), t))                 => GoType.Chan(goType(t), "recv")
    case SList(List(Atom("send-chan"), t))                 => GoType.Chan(goType(t), "send")
    case SList(List(Atom("func"), sig))                    => GoType.Func(signature(sig))
    case SList(List(Atom("interface")))                    => GoType.EmptyInterface
    case SList(List(Atom("empty-struct")))                 => GoType.EmptyStruct
    case SList(Atom("generic") :: Str(pkg) :: Atom(name) :: args) => GoType.Generic(pkg, name, args.map(goType))
    case SList(List(Atom("tparam"), Atom(name)))           => GoType.TypeParam(name)
    case SList(Atom("constraint") :: parts)                =>
      GoType.Constraint(
        parts.collectFirst { case SList(List(Atom("core"), t)) => goType(t) },
        parts.collectFirst { case SList(Atom("terms") :: ts) => ts.map(goType) }.getOrElse(Nil),
        parts.contains(Atom("comparable")))
    case SList(List(Atom("unknown"), Str(d)))              => GoType.Other(d)
    case SList(Atom(other) :: _)                           => GoType.Other(s"$other type")
    case other                                             => throw GoApiException(s"bad type: $other")

  private def parseSExpr(s: String): SExpr =
    var i = 0
    def skipSpaces(): Unit = while i < s.length && s(i) == ' ' do i += 1
    def expr(): SExpr =
      skipSpaces()
      s(i) match
        case '(' =>
          i += 1
          val items = mutable.ListBuffer.empty[SExpr]
          skipSpaces()
          while s(i) != ')' do
            items += expr()
            skipSpaces()
          i += 1
          SList(items.toList)
        case '"' =>
          i += 1
          val sb = StringBuilder()
          while s(i) != '"' do
            if s(i) == '\\' then
              s(i + 1) match
                case 'n' => sb += '\n'
                case 't' => sb += '\t'
                case 'x' =>
                  sb += Integer.parseInt(s.substring(i + 2, i + 4), 16).toChar
                  i += 2
                case 'u' =>
                  sb += Integer.parseInt(s.substring(i + 2, i + 6), 16).toChar
                  i += 4
                case c => sb += c
              i += 2
            else
              sb += s(i)
              i += 1
          i += 1
          Str(sb.toString)
        case _ =>
          val start = i
          while i < s.length && s(i) != ' ' && s(i) != ')' && s(i) != '(' do i += 1
          Atom(s.substring(start, i))
    expr()
