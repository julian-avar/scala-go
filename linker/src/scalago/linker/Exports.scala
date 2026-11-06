package scalago.linker

import scala.collection.mutable

import scalago.ir.*

/** Library builds: the Go API of Scala code marked `@GoExport` (see `scalago.go.GoExport`).
 *
 *  {{{
 *  internal/scala/exports.go   X_<class>: the Go interface of each exported class (it embeds the
 *                              class's own interface); Export_<name>: each exported function and
 *                              constructor, converting its arguments and result
 *  <class>.go                  on every instantiated struct of an exported class: its exported
 *                              methods, under their Go names
 *  exports.go (module root)    the public package: `type Counter = scala.X_Counter`, and one-line
 *                              functions forwarding to the Export_ ones
 *  }}}
 *  An exported object crosses the boundary as itself: the Go interface value holds the generated
 *  struct pointer, so identity is kept and calls dispatch virtually (methods are defined on
 *  each subclass's struct, not promoted from the exported class's).
 */
object Exports:
  final case class Function(owner: ClassDef, method: MethodDef, goExport: GoExport)
  final case class ExportedClass(cls: ClassDef, goName: String, constructor: Option[(MethodDef, GoExport)],
      methods: List[(MethodDef, GoExport)])

  def functions(program: Program): List[Function] =
    for
      c <- program.classes.toList.sortBy(_.name) if c.kind == ClassKind.ModuleClass
      m <- c.methods
      e <- m.goExport
    yield Function(c, m, e)

  def classes(program: Program): List[ExportedClass] =
    for
      c <- program.classes.toList.sortBy(_.name)
      name <- c.goExport
    yield ExportedClass(c, name,
      c.methods.collectFirst { case m if m.name.isConstructor && m.goExport.isDefined => (m, m.goExport.get) },
      c.methods.collect { case m if !m.name.isConstructor && m.goExport.isDefined => (m, m.goExport.get) })

  /** Makes everything exported reachable: the roots of a library link. */
  def addRoots(program: Program, analyzer: Analyzer): Unit =
    for f <- functions(program) do
      analyzer.loadModuleRoot(f.owner.name)
      analyzer.reach(f.owner.name, f.method.name)
      analyzer.useExport(f.goExport)
    for c <- classes(program) do
      for (ctor, e) <- c.constructor do
        analyzer.instantiate(c.cls.name)
        analyzer.reach(c.cls.name, ctor.name)
        analyzer.initializeStatics(c.cls.name)
        analyzer.useExport(e)
      for (m, e) <- c.methods do
        analyzer.callVirtual(m.name)
        analyzer.useExport(e)

  private val goKeywords = Set("break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough",
    "for", "func", "go", "goto", "if", "import", "interface", "map", "package", "range", "return", "select", "struct",
    "switch", "type", "var")

  /** Readable, distinct Go parameter names for the public API. */
  def paramNames(m: MethodDef): List[String] =
    val used = mutable.HashSet.empty[String]
    m.params.zipWithIndex.map { (p, i) =>
      val base = p.name.takeWhile(_ != '$').filter(c => c.isLetterOrDigit || c == '_')
      val name = if base.isEmpty || goKeywords(base) || !base.head.isLetter || used(base) then s"p$i" else base
      used += name
      name
    }
end Exports

