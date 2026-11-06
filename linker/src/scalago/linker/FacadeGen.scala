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
 *  Members whose types cannot be represented (generics, types of packages not generated, unnamed
 *  structs and interfaces, arrays) are left out, with a comment saying why. Facades are checked
 *  against Go at link time only where used, so unused members cost nothing.
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
      val api = GoApi.load(moduleDir, config.packages)
      Files.createDirectories(outDir)
      for (name, source) <- generate(api, config.scalaPackage, config.packages).toSeq yield
        val file = outDir.resolve(name)
        Files.writeString(file, source, UTF_8)
        file
    finally Using.resource(Files.walk(moduleDir))(_.iterator.asScala.toList.reverse.foreach(Files.deleteIfExists))

  /** Scala source files (name to content) for `packages` of `api`. */
  def generate(api: GoApi, scalaPackage: String, packages: Seq[String]): Map[String, String] =
    Generator(api, scalaPackage, packages).files

  private val scalaKeywords = Set("abstract", "case", "catch", "class", "def", "do", "else", "enum", "export",
    "extends", "false", "final", "finally", "for", "forSome", "given", "if", "implicit", "import", "lazy", "match",
    "new", "null", "object", "override", "package", "private", "protected", "return", "sealed", "super", "then",
    "this", "throw", "trait", "true", "try", "type", "val", "var", "while", "with", "yield", "end", "inline",
    "opaque", "open", "transparent", "infix", "derives", "erased")

  def ident(name: String): String = if scalaKeywords(name) || !name.head.isLetter then s"`$name`" else name

  private final class Generator(api: GoApi, scalaPackage: String, roots: Seq[String]):
    /** Scala object name of each generated package: its Go name, or its path when ambiguous. */
    private val objectNames: Map[String, String] =
      val byName = roots.groupBy(p => api.packages.get(p).map(_.name).getOrElse(p.split('/').last))
      roots.map { p =>
        val name = api.packages.get(p).map(_.name).getOrElse(p.split('/').last)
        p -> (if byName(name).size == 1 then name else p.replaceAll("[^A-Za-z0-9]", "_"))
      }.toMap

    private def facadeRef(pkg: String, name: String): Option[String] =
      for
        obj <- objectNames.get(pkg)
        p <- api.packages.get(pkg)
        if p.types.contains(name) || p.aliases.contains(name)
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
      case GoType.Named(pkg, n)                              => named(pkg, n)
      case GoType.Ptr(GoType.Named(pkg, n)) if isStruct(pkg, n) => named(pkg, n)
      case GoType.Ptr(e)                                     => s"_root_.scalago.go.GoPtr[${inner(e)}]"
      case GoType.Slice(e)                                   => s"_root_.scalago.go.GoSlice[${inner(e)}]"
      case GoType.MapOf(k, v)                                => s"_root_.scalago.go.GoMap[${inner(k)}, ${inner(v)}]"
      case GoType.Chan(e, _)                                 => s"_root_.scalago.go.GoChan[${inner(e)}]"
      case GoType.EmptyInterface                             => "_root_.scala.Any"
      case GoType.Func(_)                                    => no("a func inside Go data")
      case GoType.ArrayOf(_, _)                              => no("a Go array type")
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

    // ---- Members ---------------------------------------------------------------------------------

    private def member(name: String, out: mutable.ListBuffer[String], skipped: mutable.ListBuffer[String])(body: => String): Unit =
      try out += body
      catch case e: Unrepresentable => skipped += s"$name (${e.reason})"

    private def method(name: String, sig: GoSignature, abstractMember: Boolean): String =
      if sig.generic then no("generic")
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
        path <- roots if api.packages.contains(path)
        (n, d) <- api.packages(path).types.toSeq.sortBy(_._1) if d.kind == GoTypeKind.Interface
        methods = representable(d) if methods.size == d.methods.size && methods.nonEmpty
      yield ((path, n), methods.map((m, sig) => m -> sig.copy(paramNames = Nil)))

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
      val lines = mutable.ListBuffer.empty[String]
      val skipped = mutable.ListBuffer.empty[String]
      for (n, sig) <- pkg.funcs.toSeq.sortBy(_._1) do member(n, lines, skipped)(method(n, sig, abstractMember = false))
      for (n, t) <- pkg.vars.toSeq.sortBy(_._1) do
        member(n, lines, skipped)(s"def $n: ${outer(t, isResult = true)} = _root_.scalago.go.native")
      for (n, t) <- pkg.consts.toSeq.sortBy(_._1) do
        member(n, lines, skipped)(s"def $n: ${constant(pkg, n, t)} = _root_.scalago.go.native")
      for (n, target) <- pkg.aliases.toSeq.sortBy(_._1) do
        member(n, lines, skipped) {
          target match
            case GoType.Named(p, tn) => s"type $n = ${named(p, tn)}"
            case other               => no(s"alias of $other")
        }
      val types = pkg.types.toSeq.sortBy(_._1).map((n, d) => typeDef(pkg, n, d))
      val obj = objectNames(path)
      val sb = StringBuilder()
      sb ++= "// Code generated by scala-go (FacadeGen). DO NOT EDIT.\n\n"
      sb ++= s"package $scalaPackage\n\n"
      sb ++= s"/** Go package `$path`. */\n"
      sb ++= s"@_root_.scalago.go.GoImport(${FunctionEmitter.goStringLiteral(path)})\n"
      sb ++= s"object ${ident(obj)}:\n"
      lines.foreach(l => sb ++= s"  $l\n")
      skipped.foreach(s => sb ++= s"  // not generated: $s\n")
      if types.nonEmpty then sb ++= "\n" ++= types.mkString("\n")
      sb ++= s"end ${ident(obj)}\n"
      sb.toString

    def files: Map[String, String] =
      roots.filter(api.packages.contains).map(p => s"${objectNames(p)}.scala" -> objectSource(p)).toMap
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
