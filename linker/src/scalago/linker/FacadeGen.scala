package scalago.linker

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Generates Scala facades (`@GoImport` objects) from Go packages, as ScalablyTyped does from
 *  TypeScript declarations. The `goapi` helper reads the packages' APIs; each package becomes one
 *  Scala object, in one file:
 *  {{{
 *  scalago-facadegen --out <dir> --package <scala package> [--go-require m@v]... [--go-replace m=dir]...
 *                    <import path>...
 *  }}}
 *  Types map as the facade conversions allow, choosing exact and cheap types:
 *  - parameters and results: Go numbers as Scala numbers (`int` is `Long`), `string` as `String`,
 *    `error` as `GoError`, `any` as `Any`, Go types of the generated packages as their facades,
 *    funcs as Scala functions, `(T, error)` results as `T` (the error is thrown), several results as
 *    tuples;
 *  - inside Go data (`[]T`, `map[K]V`, `chan T`, `*T`): canonical views (`GoSlice[GoString]`,
 *    `GoMap[GoString, GoInt]`), so slices are shared and creating them gives the right Go type.
 *  - Go func types and interfaces without methods are type aliases (`type Cmd = () => Any`), so
 *    Scala functions and values are passed as they are; other named non-struct types get a
 *    conversion named as the type (`def Color(value: String): Color`), as in Go.
 *  - generic functions whose type parameters are constrained by interfaces take the interfaces.
 *  The types of other packages that the members use are generated too (only the types), so the
 *  facades of a package are complete without listing its dependencies. Members whose types cannot
 *  be represented (generics with type sets, unnamed structs and interfaces, arrays, funcs inside
 *  Go data) are left out, with a comment saying why. Facades are checked against Go at link time
 *  only where used, so unused members cost nothing.
 */
