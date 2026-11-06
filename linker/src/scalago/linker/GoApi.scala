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
  case Chan(elem: GoType)
  case Func(sig: GoSignature)
  /** `any` / `interface{}`. */
  case EmptyInterface
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
    case Chan(e)           => s"chan $e"
    case Func(sig)         => s"func$sig"
    case EmptyInterface    => "any"
    case Other(d)          => d

object GoType:
  val Error: GoType = Named("", "error")

final case class GoSignature(params: List[GoType], results: List[GoType], variadic: Boolean, generic: Boolean):
  override def toString: String =
    val ps = params.zipWithIndex.map((t, i) => if variadic && i == params.size - 1 then s"...${t.asInstanceOf[GoType.Slice].elem}" else t.toString)
    val rs = results match
      case Nil       => ""
      case List(one) => s" $one"
      case several   => several.mkString(" (", ", ", ")")
    s"(${ps.mkString(", ")})$rs"

enum GoTypeKind:
  case Struct, Interface, Other

/** A defined Go type: its kind, its method set (of `*T`, or of `T` for interfaces) and its fields. */
final case class GoTypeDecl(kind: GoTypeKind, methods: Map[String, GoSignature], fields: Map[String, GoType])

/** The exported API of one Go package. */
final case class GoPackage(
    path: String,
    name: String,
    funcs: Map[String, GoSignature],
    vars: Map[String, GoType],
    consts: Map[String, GoType],
    types: Map[String, GoTypeDecl],
    /** `type A = B`: the aliased type. */
    aliases: Map[String, GoType]
)

/** The APIs of the Go packages a program uses. */
final class GoApi(val packages: Map[String, GoPackage]):
  /** The declaration of a named type; aliases are followed. Predeclared `error` is built in. */
  def typeDecl(pkg: String, name: String): Option[GoTypeDecl] =
    if pkg == "" && name == "error" then Some(GoApi.errorDecl)
    else
      packages.get(pkg).flatMap { p =>
        p.types.get(name).orElse(p.aliases.get(name).collect { case GoType.Named(p2, n2) => typeDecl(p2, n2) }.flatten)
      }

object GoApi:
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
      val kinds = mutable.LinkedHashMap.empty[String, GoTypeKind]
      val methods = mutable.LinkedHashMap.empty[String, mutable.LinkedHashMap[String, GoSignature]]
      val fields = mutable.LinkedHashMap.empty[String, mutable.LinkedHashMap[String, GoType]]
      val aliases = mutable.LinkedHashMap.empty[String, GoType]
      def build = GoPackage(path, name, funcs.toMap, vars.toMap, consts.toMap,
        kinds.map((t, k) => t -> GoTypeDecl(k, methods.get(t).fold(Map.empty)(_.toMap), fields.get(t).fold(Map.empty)(_.toMap))).toMap,
        aliases.toMap)

    val builders = mutable.LinkedHashMap.empty[String, Builder]
    val errors = mutable.ListBuffer.empty[String]
    def pkg(path: String) = builders.getOrElseUpdate(path, Builder(path, path.split('/').last))
    for line <- output.linesIterator if line.trim.nonEmpty do
      parseSExpr(line) match
        case SList(Atom("package") :: Str(path) :: Atom(name) :: Nil) => builders(path) = Builder(path, name)
        case SList(Atom("func") :: Str(path) :: Atom(name) :: sig :: Nil) => pkg(path).funcs(name) = signature(sig)
        case SList(Atom("var") :: Str(path) :: Atom(name) :: t :: Nil) => pkg(path).vars(name) = goType(t)
        case SList(Atom("const") :: Str(path) :: Atom(name) :: t :: Nil) => pkg(path).consts(name) = goType(t)
        case SList(Atom("alias") :: Str(path) :: Atom(name) :: t :: Nil) => pkg(path).aliases(name) = goType(t)
        case SList(Atom("type") :: Str(path) :: Atom(name) :: Atom(kind) :: Nil) =>
          pkg(path).kinds(name) = kind match
            case "struct"    => GoTypeKind.Struct
            case "interface" => GoTypeKind.Interface
            case _           => GoTypeKind.Other
        case SList(Atom("method") :: Str(path) :: Atom(tpe) :: Atom(name) :: sig :: Nil) =>
          pkg(path).methods.getOrElseUpdate(tpe, mutable.LinkedHashMap.empty)(name) = signature(sig)
        case SList(Atom("field") :: Str(path) :: Atom(tpe) :: Atom(name) :: t :: Nil) =>
          pkg(path).fields.getOrElseUpdate(tpe, mutable.LinkedHashMap.empty)(name) = goType(t)
        case SList(Atom("error") :: Str(message) :: Nil) => errors += message
        case _ => throw GoApiException(s"unexpected goapi output: $line")
    if errors.nonEmpty then throw GoApiException(errors.mkString("\n"))
    GoApi(builders.map((p, b) => p -> b.build).toMap)

  private def signature(e: SExpr): GoSignature = e match
    case SList(Atom("sig") :: SList(params) :: SList(results) :: Atom(variadic) :: rest) =>
      GoSignature(params.map(goType), results.map(goType), variadic == "true", rest.contains(Atom("generic")))
    case other => throw GoApiException(s"bad signature: $other")

  private def goType(e: SExpr): GoType = e match
    case SList(List(Atom("basic"), Atom(name)))           => GoType.Basic(name)
    case SList(List(Atom("named"), Str(pkg), Atom(name))) => GoType.Named(pkg, name)
    case SList(List(Atom("ptr"), t))                       => GoType.Ptr(goType(t))
    case SList(List(Atom("slice"), t))                     => GoType.Slice(goType(t))
    case SList(List(Atom("array"), Atom(n), t))            => GoType.ArrayOf(n.toLong, goType(t))
    case SList(List(Atom("map"), k, v))                    => GoType.MapOf(goType(k), goType(v))
    case SList(List(Atom("chan"), t))                      => GoType.Chan(goType(t))
    case SList(List(Atom("func"), sig))                    => GoType.Func(signature(sig))
    case SList(List(Atom("interface")))                    => GoType.EmptyInterface
    case SList(List(Atom("generic"), Str(pkg), Atom(name))) => GoType.Other(s"$pkg.$name[...] (generic)")
    case SList(List(Atom("tparam"), Atom(name)))           => GoType.Other(s"type parameter $name")
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