/** Generates the Go code of exports (library mode only). */
final class ExportEmitter(ctx: LinkContext):
  import Exports.*

  private def program = ctx.program
  private def interop = ctx.interop

  lazy val exportedClasses: List[ExportedClass] = classes(program).filter(c => ctx.declared(c.cls.name))
  private lazy val byName = exportedClasses.map(c => c.cls.name -> c).toMap

  /** Whether values of `cls` cross to Go as an exported interface type. */
  def isExportedClass(cls: ClassName): Boolean = ctx.libraryMode && byName.contains(cls)

  def ifaceName(cls: ClassName): String = s"X_${Mangling.escape(cls)}"

  /** The exported classes `cls` is an instance of (itself or ancestors), with their methods. */
  private def exportedAncestry(cls: ClassName): List[ExportedClass] =
    (cls :: program.superClassChain(cls).map(_.name)).distinct.flatMap(byName.get)

  /** Go methods of `cls`'s struct for the exported methods of the exported classes it belongs to. */
  def structMethods(cls: ClassDef, struct: String): List[String] =
    val done = mutable.HashSet.empty[String]
    // Go interfaces the class implements (facades) already define their methods.
    for f <- program.facadeAncestors(cls.name); fd <- program.facade(f).toList; m <- fd.methods; g <- m.goMember do
      done += g.goName
    for
      e <- exportedAncestry(cls.name)
      (m, exp) <- e.methods
      if done.add(exp.goName)
    yield interop.reporting(m.pos, s"exported method ${GoInterop.scalaName(e.cls.name)}.${m.name.simple}") {
      function(s"func (this *$struct) ${exp.goName}", exp, args => s"this.${Mangling.virtualMethod(m.name)}(${args.mkString(", ")})")
    }

  /** `internal/scala/exports.go`: exported interfaces and functions. */
  def internalFile: String =
    val sb = StringBuilder()
    for c <- exportedClasses do
      val methods = (c :: exportedAncestry(c.cls.name).tail).flatMap(_.methods).distinctBy(_._2.goName)
      sb ++= s"// ${ifaceName(c.cls.name)} is the Go API of the Scala class ${GoInterop.scalaName(c.cls.name)}.\n"
      sb ++= s"type ${ifaceName(c.cls.name)} interface {\n\t${Mangling.iface(c.cls.name)}\n"
      for (m, e) <- methods do
        val sig = interop.reporting(m.pos, s"exported method ${GoInterop.scalaName(c.cls.name)}.${m.name.simple}") {
          signature(m, e, Exports.paramNames(m), root = false)
        }
        sb ++= s"\t${e.goName}$sig\n"
      sb ++= "}\n\n"
    for f <- functions(program) do
      val call = (args: List[String]) =>
        s"${Mangling.impl(f.owner.name, f.method.name)}(${(s"${Mangling.module(f.owner.name)}()" :: args).mkString(", ")})"
      sb ++= interop.reporting(f.method.pos, s"exported function ${GoInterop.scalaName(f.owner.name)}.${f.method.name.simple}") {
        function(s"func Export_${f.goExport.goName}", f.goExport, call)
      }
      sb ++= "\n\n"
    for c <- exportedClasses; (ctor, e) <- c.constructor do
      val call = (args: List[String]) => s"${Mangling.newInstance(c.cls.name, ctor.name)}(${args.mkString(", ")})"
      sb ++= interop.reporting(ctor.pos, s"exported constructor of ${GoInterop.scalaName(c.cls.name)}") {
        function(s"func Export_${e.goName}", e, call, constructed = Some(c.cls.name))
      }
      sb ++= "\n\n"
    sb.toString

  /** The public root package: type aliases and forwarders. */
  def rootFile(packageName: String, scalaImport: String): String =
    val body = StringBuilder()
    for c <- exportedClasses do
      body ++= s"// ${c.goName} is the Scala class ${GoInterop.scalaName(c.cls.name)}.\n"
      body ++= s"type ${c.goName} = scala.${ifaceName(c.cls.name)}\n\n"
    def forwarder(doc: String, m: MethodDef, e: GoExport): Unit =
      val names = Exports.paramNames(m)
      val sig = interop.reporting(m.pos, s"exported ${e.goName}")(signature(m, e, names, root = true))
      val ret = if hasResults(e) || m.name.isConstructor then "return " else ""
      body ++= s"// ${e.goName} $doc.\n"
      body ++= s"func ${e.goName}$sig { ${ret}scala.Export_${e.goName}(${names.mkString(", ")}) }\n\n"
    for f <- functions(program) do
      forwarder(s"calls the Scala method ${GoInterop.scalaName(f.owner.name)}.${f.method.name.simple}", f.method, f.goExport)
    for c <- exportedClasses; (ctor, e) <- c.constructor do
      forwarder(s"constructs a Scala ${GoInterop.scalaName(c.cls.name)}", ctor, e)
    val text = body.toString
    val goPackages = "(?<![A-Za-z0-9_.])(gp_[A-Za-z0-9_]+)\\.".r.findAllMatchIn(text).map(_.group(1)).toSet.toList.sorted
      .map(alias => s"$alias \"${interop.imports(alias)}\"")
    val imports = (s"\"$scalaImport\"" :: goPackages).mkString("import (\n\t", "\n\t", "\n)\n\n")
    s"// Code generated by scala-go. DO NOT EDIT.\n\n// Package $packageName is a Scala library compiled by scala-go.\npackage $packageName\n\n$imports$text"

  // ---- Signatures and conversions -------------------------------------------------------------

  private def hasResults(e: GoExport): Boolean = e.returnsError || !isVoid(e.result)

  private def isVoid(s: ScalaType): Boolean = s match
    case ScalaType.Prim(Type.Void)                   => true
    case ScalaType.Ref("scala.runtime.BoxedUnit", _) => true
    case _                                           => false

  private def resultGoType(e: GoExport, constructed: Option[ClassName]): Option[GoType] =
    constructed match
      case Some(cls)                  => Some(GoType.Named(GoInterop.ScalaExportPkg, cls))
      case None if isVoid(e.result)   => None
      case None                       => Some(interop.goTypeOf(e.result))

  private def signature(m: MethodDef, e: GoExport, names: List[String], root: Boolean): String =
    val constructed = Option.when(m.name.isConstructor)(program.classes.find(_.methods.contains(m)).get.name)
    def spell(g: GoType) = if root then interop.rootTypeString(g, c => byName(c).goName) else interop.typeString(g)
    val params = names.zip(e.params).map((n, s) => s"$n ${spell(interop.goTypeOf(s))}")
    val results = resultGoType(e, constructed).map(spell).toList ++ Option.when(e.returnsError)("error")
    val resultDecl = results match
      case Nil       => ""
      case List(one) => s" $one"
      case several   => several.mkString(" (", ", ", ")")
    s"(${params.mkString(", ")})$resultDecl"

  /** A Go function or method converting Go arguments to Scala, calling `call`, and converting
   *  the result back. With `@throws`, a Scala exception becomes the trailing `error` result.
   */
  private def function(header: String, e: GoExport, call: List[String] => String,
      constructed: Option[ClassName] = None): String =
    val names = e.params.indices.map(i => s"p$i").toList
    val goTypes = e.params.map(interop.goTypeOf)
    val params = names.zip(goTypes).map((n, g) => s"$n ${interop.typeString(g)}").mkString(", ")
    val scalaArgs = names.zip(goTypes).zip(e.params).map { case ((n, g), s) => interop.fromGo(n, g, s) }
    val scalaCall = call(scalaArgs)
    val resultType = resultGoType(e, constructed)
    val converted = (resultType, constructed) match
      case (Some(_), Some(_)) => Some(scalaCall) // the new struct pointer implements the interface
      case (Some(g), None)    => Some(interop.toGo(scalaCall, e.result, g))
      case (None, _)          => None
    if e.returnsError then
      val resultDecl = resultType.map(g => s"r ${interop.typeString(g)}, ").getOrElse("") + "err error"
      val body = converted.fold(s"$scalaCall; return")(c => s"r = $c; return")
      s"$header($params) ($resultDecl) {\n\tdefer rt.GoCallback()\n\tdefer rt.RecoverAsError(&err)\n\t$body\n}"
    else
      val resultDecl = resultType.map(g => s" ${interop.typeString(g)}").getOrElse("")
      val body = converted.fold(scalaCall)(c => s"return $c")
      s"$header($params)$resultDecl {\n\tdefer rt.GoCallback()\n\t$body\n}"
end ExportEmitter