object FacadeGen:
  final case class Config(scalaPackage: String, packages: Seq[String], goRequires: Seq[(String, String)] = Nil,
      goReplaces: Seq[(String, String)] = Nil)

  /** Generates the facades into `outDir` (one file per package); returns the files written. */
  def run(config: Config, outDir: Path): Seq[Path] =
    val moduleDir = Files.createTempDirectory("scalago-facadegen-")
    try
      val goMod = StringBuilder("module scalago.facadegen\n\ngo 1.22\n")
      if config.goRequires.nonEmpty then goMod ++= config.goRequires.map((m, v) => s"\t$m $v\n").mkString("\nrequire (\n", "", ")\n")
      for (m, dir) <- config.goReplaces do goMod ++= s"\nreplace $m => $dir\n"
      Files.writeString(moduleDir.resolve("go.mod"), goMod.toString, UTF_8)
      // The packages whose types the generated members use are loaded too, until none is missing.
      var api = GoApi.load(moduleDir, config.packages)
      var missing = Generator(api, config.scalaPackage, config.packages).missingPackages
      while missing.nonEmpty do
        api = GoApi.load(moduleDir, (api.packages.keySet ++ missing).toSeq.sorted)
        missing = Generator(api, config.scalaPackage, config.packages).missingPackages
      Files.createDirectories(outDir)
      for (name, source) <- generate(api, config.scalaPackage, config.packages).toSeq yield
        val file = outDir.resolve(name)
        Files.writeString(file, source, UTF_8)
        file
    finally Using.resource(Files.walk(moduleDir))(_.iterator.asScala.toList.reverse.foreach(Files.deleteIfExists))

  /** Scala source files (name to content) for `packages` of `api`, and for the types of other
   *  packages of `api` that their members use.
   */
  def generate(api: GoApi, scalaPackage: String, packages: Seq[String]): Map[String, String] =
    Generator(api, scalaPackage, packages).files

  private val scalaKeywords = Set("abstract", "case", "catch", "class", "def", "do", "else", "enum", "export",
    "extends", "false", "final", "finally", "for", "forSome", "given", "if", "implicit", "import", "lazy", "match",
    "new", "null", "object", "override", "package", "private", "protected", "return", "sealed", "super", "then",
    "this", "throw", "trait", "true", "try", "type", "val", "var", "while", "with", "yield", "end", "inline",
    "opaque", "open", "transparent", "infix", "derives", "erased")

  def ident(name: String): String = if scalaKeywords(name) || !name.head.isLetter then s"`$name`" else name

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
    case _                     => Nil

  private def namedIn(sig: GoSignature): List[(String, String)] =
    (sig.params ++ sig.results ++ sig.typeParams.map(_._2)).flatMap(namedIn)

  private final class Generator(api: GoApi, scalaPackage: String, roots: Seq[String]):
    /** The types facades are generated for: all types of the root packages, and the types of other
     *  packages their members use (transitively). The other packages' objects only have these.
     */
    private val (included: Set[(String, String)], missing: Set[String]) =
      val seen = mutable.LinkedHashSet.empty[(String, String)]
      val missing = mutable.LinkedHashSet.empty[String]
      val queue = mutable.Queue.empty[(String, String)]
      def use(refs: List[(String, String)]): Unit =
        for r @ (p, n) <- refs if importable(p) && !seen(r) do
          if !api.packages.contains(p) then missing += p
          else if api.typeDecl(p, n).isDefined then
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
      (seen.toSet, missing.toSet)

    /** Packages whose types are used but whose APIs are not loaded. */
    def missingPackages: Set[String] = missing

    /** The generated packages: the roots, then those of the other included types. */
    private val packages: Seq[String] =
      (roots.filter(api.packages.contains) ++ included.toSeq.map(_._1).sorted).distinct

    /** Scala object name of each generated package: its Go name, or its path when ambiguous. */
    private val objectNames: Map[String, String] =
      val byName = packages.groupBy(p => api.packages(p).name)
      packages.map { p =>
        val name = api.packages(p).name
        p -> (if byName(name).size == 1 then name else p.replaceAll("[^A-Za-z0-9]", "_"))
      }.toMap

    private def facadeRef(pkg: String, name: String): Option[String] =
      for
        obj <- objectNames.get(pkg)
        p <- api.packages.get(pkg)
        if included((pkg, name)) || (roots.contains(pkg) && p.aliases.contains(name))
      yield s"_root_.$scalaPackage.${ident(obj)}.$name"

    private final class Unrepresentable(val reason: String) extends Exception(reason)
    private def no(reason: String): Nothing = throw Unrepresentable(reason)

    private def isStruct(pkg: String, name: String): Boolean =
      api.typeDecl(pkg, name).exists(_.kind == GoTypeKind.Struct)

    // ---- Types ------------------------------------------------------------------------------------

    private def basic(b: String): String = b.stripPrefix("untyped-") match
      case "bool"                                         => "_root_.scala.Boolean"
      case "string"                                       => "_root_.java.lang.String"
      case "int" | "int64" | "uint" | "uint64" | "uintptr" => "_root_.scala.Long"
      case "int32" | "rune" | "uint32"                    => "_root_.scala.Int"
      case "int16"                                        => "_root_.scala.Short"
      case "uint16"                                       => "_root_.scala.Char"
      case "int8" | "uint8" | "byte"                      => "_root_.scala.Byte"
      case "float32"                                      => "_root_.scala.Float"
      case "float64" | "float"                            => "_root_.scala.Double"
      case other                                          => no(s"Go type $other")

    private def named(pkg: String, name: String): String =
      if pkg == "" && name == "error" then "_root_.scalago.go.GoError"
      else facadeRef(pkg, name).getOrElse(no(s"type $pkg.$name is not generated"))

    /** The Scala type alias standing for Go type `pkg.name`, if it is one: interfaces without
     *  methods are `Any`, func types without methods are Scala function types, so that Scala
     *  values and lambdas can be passed directly (`tea.Msg`, `tea.Cmd`).
     */
    private def aliasOf(pkg: String, name: String): Option[String] =
      api.typeDecl(pkg, name).flatMap { decl =>
        if decl.isEmptyInterface then Some("_root_.scala.Any")
        else if decl.methods.isEmpty then
          decl.funcSignature.flatMap(sig => try Some(function(sig, isResult = true)) catch case _: Unrepresentable => None)
        else None
      }

    private def isFuncAlias(pkg: String, name: String): Boolean =
      api.typeDecl(pkg, name).exists(_.funcSignature.isDefined) && aliasOf(pkg, name).isDefined

    /** The Scala type of a parameter or result. */
    def outer(g: GoType, isResult: Boolean): String = g match
      case GoType.Basic(b)                                   => basic(b)
      case GoType.Named(pkg, n)                              => named(pkg, n)
      case GoType.Ptr(GoType.Named(pkg, n)) if isStruct(pkg, n) => named(pkg, n)
      case GoType.EmptyInterface                             => "_root_.scala.Any"
      case GoType.Func(sig)                                  => function(sig, isResult)
      case other                                             => inner(other)

    /** The canonical Scala view of a Go type inside Go data. */
    def inner(g: GoType): String = g match
      case GoType.Basic(b) =>
        b.stripPrefix("untyped-") match
          case "int"              => "_root_.scalago.go.GoInt"
          case "uint"             => "_root_.scalago.go.GoUint"
          case "uint64"           => "_root_.scalago.go.GoUint64"
          case "uintptr"          => "_root_.scalago.go.GoUintptr"
          case "uint32"           => "_root_.scalago.go.GoUint32"
          case "uint8" | "byte"   => "_root_.scalago.go.GoByte"
          case "string"           => "_root_.scalago.go.GoString"
          case other              => basic(other)
      case GoType.Named(pkg, n) if isFuncAlias(pkg, n)       => no("a func inside Go data")
      case GoType.Named(pkg, n)                              => named(pkg, n)
      case GoType.Ptr(GoType.Named(pkg, n)) if isStruct(pkg, n) => named(pkg, n)
      case GoType.Ptr(e)                                     => s"_root_.scalago.go.GoPtr[${inner(e)}]"
      case GoType.Slice(e)                                   => s"_root_.scalago.go.GoSlice[${inner(e)}]"
      case GoType.MapOf(k, v)                                => s"_root_.scalago.go.GoMap[${inner(k)}, ${inner(v)}]"
      case GoType.Chan(e, _)                                 => s"_root_.scalago.go.GoChan[${inner(e)}]"
      case GoType.EmptyInterface                             => "_root_.scala.Any"
      case GoType.Func(_)                                    => no("a func inside Go data")
      case GoType.ArrayOf(_, _)                              => no("a Go array type")
      case GoType.TypeParam(n)                               => no(s"type parameter $n")
      case GoType.Other(d)                                   => no(d)

    private def function(sig: GoSignature, isResult: Boolean): String =
      if sig.variadic || sig.generic then no("a variadic or generic func type")
      if isResult && sig.params.size > 3 then no("a returned func with more than 3 parameters")
      val params = sig.params.map(outer(_, isResult = false))
      val result = sig.results match
        case Nil       => "_root_.scala.Unit"
        case List(one) => outer(one, isResult = true)
        case _         => no("a func type with several results")
      params match
        case List(one) => s"$one => $result"
        case ps        => ps.mkString("(", ", ", s") => $result")

    /** Functions whose `error` result is their value, not a failure (`errors.New`, `ctx.Err()`). */
    private def errorIsValue(name: String): Boolean =
      name.startsWith("New") || Set("Errorf", "Join", "Unwrap", "Err", "Cause")(name)

    /** The result type: a trailing error is thrown, several values are a tuple. */
    def results(rs: List[GoType], name: String = ""): String =
      val values = if rs.lastOption.contains(GoType.Error) && !(rs.size == 1 && errorIsValue(name)) then rs.init else rs
      values match
        case Nil       => "_root_.scala.Unit"
        case List(one) => outer(one, isResult = true)
        case several if several.size <= 22 => several.map(outer(_, isResult = true)).mkString("(", ", ", ")")
        case _         => no("too many results")

    def params(sig: GoSignature): String =
      val names = sig.params.indices.map { i =>
        sig.paramNames.lift(i).filter(n => n != "_" && n.nonEmpty).getOrElse(s"arg$i")
      }
      val distinct = names.zipWithIndex.map((n, i) => if names.indexOf(n) != i then s"${n}$i" else n)
      sig.params.zip(distinct).zipWithIndex.map { case ((t, n), i) =>
        if sig.variadic && i == sig.params.size - 1 then
          t match
            case GoType.Slice(e) => s"${ident(n)}: ${outer(e, isResult = false)}*"
            case _               => no("a variadic parameter that is not a slice")
        else s"${ident(n)}: ${outer(t, isResult = false)}"
      }.mkString("(", ", ", ")")

    /** A generic function as a monomorphic one, when each type parameter's constraint is an
     *  interface without type sets: the parameter becomes the constraint (`Matches[K fmt.Stringer](k K)`
     *  takes a `fmt.Stringer`), which satisfies it, and the linker passes values of that type, so
     *  that Go infers it as the type argument.
     */
    private def monomorphic(sig: GoSignature): GoSignature =
      if !sig.generic then sig
      else
        val bounds = sig.typeParams.map { (n, c) =>
          c match
            case GoType.EmptyInterface | _: GoType.Named => n -> c
            case _                                     => no(s"generic, with a type set constraint on $n")
        }.toMap
        def subst(g: GoType): GoType = g match
          case GoType.TypeParam(n)  => bounds.getOrElse(n, no(s"generic, type parameter $n"))
          case GoType.Ptr(e)        => GoType.Ptr(subst(e))
          case GoType.Slice(e)      => GoType.Slice(subst(e))
          case GoType.ArrayOf(l, e) => GoType.ArrayOf(l, subst(e))
          case GoType.MapOf(k, v)   => GoType.MapOf(subst(k), subst(v))
          case GoType.Chan(e, d)    => GoType.Chan(subst(e), d)
          case GoType.Func(s)       => GoType.Func(s.copy(params = s.params.map(subst), results = s.results.map(subst)))
          case other                => other
        if bounds.isEmpty then no("generic")
        sig.copy(params = sig.params.map(subst), results = sig.results.map(subst), generic = false, typeParams = Nil)

    // ---- Members ---------------------------------------------------------------------------------

    private def member(name: String, out: mutable.ListBuffer[String], skipped: mutable.ListBuffer[String])(body: => String): Unit =
      try out += body
      catch case e: Unrepresentable => skipped += s"$name (${e.reason})"

    private def method(name: String, genericSig: GoSignature, abstractMember: Boolean): String =
      val sig = monomorphic(genericSig)
      val rhs = if abstractMember then "" else " = _root_.scalago.go.native"
      s"def $name${params(sig)}: ${results(sig.results, name)}$rhs"

    /** The methods of a type that can be generated. */
    private def representable(decl: GoTypeDecl): Map[String, GoSignature] =
      decl.methods.filter { (m, sig) =>
        !decl.fields.contains(m) && (try { method(m, sig, abstractMember = true); true } catch case _: Unrepresentable => false)
      }

    /** Generated interfaces, with their (representable) method sets. */
    private lazy val interfaces: Seq[((String, String), Map[String, GoSignature])] =
      for
        key @ (p, n) <- included.toSeq.sorted
        d = api.typeDecl(p, n).get if d.kind == GoTypeKind.Interface
        methods = representable(d) if methods.size == d.methods.size && methods.nonEmpty
      yield (key, methods.map((m, sig) => m -> sig.copy(paramNames = Nil)))

    /** The generated interfaces that type `self` implements (Go typing is structural, Scala's
     *  nominal): the facade extends them. Between interfaces with the same methods, the order of
     *  their names decides, to avoid cycles.
     */
    private def parents(self: (String, String), decl: GoTypeDecl): Seq[String] =
      val mine = representable(decl).map((m, sig) => m -> sig.copy(paramNames = Nil))
      for
        (key @ (p, n), methods) <- interfaces
        if key != self && methods.forall((m, sig) => mine.get(m).contains(sig))
        if decl.kind != GoTypeKind.Interface || methods.size < mine.size || (methods.size == mine.size && key.toString < self.toString)
      yield named(p, n)

    private def typeDef(pkg: GoPackage, name: String, decl: GoTypeDecl): String =
      aliasOf(pkg.path, name) match
        case Some(alias) =>
          val kind = if decl.isEmptyInterface then "interface type" else "func type"
          s"  /** Go $kind `${pkg.name}.$name`. */\n  type $name = $alias\n"
        case None => classDef(pkg, name, decl)

    private def classDef(pkg: GoPackage, name: String, decl: GoTypeDecl): String =
      val lines = mutable.ListBuffer.empty[String]
      val skipped = mutable.ListBuffer.empty[String]
      val isInterface = decl.kind == GoTypeKind.Interface
      for (f, t) <- decl.fields.toSeq.sortBy(_._1) do
        member(f, lines, skipped)(s"def $f: ${outer(t, isResult = true)} = _root_.scalago.go.native")
        member(s"$f (setter)", lines, skipped)(s"def ${f}_=(value: ${outer(t, isResult = false)}): _root_.scala.Unit = _root_.scalago.go.native")
      for (m, sig) <- decl.methods.toSeq.sortBy(_._1) if !decl.fields.contains(m) do
        member(m, lines, skipped)(method(m, sig, abstractMember = isInterface))
      val extendsClause = parents((pkg.path, name), decl) match
        case Nil => ""
        case ps  => ps.mkString(" extends ", ", ", "")
      val keyword = if isInterface then "trait" else "class"
      val kind = decl.kind match
        case GoTypeKind.Struct    => "struct type"
        case GoTypeKind.Interface => "interface type"
        case GoTypeKind.Other     => "type"
      val sb = StringBuilder(s"  /** Go $kind `${pkg.name}.$name`. */\n  $keyword $name$extendsClause")
      if lines.isEmpty && skipped.isEmpty then sb ++= "\n"
      else
        sb ++= ":\n"
        lines.foreach(l => sb ++= s"    $l\n")
        skipped.foreach(s => sb ++= s"    // not generated: $s\n")
        if lines.isEmpty then sb ++= s"  end $name\n"
      sb.toString

    /** The conversion to a named non-struct type, as in Go (`lipgloss.Color("205")`): a method of
     *  the package object, named as the type.
     */
    private def conversion(pkg: GoPackage, name: String, decl: GoTypeDecl): Option[String] =
      decl.underlying.filter(_ => decl.kind == GoTypeKind.Other && aliasOf(pkg.path, name).isEmpty).map { u =>
        s"def $name(value: ${outer(u, isResult = false)}): ${named(pkg.path, name)} = _root_.scalago.go.native"
      }

    /** An untyped constant, typed by its value (big integers that do not fit a Long are left out). */
    private def constant(pkg: GoPackage, name: String, t: GoType): String = t match
      case GoType.Basic(b) if b.startsWith("untyped-") =>
        val value = pkg.constValues.getOrElse(name, "")
        b.stripPrefix("untyped-") match
          case "int" if value.toLongOption.isEmpty => no(s"the value $value does not fit a Long")
          case "int"                               => "_root_.scala.Long"
          case "rune"                              => "_root_.scala.Int"
          case "float"                             => "_root_.scala.Double"
          case "string"                            => "_root_.java.lang.String"
          case "bool"                              => "_root_.scala.Boolean"
          case other                               => no(s"untyped $other constant")
      case GoType.Basic(b) if b.startsWith("int") || b.startsWith("uint") =>
        val value = pkg.constValues.getOrElse(name, "0")
        if value.toLongOption.isEmpty then no(s"the value $value does not fit a Long")
        outer(t, isResult = true)
      case other => outer(other, isResult = true)

    private def objectSource(path: String): String =
      val pkg = api.packages(path)
      val isRoot = roots.contains(path)
      val lines = mutable.ListBuffer.empty[String]
      val skipped = mutable.ListBuffer.empty[String]
      val types = pkg.types.toSeq.sortBy(_._1).filter((n, _) => included((path, n)))
      if isRoot then
        for (n, sig) <- pkg.funcs.toSeq.sortBy(_._1) do member(n, lines, skipped)(method(n, sig, abstractMember = false))
        for (n, t) <- pkg.vars.toSeq.sortBy(_._1) do
          member(n, lines, skipped)(s"def $n: ${outer(t, isResult = true)} = _root_.scalago.go.native")
        for (n, t) <- pkg.consts.toSeq.sortBy(_._1) do
          member(n, lines, skipped)(s"def $n: ${constant(pkg, n, t)} = _root_.scalago.go.native")
      for (n, d) <- types do
        try conversion(pkg, n, d).foreach(lines += _)
        catch case e: Unrepresentable => skipped += s"$n (conversion) (${e.reason})"
      if isRoot then
        for (n, target) <- pkg.aliases.toSeq.sortBy(_._1) do
          member(n, lines, skipped) {
            target match
              case GoType.Named(p, tn) => s"type $n = ${named(p, tn)}"
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
    val packages = args.toList.drop(i)
    def all(key: String) = options.collect { case (`key`, v) => v }
    def split(s: String, sep: Char) =
      val i = s.indexOf(sep)
      (s.substring(0, i), s.substring(i + 1))
    val out = all("--out").lastOption
    val scalaPackage = all("--package").lastOption
    if out.isEmpty || scalaPackage.isEmpty || packages.isEmpty then
      System.err.println("usage: scalago-facadegen --out <dir> --package <scala package> " +
        "[--go-require m@v]... [--go-replace m=dir]... <import path>...")
      sys.exit(2)
    val config = Config(scalaPackage.get, packages, all("--go-require").map(split(_, '@')), all("--go-replace").map(split(_, '=')))
    try run(config, Path.of(out.get)).foreach(f => println(s"generated $f"))
    catch
      case e: GoApi.GoApiException =>
        System.err.println(e.getMessage)
        sys.exit(1)
end FacadeGen
