package scalago.linker

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Using

import scalago.ir.{ClassName, ScalaType, Type}

/** Generates Scala facades (`@GoImport` objects) from Go packages, as ScalablyTyped does from
 *  TypeScript declarations. The `goapi` helper reads the packages' APIs; each package becomes one
 *  Scala object, in one file:
 *  {{{
 *  scalago-facadegen --out <dir> --package <scala package> [--classpath <cp>]
 *                    [--go-require m@v]... [--go-replace m=dir]... <import path>[=<object>]...
 *  }}}
 *  A package's object is named as the Go package, or, when several have that name, after its
 *  import path (`math_rand`); `<import path>=<object>` names it, as a Go import alias does.
 *  Types map as the facade conversions allow, choosing exact and cheap types:
 *  - parameters and results: Go numbers as Scala numbers (`int` is `Long`), `string` as `String`,
 *    `error` as `GoError`, `any` as `Any`, Go types of the generated packages as their facades,
 *    funcs as Scala functions, `(T, error)` results as `T` (the error is thrown), several results as
 *    tuples;
 *  - inside Go data (`[]T`, `map[K]V`, `chan T`, `*T`): canonical views (`GoSlice[GoString]`,
 *    `GoMap[GoString, GoInt]`), so slices are shared and creating them gives the right Go type.
 *  - Go func types and interfaces without methods are type aliases (`type Cmd = () => Any`), so
 *    Scala functions and values are passed as they are; other named non-struct types get a
 *    conversion named as the type (`def Color(value: String): Color`), as in Go, and named slice
 *    and map types their composite literals too (`def H(entries: (String, Any)*): H`).
 *  - generic functions are generic methods (`def Max[E](x: GoSlice[E]): E` for
 *    `Max[S ~[]E, E cmp.Ordered](x S) E`): a type parameter whose constraint has a core type
 *    (`~[]E`) is that type, the others are type parameters, whose type arguments are canonical
 *    views (`GoInt`, `GoString`); Go checks them against the constraints. Those constrained by
 *    named interfaces, used in parameters, take the interfaces (`Matches[K fmt.Stringer](k K)`
 *    takes a `fmt.Stringer`). Generic types are generic classes, traits and aliases
 *    (`type Seq[V] = (V => Boolean) => Unit`).
 *  The types of other packages that the members use are generated too (only the types), so the
 *  facades of a package are complete without listing its dependencies. Members whose types cannot
 *  be represented (unnamed structs and interfaces, arrays and funcs inside Go data, ...) are left
 *  out, with a comment saying why. Facades are checked against Go at link time
 *  only where used, so unused members cost nothing.
 *
 *  The facades that the dependencies on `--classpath` provide (see [[FacadeIndex]]) are used
 *  instead of generated again, so that values flow between libraries: generated members take and
 *  return their types, generated types extend their interfaces, and a requested package that they
 *  provide completely is not generated at all.
 */
/** Go integer constants as Scala `Long`s. */
object GoConstants:
  private val Uint64Max = BigInt(2).pow(64) - 1

  /** Whether integer constant `value` (Go syntax) fits a `Long`, or a `uint64` (as its bits). */
  def fitsUint64Bits(value: String): Boolean =
    value.toLongOption.isDefined || scala.util.Try(BigInt(value)).toOption.exists(v => v >= 0 && v <= Uint64Max)

  /** Whether integer constant `value` fits a `Long`. */
  def fitsLong(value: String): Boolean = value.toLongOption.isDefined

object FacadeGen:
  /** @param classpath   the dependencies of the generated facades, whose Go facades are reused
   *  @param objectNames the names of the objects of some packages (by import path)
   */
  final case class Config(scalaPackage: String, packages: Seq[String], goRequires: Seq[(String, String)] = Nil,
      goReplaces: Seq[(String, String)] = Nil, classpath: Seq[Path] = Nil, objectNames: Map[String, String] = Map.empty)

  /** Generated sources (file name to content), and notes on the requested packages that were not
   *  generated because dependencies provide them.
   */
  final case class Output(files: Map[String, String], notes: Seq[String])

  /** The files written by [[run]], and the notes of its [[Output]]. */
  final case class Result(files: Seq[Path], notes: Seq[String])

  /** A problem that prevents generating the facades. */
  final class FacadeGenException(message: String) extends Exception(message)

  /** Generates the facades into `outDir` (one file per package). */
  def run(config: Config, outDir: Path): Result =
    val provided = FacadeIndex.load(config.classpath)
    val moduleDir = Files.createTempDirectory("scalago-facadegen-")
    try
      val goMod = StringBuilder("module scalago.facadegen\n\ngo 1.27\n")
      if config.goRequires.nonEmpty then goMod ++= config.goRequires.map((m, v) => s"\t$m $v\n").mkString("\nrequire (\n", "", ")\n")
      for (m, dir) <- config.goReplaces do goMod ++= s"\nreplace $m => $dir\n"
      Files.writeString(moduleDir.resolve("go.mod"), goMod.toString, UTF_8)
      // The packages whose types the generated members use are loaded too, until none is missing.
      var api = GoApi.load(moduleDir, config.packages)
      var missing = Generator(api, config.scalaPackage, config.packages, provided, config.objectNames).missingPackages
      while missing.nonEmpty do
        api = GoApi.load(moduleDir, (api.packages.keySet ++ missing).toSeq.sorted)
        missing = Generator(api, config.scalaPackage, config.packages, provided, config.objectNames).missingPackages
      val output = generate(api, config.scalaPackage, config.packages, provided, config.objectNames)
      Files.createDirectories(outDir)
      val files = for (name, source) <- output.files.toSeq.sortBy(_._1) yield
        val file = outDir.resolve(name)
        Files.writeString(file, source, UTF_8)
        file
      Result(files, output.notes)
    finally Using.resource(Files.walk(moduleDir))(_.iterator.asScala.toList.reverse.foreach(Files.deleteIfExists))

  /** The Scala sources for `packages` of `api`, and for the types of other packages of `api` that
   *  their members use, except what the dependencies' facades (`provided`) already have.
   */
  def generate(api: GoApi, scalaPackage: String, packages: Seq[String], provided: FacadeIndex = FacadeIndex.empty,
      objectNames: Map[String, String] = Map.empty): Output =
    val all = Generator(api, scalaPackage, packages, provided, objectNames)
    val (complete, own) = packages.partition(all.isProvided)
    val generator = if complete.isEmpty then all else Generator(api, scalaPackage, own, provided, objectNames)
    val notes = complete.map { p =>
      s"Go package $p is not generated: dependencies provide its facades (${provided.packageObjects(p).mkString(", ")})"
    }
    Output(generator.files, notes)

  private val scalaKeywords = Set("abstract", "case", "catch", "class", "def", "do", "else", "enum", "export",
    "extends", "false", "final", "finally", "for", "forSome", "given", "if", "implicit", "import", "lazy", "match",
    "new", "null", "object", "override", "package", "private", "protected", "return", "sealed", "super", "then",
    "this", "throw", "trait", "true", "try", "type", "val", "var", "while", "with", "yield", "end", "inline",
    "opaque", "open", "transparent", "infix", "derives", "erased")

  /** A Go identifier as a Scala one: keywords and names that do not start with a letter are quoted,
   *  as are names ending in `_` (`x_: T` would read as the operator `x_:`).
   */
  def ident(name: String): String =
    if scalaKeywords(name) || !name.head.isLetter || name.last == '_' then s"`$name`" else name

  /** Whether code outside a package's module can import it (not `internal`, not `vendor`). */
  private def importable(path: String): Boolean =
    path.nonEmpty && path != "C" && path != "unsafe" && !path.split('/').exists(e => e == "internal" || e == "vendor")

  /** The named types a Go type mentions. */
  private def namedIn(g: GoType): List[(String, String)] = g match
    case GoType.Named(p, n)    => if p.isEmpty then Nil else List(p -> n)
    case GoType.Ptr(e)         => namedIn(e)
    case GoType.Slice(e)       => namedIn(e)
    case GoType.ArrayOf(_, e)  => namedIn(e)
    case GoType.MapOf(k, v)    => namedIn(k) ++ namedIn(v)
    case GoType.Chan(e, _)     => namedIn(e)
    case GoType.Func(sig)      => namedIn(sig)
    case GoType.Generic(p, n, as) => (if p.isEmpty then Nil else List(p -> n)) ++ as.flatMap(namedIn)
    case GoType.Constraint(c, ts, _) => (c.toList ++ ts).flatMap(namedIn)
    case _                     => Nil

  private def namedIn(sig: GoSignature): List[(String, String)] =
    (sig.params ++ sig.results ++ sig.typeParams.map(_._2)).flatMap(namedIn)

  /** A Scala type of generated code: its source, and the type that the plugin records for it
   *  ([[ScalaType]], aliases followed), to compare generated members with those of dependencies'
   *  facades. `ir` is lazy: the expansion of an alias may not be needed, nor representable.
   */
  private final class SType(val source: String, irType: => ScalaType):
    lazy val ir: ScalaType = irType

  private object SType:
    def prim(t: Type): SType =
      val name = t match
        case Type.Void => "Unit"
        case other     => other.toString
      SType(s"_root_.scala.$name", ScalaType.Prim(t))

    /** A top-level class or opaque type, by its full name. */
    def ref(name: String, args: List[SType] = Nil): SType =
      val source = if args.isEmpty then s"_root_.$name" else args.map(_.source).mkString(s"_root_.$name[", ", ", "]")
      SType(source, ScalaType.Ref(name, args.map(_.ir)))

    val Any: SType = SType("_root_.scala.Any", ScalaType.Ref(ClassName.Object, Nil))

    def function(params: List[SType], result: SType): SType =
      val source = params match
        // A function parameter is parenthesized: `(V => Boolean) => Unit`, not `V => (Boolean => Unit)`.
        case List(one) if one.source.contains("=>") => s"(${one.source}) => ${result.source}"
        case List(one) => s"${one.source} => ${result.source}"
        case ps        => ps.map(_.source).mkString("(", ", ", s") => ${result.source}")
      SType(source, ScalaType.Ref(s"scala.Function${params.size}", (params :+ result).map(_.ir)))

    def tuple(elems: List[SType]): SType =
      SType(elems.map(_.source).mkString("(", ", ", ")"), ScalaType.Ref(s"scala.Tuple${elems.size}", elems.map(_.ir)))

    /** The type of a repeated parameter (`T*`). */
    def repeated(elem: SType): SType = SType(s"${elem.source}*", ScalaType.Ref(GoInterop.SeqClass, List(elem.ir)))

  /** A Scala name (`a.b.C`) as a source reference from the root package. */
  private def rootRef(scalaName: String): String = scalaName.split('.').map(ident).mkString("_root_.", ".", "")

  private final class Generator(api: GoApi, scalaPackage: String, roots: Seq[String], provided: FacadeIndex,
      names: Map[String, String]):
    /** The types facades are generated for: all types of the root packages, and the types of other
     *  packages their members use (transitively), except those that dependencies provide. The
     *  other packages' objects only have these.
     */
    private val (included: Set[(String, String)], missing: Set[String]) =
      val seen = mutable.LinkedHashSet.empty[(String, String)]
      val missing = mutable.LinkedHashSet.empty[String]
      val queue = mutable.Queue.empty[(String, String)]
      def use(refs: List[(String, String)]): Unit =
        // Dependencies' types are used as they are, but their declarations are needed too.
        for r @ (p, n) <- refs if importable(p) && !seen(r) do
          if !api.packages.contains(p) then missing += p
          else if provided.typeOf(p, n).isEmpty && api.typeDecl(p, n).isDefined then
            seen += r
            queue += r
      for path <- roots; pkg <- api.packages.get(path) do
        use(pkg.types.keys.toList.map(path -> _))
        pkg.funcs.values.foreach(sig => use(namedIn(sig)))
        (pkg.vars.values ++ pkg.consts.values ++ pkg.aliases.values).foreach(t => use(namedIn(t)))
      while queue.nonEmpty do
        val (p, n) = queue.dequeue()
        val decl = api.typeDecl(p, n).get
        decl.methods.values.foreach(sig => use(namedIn(sig)))
        decl.fields.values.foreach(t => use(namedIn(t)))
        decl.underlying.foreach(t => use(namedIn(t)))
        decl.typeParams.foreach((_, c) => use(namedIn(c)))
      (seen.toSet, missing.toSet)

    /** Packages whose types are used but whose APIs are not loaded. */
    def missingPackages: Set[String] = missing

    /** The generated packages: the roots, then those of the other included types. */
    private val packages: Seq[String] =
      (roots.filter(api.packages.contains) ++ included.toSeq.map(_._1).sorted).distinct

    /** Scala object name of each generated package: the one given (`names`), else its Go name,
     *  or its path when ambiguous. A root package that is the only root of its name keeps its
     *  name: the packages that other packages' types come from (`encoding/json/v2` for
     *  `encoding/json`) do not take it.
     */
    private val objectNames: Map[String, String] =
      val named = names.filter((p, _) => packages.contains(p))
      val others = packages.filterNot(named.contains)
      val byName = others.groupBy(p => api.packages(p).name)
      val taken = named.values.toSet
      named ++ others.map { p =>
        val name = api.packages(p).name
        val same = byName(name)
        val unique = !taken(name) && (same.size == 1 || same.filter(roots.contains) == Seq(p))
        p -> (if unique then name else p.replaceAll("[^A-Za-z0-9]", "_"))
      }

    /** The Scala type standing for Go type `pkg.name` (an instance of it, for a generic type): a
     *  dependency's facade, or a generated one.
     */
    private def facadeRef(pkg: String, name: String, args: List[SType] = Nil): Option[SType] =
      def applied(source: String) = if args.isEmpty then source else args.map(_.source).mkString(s"$source[", ", ", "]")
      provided.typeOf(pkg, name) match
        case Some(t) =>
          // A type alias is compared as what it is an alias of (as the plugin records it).
          Some(SType(applied(rootRef(t.scalaName)),
            t.facade.fold(expansion(pkg, name, t.scalaName, args))(c => ScalaType.Ref(c.name, args.map(_.ir)))))
        case None =>
          for
            obj <- objectNames.get(pkg)
            p <- api.packages.get(pkg)
            if included((pkg, name)) || (roots.contains(pkg) && p.aliases.contains(name))
          yield SType(applied(s"_root_.$scalaPackage.${ident(obj)}.$name"), expansion(pkg, name, s"$scalaPackage.$obj$$$name", args))

    /** What the plugin records for Go type `pkg.name` (applied to `args`): for a Go alias
     *  (`type A = B`) that of its target, for the types generated as Scala aliases their
     *  expansion, otherwise class `cls`.
     */
    private def expansion(pkg: String, name: String, cls: ClassName, args: List[SType] = Nil): ScalaType =
      api.packages.get(pkg).flatMap(_.aliases.get(name)) match
        case Some(GoType.Named(p, n)) => named(p, n).ir
        case _ =>
          aliasOf(pkg, name) match
            case Some(alias) =>
              val params = api.typeDecl(pkg, name).fold(Nil)(_.typeParams.map(_._1))
              substituteParams(alias.ir, params.zip(args.map(_.ir)).toMap)
            case None => ScalaType.Ref(cls, args.map(_.ir))

    private final class Unrepresentable(val reason: String) extends Exception(reason)
    private def no(reason: String): Nothing = throw Unrepresentable(reason)

    private def isStruct(pkg: String, name: String): Boolean =
      api.typeDecl(pkg, name).exists(_.kind == GoTypeKind.Struct)

    private def substituteParams(t: ScalaType, bindings: Map[String, ScalaType]): ScalaType = t match
      case ScalaType.Param(n)     => bindings.getOrElse(n, t)
      case ScalaType.Ref(n, args) => ScalaType.Ref(n, args.map(substituteParams(_, bindings)))
      case ScalaType.Arr(e)       => ScalaType.Arr(substituteParams(e, bindings))
      case other                  => other

    /** An instance of generic Go type `pkg.name`; its type arguments are canonical views. */
    private def generic(pkg: String, name: String, args: List[GoType]): SType =
      val decl = api.typeDecl(pkg, name).getOrElse(no(s"type $pkg.$name is not generated"))
      if decl.typeParams.size != args.size then no(s"type $pkg.$name with ${args.size} type arguments")
      facadeRef(pkg, name, args.map(inner)).getOrElse(no(s"type $pkg.$name is not generated"))

    // ---- Types ------------------------------------------------------------------------------------

    private def basic(b: String): SType = b.stripPrefix("untyped-") match
      case "bool"                                         => SType.prim(Type.Boolean)
      case "string"                                       => SType.ref(ClassName.String)
      case "int" | "int64" | "uint" | "uint64" | "uintptr" => SType.prim(Type.Long)
      case "int32" | "rune" | "uint32"                    => SType.prim(Type.Int)
      case "int16"                                        => SType.prim(Type.Short)
      case "uint16"                                       => SType.prim(Type.Char)
      case "int8" | "uint8" | "byte"                      => SType.prim(Type.Byte)
      case "float32"                                      => SType.prim(Type.Float)
      case "float64" | "float"                            => SType.prim(Type.Double)
      case "complex128" | "complex64" | "complex"         => SType.ref(GoInterop.GoComplexType)
      case other                                          => no(s"Go type $other")

    private def named(pkg: String, name: String): SType =
      if pkg == "" && name == "error" then SType.ref(GoInterop.GoErrorClass)
      else facadeRef(pkg, name).getOrElse(no(s"type $pkg.$name is not generated"))

    /** The Scala type alias standing for Go type `pkg.name`, if it is one: interfaces without
     *  methods are `Any`, func types without methods are Scala function types, so that Scala
     *  values and lambdas can be passed directly (`tea.Msg`, `tea.Cmd`).
     */
    private def aliasOf(pkg: String, name: String): Option[SType] =
      api.typeDecl(pkg, name).flatMap { decl =>
        if decl.isEmptyInterface then Some(SType.Any)
        else if decl.methods.isEmpty then
          decl.funcSignature.flatMap(sig => try Some(function(sig, isResult = true)) catch case _: Unrepresentable => None)
        else None
      }

    /** A type parameter of the member or class being generated: a Scala type parameter. */
    private def typeParam(name: String): SType = SType(name, ScalaType.Param(name))

    private def isFuncAlias(pkg: String, name: String): Boolean =
      api.typeDecl(pkg, name).exists(_.funcSignature.isDefined) && aliasOf(pkg, name).isDefined

    private def goData(name: String, args: SType*): SType = SType.ref(s"scalago.go.$name", args.toList)

    /** A type that code outside its package cannot name (unexported, of an `internal` package):
     *  the interface of its package it implements with the most methods (`binary.ByteOrder` for
     *  `binary.BigEndian`'s type), or `Any`. Its values pass through Scala as they are.
     */
    private def opaqueView(g: GoType): Option[SType] =
      val target = g match
        case GoType.Named(p, n)                       => Some((p, n))
        case GoType.Generic(p, n, _)                  => Some((p, n))
        case GoType.Ptr(GoType.Named(p, n))           => Some((p, n))
        case GoType.Ptr(GoType.Generic(p, n, _))      => Some((p, n))
        case _                                        => None
      target.flatMap((p, n) => api.opaque(p, n)).map { ifaces =>
        ifaces.collectFirst { case GoType.Named(ip, in) if facadeRef(ip, in).isDefined => named(ip, in) }.getOrElse(SType.Any)
      }

    /** The Scala type of a parameter or result. */
    def outer(g: GoType, isResult: Boolean): SType = g match
      case _ if opaqueView(g).isDefined                      => opaqueView(g).get
      case GoType.Basic(b)                                   => basic(b)
      case GoType.Named(pkg, n)                              => named(pkg, n)
      case GoType.Ptr(GoType.Named(pkg, n)) if isStruct(pkg, n) => named(pkg, n)
      case GoType.Generic(pkg, n, as)                        => generic(pkg, n, as)
      case GoType.Ptr(GoType.Generic(pkg, n, as)) if isStruct(pkg, n) => generic(pkg, n, as)
      case GoType.EmptyInterface                             => SType.Any
      case GoType.Func(sig)                                  => function(sig, isResult)
      case GoType.EmptyStruct                                => no("a struct{} value")
      // A Go array is a value: Scala gets a slice of a copy, and gives a slice to copy from.
      case GoType.ArrayOf(_, e)                              => goData("GoSlice", inner(e))
      case other                                             => inner(other)

    /** The canonical Scala view of a Go type inside Go data. */
    def inner(g: GoType): SType = g match
      case _ if opaqueView(g).isDefined => opaqueView(g).get
      case GoType.Basic(b) =>
        b.stripPrefix("untyped-") match
          case "int"              => goData("GoInt")
          case "uint"             => goData("GoUint")
          case "uint64"           => goData("GoUint64")
          case "uintptr"          => goData("GoUintptr")
          case "uint32"           => goData("GoUint32")
          case "uint8" | "byte"   => goData("GoByte")
          case "string"           => goData("GoString")
          case "complex64"        => no("complex64 inside Go data")
          case other              => basic(other)
      case GoType.Named(pkg, n) if isFuncAlias(pkg, n)       => no("a func inside Go data")
      case GoType.Named(pkg, n)                              => named(pkg, n)
      case GoType.Ptr(GoType.Named(pkg, n)) if isStruct(pkg, n) => named(pkg, n)
      case GoType.Generic(pkg, n, _) if isFuncAlias(pkg, n)  => no("a func inside Go data")
      case GoType.Generic(pkg, n, as)                        => generic(pkg, n, as)
      case GoType.Ptr(GoType.Generic(pkg, n, as)) if isStruct(pkg, n) => generic(pkg, n, as)
      case GoType.Ptr(e)                                     => goData("GoPtr", inner(e))
      case GoType.Slice(e)                                   => goData("GoSlice", inner(e))
      case GoType.MapOf(k, v)                                => goData("GoMap", inner(k), inner(v))
      case GoType.Chan(e, _)                                 => goData("GoChan", inner(e))
      case GoType.EmptyInterface                             => SType.Any
      case GoType.EmptyStruct                                => SType("_root_.scala.Unit", ScalaType.Ref(GoInterop.UnitClass, Nil))
      case GoType.Func(_)                                    => no("a func inside Go data")
      case GoType.ArrayOf(_, _)                              => no("a Go array inside Go data")
      case GoType.TypeParam(n)                               => typeParam(n)
      case c: GoType.Constraint                              => no(s"constraint $c")
      case GoType.Other(d)                                   => no(d)

    /** A Go func type as a Scala function type. Its results are as a function's: a trailing
     *  `error` is an exception (thrown by the Scala function, or by the Go func called from Scala),
     *  several results are a tuple.
     */
    private def function(sig: GoSignature, isResult: Boolean): SType =
      if sig.variadic then no("a variadic func type")
      if isResult && sig.params.size > 3 then no("a returned func with more than 3 parameters")
      SType.function(sig.params.map(outer(_, isResult = false)), results(sig.results))

    /** Functions whose `error` result is their value, not a failure (`errors.New`, `ctx.Err()`). */
    private def errorIsValue(name: String): Boolean =
      name.startsWith("New") || Set("Errorf", "Join", "Unwrap", "Err", "Cause")(name)

    /** The result type: a trailing error is thrown, several values are a tuple. */
    def results(rs: List[GoType], name: String = ""): SType =
      val values = if rs.lastOption.contains(GoType.Error) && !(rs.size == 1 && errorIsValue(name)) then rs.init else rs
      values match
        case Nil       => SType.prim(Type.Void)
        case List(one) => outer(one, isResult = true)
        case several if several.size <= 22 => SType.tuple(several.map(outer(_, isResult = true)))
        case _         => no("too many results")

    /** The parameters, with their names; a variadic one is repeated (`T*`). */
    def paramList(sig: GoSignature): List[(String, SType)] =
      val names = sig.params.indices.map { i =>
        sig.paramNames.lift(i).filter(n => n != "_" && n.nonEmpty).getOrElse(s"arg$i")
      }
      val distinct = names.zipWithIndex.map((n, i) => if names.indexOf(n) != i then s"${n}$i" else n)
      sig.params.zip(distinct).zipWithIndex.map { case ((t, n), i) =>
        if sig.variadic && i == sig.params.size - 1 then
          t match
            case GoType.Slice(e) => n -> SType.repeated(outer(e, isResult = false))
            case _               => no("a variadic parameter that is not a slice")
        else n -> outer(t, isResult = false)
      }

    def params(sig: GoSignature): String =
      paramList(sig).map((n, t) => s"${ident(n)}: ${t.source}").mkString("(", ", ", ")")

    /** A generic function's signature as Scala declares it, with the Scala type parameters.
     *
     *  - When each type parameter is constrained by a named interface and used by a parameter,
     *    the function is monomorphic: the parameter becomes the constraint
     *    (`Matches[K fmt.Stringer](k K)` takes a `fmt.Stringer`), which satisfies it, and the
     *    linker passes values of that type, so that Go infers it as the type argument.
     *  - Otherwise a type parameter whose constraint has a core type (`S ~[]E`) is that type
     *    (`[]E`), and the others are Scala type parameters.
     */
    private def prepare(sig: GoSignature): (GoSignature, List[String]) =
      if !sig.generic then (sig, Nil)
      else
        val interfaceBounds = sig.typeParams.collect { case (n, c: GoType.Named) => n -> (c: GoType) }.toMap
        val used = (n: String) => sig.params.exists(_.mentions(n))
        if interfaceBounds.size == sig.typeParams.size && sig.typeParams.forall((n, _) => used(n)) then
          (sig.substitute(interfaceBounds), Nil)
        else
          val cores = sig.typeParams.collect { case (n, GoType.Constraint(Some(core), _, _)) if isComposite(core) => n -> core }.toMap
          // Cores may mention each other's parameters (`M ~map[K]V`): substitute until none is left.
          var resolved = cores
          for _ <- cores.toSeq do resolved = resolved.map((n, c) => n -> c.substitute(resolved))
          if resolved.values.exists(c => cores.keys.exists(c.mentions)) then no("generic, with cyclic core types")
          val substituted = sig.substitute(resolved)
          (substituted.copy(generic = false, typeParams = Nil), substituted.typeParams.map(_._1))

    private def isComposite(g: GoType): Boolean = g match
      case _: GoType.Slice | _: GoType.MapOf | _: GoType.Chan | _: GoType.Ptr | _: GoType.Func | _: GoType.ArrayOf => true
      case _ => false

    // ---- Members ---------------------------------------------------------------------------------

    private def member(name: String, out: mutable.ListBuffer[String], skipped: mutable.ListBuffer[String])(body: => String): Unit =
      try out += body
      catch case e: Unrepresentable => skipped += s"$name (${e.reason})"

    private def method(name: String, genericSig: GoSignature, abstractMember: Boolean): String =
      val (sig, typeParams) = prepare(genericSig)
      val rhs = if abstractMember then "" else " = _root_.scalago.go.native"
      val tparams = if typeParams.isEmpty then "" else typeParams.map(ident).mkString("[", ", ", "]")
      s"def $name$tparams${params(sig)}: ${results(sig.results, name).source}$rhs"

    /** The methods of a type that can be generated. */
    private def representable(decl: GoTypeDecl): Map[String, GoSignature] =
      decl.methods.filter { (m, sig) =>
        !decl.fields.contains(m) && (try { method(m, sig, abstractMember = true); true } catch case _: Unrepresentable => false)
      }

    /** Whether the methods that dependency facade `cls` (a trait) and its parents declare are
     *  `methods`, with the types generated here: then generated types can extend it (otherwise
     *  they might not implement its methods: a handwritten trait may declare other types).
     */
    private def declaresAsGenerated(cls: ClassName, methods: Map[String, GoSignature]): Boolean =
      val declared = provided.methods(cls)
      declared.keySet == methods.keySet && methods.forall { (m, genericSig) =>
        val g = declared(m)
        try
          val (sig, typeParams) = prepare(genericSig)
          typeParams.isEmpty && !g.isProperty && g.params == paramList(sig).map(_._2.ir) && g.result == results(sig.results, m).ir
        catch case _: Unrepresentable => false
      }

    /** The interfaces that generated types can extend, with their (representable) method sets:
     *  the generated ones, and dependencies' facades of the interfaces of the loaded packages.
     */
    private lazy val interfaces: Seq[((String, String), Map[String, GoSignature])] =
      def complete(d: GoTypeDecl): Option[Map[String, GoSignature]] =
        Option.when(d.kind == GoTypeKind.Interface && !d.isGeneric)(representable(d)).filter(ms => ms.size == d.methods.size && ms.nonEmpty)
      val generated =
        for
          key @ (p, n) <- included.toSeq.sorted
          methods <- complete(api.typeDecl(p, n).get)
        yield (key, methods)
      val fromDependencies =
        for
          (key @ (p, n), cls) <- provided.interfaces.filter((k, _) => api.packages.contains(k._1)).sortBy(_._1)
          methods <- api.typeDecl(p, n).flatMap(complete) if declaresAsGenerated(cls, methods)
        yield (key, methods)
      (generated ++ fromDependencies).map((key, methods) => (key, methods.map((m, sig) => m -> sig.copy(paramNames = Nil))))

    /** The interfaces that type `self` implements (Go typing is structural, Scala's nominal): the
     *  facade extends them. Between generated interfaces with the same methods, the order of their
     *  names decides, to avoid cycles.
     */
    private def parents(self: (String, String), decl: GoTypeDecl): Seq[String] =
      val mine = representable(decl).map((m, sig) => m -> sig.copy(paramNames = Nil))
      if decl.isGeneric then Nil
      else for
        (key @ (p, n), methods) <- interfaces
        if key != self && methods.forall((m, sig) => mine.get(m).contains(sig))
        if decl.kind != GoTypeKind.Interface || methods.size < mine.size || provided.typeOf(p, n).isDefined ||
          (methods.size == mine.size && key.toString < self.toString)
      yield named(p, n).source

    private def typeDef(pkg: GoPackage, name: String, decl: GoTypeDecl): String =
      aliasOf(pkg.path, name) match
        case Some(alias) =>
          val kind = if decl.isEmptyInterface then "interface type" else "func type"
          s"  /** Go $kind `${pkg.name}.$name`. */\n  type $name${typeParamClause(decl)} = ${alias.source}\n"
        case None => classDef(pkg, name, decl)

    private def classDef(pkg: GoPackage, name: String, decl: GoTypeDecl): String =
      val lines = mutable.ListBuffer.empty[String]
      val skipped = mutable.ListBuffer.empty[String]
      val isInterface = decl.kind == GoTypeKind.Interface
      for (f, t) <- decl.fields.toSeq.sortBy(_._1) do
        member(f, lines, skipped)(s"def $f: ${outer(t, isResult = true).source} = _root_.scalago.go.native")
        member(s"$f (setter)", lines, skipped) {
          if api.mentionsOpaque(t) then no(s"a field of a type that code outside its package cannot name")
          s"def ${f}_=(value: ${outer(t, isResult = false).source}): _root_.scala.Unit = _root_.scalago.go.native"
        }
      for (m, sig) <- decl.methods.toSeq.sortBy(_._1) if !decl.fields.contains(m) do
        member(m, lines, skipped)(method(m, sig, abstractMember = isInterface))
      for m <- decl.pointerMethods.keys.toSeq.sorted do
        skipped += s"$m (a method of *$name: Scala holds $name values as copies, which it would change)"
      val extendsClause = parents((pkg.path, name), decl) match
        case Nil => ""
        case ps  => ps.mkString(" extends ", ", ", "")
      val keyword = if isInterface then "trait" else "class"
      val kind = decl.kind match
        case GoTypeKind.Struct    => "struct type"
        case GoTypeKind.Interface => "interface type"
        case GoTypeKind.Other     => "type"
      val sb = StringBuilder(s"  /** Go $kind `${pkg.name}.$name`. */\n  $keyword $name${typeParamClause(decl)}$extendsClause")
      if lines.isEmpty && skipped.isEmpty then sb ++= "\n"
      else
        sb ++= ":\n"
        lines.foreach(l => sb ++= s"    $l\n")
        skipped.foreach(s => sb ++= s"    // not generated: $s\n")
        if lines.isEmpty then sb ++= s"  end $name\n"
      sb.toString

    /** The type parameters of a generic type (`[T]`), for its class, trait or alias. */
    private def typeParamClause(decl: GoTypeDecl): String =
      if decl.isGeneric then decl.typeParams.map(tp => ident(tp._1)).mkString("[", ", ", "]") else ""

    /** The conversion to a named non-struct type, as in Go (`lipgloss.Color("205")`): a method of
     *  the package object, named as the type. Named slice and map types also get their composite
     *  literals, from elements or key-value pairs (`gin.H("message" -> "pong")`).
     */
    private def conversions(pkg: GoPackage, name: String, decl: GoTypeDecl, skipped: mutable.ListBuffer[String]): List[String] =
      val out = mutable.ListBuffer.empty[String]
      for u <- decl.underlying if decl.kind == GoTypeKind.Other && !decl.isGeneric && aliasOf(pkg.path, name).isEmpty do
        def define(what: String, param: => String): Unit =
          member(s"$name ($what)", out, skipped)(s"def $name($param): ${named(pkg.path, name).source} = _root_.scalago.go.native")
        define("conversion", s"value: ${outer(u, isResult = false).source}")
        u match
          case GoType.Slice(e) => define("literal", s"elems: ${SType.repeated(outer(e, isResult = false)).source}")
          case GoType.MapOf(k, v) =>
            define("literal", s"entries: ${SType.repeated(SType.tuple(List(outer(k, isResult = false), outer(v, isResult = false)))).source}")
          case _ => ()
      out.toList

    /** A constant's type; an untyped one is typed by its value. Integers beyond `Long` that fit a
     *  `uint64` (`math.MaxUint64`) are the `Long` of their bits, as `uint64` values are; bigger ones
     *  are left out.
     */
    private def constant(pkg: GoPackage, name: String, t: GoType): SType = t match
      case GoType.Basic(b) if b.startsWith("untyped-") =>
        val value = pkg.constValues.getOrElse(name, "")
        b.stripPrefix("untyped-") match
          case "int" if !GoConstants.fitsUint64Bits(value) => no(s"the value $value does not fit a Long or a uint64")
          case "int"                               => SType.prim(Type.Long)
          case "rune"                              => SType.prim(Type.Int)
          case "float"                             => SType.prim(Type.Double)
          case "complex"                           => SType.ref(GoInterop.GoComplexType)
          case "string"                            => SType.ref(ClassName.String)
          case "bool"                              => SType.prim(Type.Boolean)
          case other                               => no(s"untyped $other constant")
      case GoType.Basic(b) if b.startsWith("int") || b.startsWith("uint") =>
        val value = pkg.constValues.getOrElse(name, "0")
        if !GoConstants.fitsUint64Bits(value) then no(s"the value $value does not fit a Long or a uint64")
        outer(t, isResult = true)
      case other => outer(other, isResult = true)

    /** The functions, variables and constants of a root package, by Go name: their definitions,
     *  or why they cannot be generated.
     */
    private def rootMembers(pkg: GoPackage): Seq[(String, Either[String, String])] =
      def attempt(body: => String): Either[String, String] =
        try Right(body) catch case e: Unrepresentable => Left(e.reason)
      def native(n: String, t: SType) = s"def $n: ${t.source} = _root_.scalago.go.native"
      pkg.funcs.toSeq.sortBy(_._1).map((n, sig) => n -> attempt(method(n, sig, abstractMember = false))) ++
        pkg.vars.toSeq.sortBy(_._1).map((n, t) => n -> attempt(native(n, outer(t, isResult = true)))) ++
        pkg.consts.toSeq.sortBy(_._1).map((n, t) => n -> attempt(native(n, constant(pkg, n, t))))

    /** Whether dependencies' facades provide all that root package `path` would get: its types,
     *  and every function, variable and constant that can be generated.
     */
    def isProvided(path: String): Boolean =
      api.packages.get(path).exists { pkg =>
        val members = provided.members(path)
        provided.packageObjects(path).nonEmpty &&
          (pkg.types.keys ++ pkg.aliases.keys).forall(provided.typeOf(path, _).isDefined) &&
          rootMembers(pkg).forall((n, m) => m.isLeft || members(n))
      }

    private def objectSource(path: String): String =
      val pkg = api.packages(path)
      val isRoot = roots.contains(path)
      val lines = mutable.ListBuffer.empty[String]
      val skipped = mutable.ListBuffer.empty[String]
      val types = pkg.types.toSeq.sortBy(_._1).filter((n, _) => included((path, n)))
      if isRoot then
        for (n, m) <- rootMembers(pkg) do m.fold(reason => skipped += s"$n ($reason)", lines += _)
      // A root package has the conversions to all its types, dependencies' ones included.
      val converted = if isRoot then pkg.types.toSeq.sortBy(_._1).filter((n, _) => facadeRef(path, n).isDefined) else types
      for (n, d) <- converted do lines ++= conversions(pkg, n, d, skipped)
      if isRoot then
        for (n, target) <- pkg.aliases.toSeq.sortBy(_._1) if provided.typeOf(path, n).isEmpty do
          member(n, lines, skipped) {
            target match
              case t if opaqueView(t).isDefined => s"type $n = ${opaqueView(t).get.source}"
              case GoType.Named(p, tn)       => s"type $n = ${named(p, tn).source}"
              case GoType.Generic(p, tn, as) => s"type $n = ${generic(p, tn, as).source}"
              case other               => no(s"alias of $other")
          }
      val typeDefs = types.map((n, d) => typeDef(pkg, n, d))
      val obj = objectNames(path)
      val sb = StringBuilder()
      sb ++= "// Code generated by scala-go (FacadeGen). DO NOT EDIT.\n\n"
      sb ++= s"package $scalaPackage\n\n"
      if isRoot then sb ++= s"/** Go package `$path`. */\n"
      else sb ++= s"/** Go package `$path`: the types that the generated packages use. */\n"
      sb ++= s"@_root_.scalago.go.GoImport(${FunctionEmitter.goStringLiteral(path)})\n"
      sb ++= s"object ${ident(obj)}:\n"
      lines.foreach(l => sb ++= s"  $l\n")
      skipped.foreach(s => sb ++= s"  // not generated: $s\n")
      if typeDefs.nonEmpty then sb ++= "\n" ++= typeDefs.mkString("\n")
      sb ++= s"end ${ident(obj)}\n"
      sb.toString

    def files: Map[String, String] =
      for p <- packages if provided.hasFacade(s"$scalaPackage.${objectNames(p)}$$") do
        throw FacadeGenException(s"cannot generate the facades of Go package $p as $scalaPackage.${objectNames(p)}: " +
          "a dependency has a facade object of that name, which does not provide all that is needed; " +
          "generate the facades into another Scala package")
      packages.map(p => s"${objectNames(p)}.scala" -> objectSource(p)).toMap
  end Generator

  // ---- Command line ---------------------------------------------------------------------------------

  def main(args: Array[String]): Unit =
    // Options (each with one value) come first; the remaining arguments are the packages.
    val pairs = mutable.ListBuffer.empty[(String, String)]
    var i = 0
    while i < args.length && args(i).startsWith("--") do
      pairs += args(i) -> args.lift(i + 1).getOrElse("")
      i += 2
    val options = pairs.toList
    val (packages, objectNames) = args.toList.drop(i).foldRight((List.empty[String], Map.empty[String, String])) {
      case (s"$path=$name", (ps, ns)) => (path :: ps, ns + (path -> name))
      case (path, (ps, ns))           => (path :: ps, ns)
    }
    def all(key: String) = options.collect { case (`key`, v) => v }
    def split(s: String, sep: Char) =
      val i = s.indexOf(sep)
      (s.substring(0, i), s.substring(i + 1))
    val out = all("--out").lastOption
    val scalaPackage = all("--package").lastOption
    if out.isEmpty || scalaPackage.isEmpty || packages.isEmpty then
      System.err.println("usage: scalago-facadegen --out <dir> --package <scala package> [--classpath <cp>] " +
        "[--go-require m@v]... [--go-replace m=dir]... <import path>[=<object>]...")
      sys.exit(2)
    val classpath = all("--classpath").flatMap(_.split(java.io.File.pathSeparator)).filter(_.nonEmpty).map(Path.of(_))
    val config = Config(scalaPackage.get, packages, all("--go-require").map(split(_, '@')), all("--go-replace").map(split(_, '=')),
      classpath, objectNames)
    try
      val result = run(config, Path.of(out.get))
      result.notes.foreach(System.err.println)
      result.files.foreach(f => println(s"generated $f"))
    catch
      case e @ (_: GoApi.GoApiException | _: FacadeGenException) =>
        System.err.println(e.getMessage)
        sys.exit(1)
end FacadeGen
