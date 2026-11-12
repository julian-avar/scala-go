package scalago.linker

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Using

import scalago.ir.*

/** Links the `.goir` files of a whole program into a Go module that `go build` can compile.
 *
 *  Output layout (the linker owns `main.go` and `internal/`; previous output there is replaced):
 *  {{{
 *  go.mod                    module <goModulePath>, with the Go modules facades need
 *  main.go                   package main: calls scala.Main()
 *  internal/scala/           package scala: the generated code
 *    <class>.go              one file per reachable class
 *    support.go              string literals, dynamic dispatchers and type tests
 *    entry.go                Main(): runs <mainObject>.main(args); runtime hooks
 *    tzdata.go               imports Go's tz database, if java.time's zones are reachable
 *  internal/rt/              the runtime support package
 *  }}}
 *  The generated code is not in `package main`, and the root package is a thin facade over it.
 *  `internal/` keeps the generated names out of reach of other modules.
 *
 *  A library link (`Config.library`) has no entry point: its roots are the definitions marked
 *  `@GoExport`, and the root package is the library's public Go API (`exports.go`, see
 *  [[Exports]]) instead of `main.go`. Other Go modules require the output module and import it.
 */
object Linker:
  final case class Config(
      /** The entry point: an object with a `main(Array[String])` method, or a class with a static
       *  one (what `@main def` generates).
       */
      mainObject: String = "Main",
      goModulePath: String = "scalagoapp",
      /** Go modules the program's facades need, as (module path, version). */
      goRequires: Seq[(String, String)] = Nil,
      /** Local directories replacing Go modules, as (module path, directory). */
      goReplaces: Seq[(String, String)] = Nil,
      /** Link a library (`@GoExport`ed definitions) instead of a program. */
      library: Boolean = false,
      /** The Go package name of a library's root package (default: the module path's last element). */
      packageName: Option[String] = None,
      /** Emit Go line directives, so that stack traces, Go panics and Go compiler and `go vet`
       *  messages give Scala positions (see [[LineDirectives]]). Without them, they give positions
       *  in the generated Go code, and stack traces have no file names or line numbers.
       */
      lineDirectives: Boolean = true,
      /** Reachable uses of APIs without an implementation: link errors (the default), or
       *  warnings and exceptions thrown when they run (see [[Analyzer.MissingApis]]).
       */
      missingApis: Analyzer.MissingApis = Analyzer.MissingApis.Error
  ):
    def rootPackageName: String =
      packageName.getOrElse {
        val last = goModulePath.split('/').last.takeWhile(_ != '.').filter(c => c.isLetterOrDigit || c == '_').toLowerCase
        if last.isEmpty || last.head.isDigit then "lib" else last
      }

  final class LinkingException(val errors: List[String])
      extends Exception(errors.mkString(s"Linking failed with ${errors.size} error(s):\n", "\n", ""))

  private val MainMethod = MethodName("main", List(Type.Array(Type.String)), Type.Void)
  private val ModuleConstructor = MethodName(MethodName.ConstructorSimpleName, Nil, Type.Void)
  private val RuntimeResourceDir = "scalago/rt"
  /** Directory and Go package name of the generated code. */
  private val ScalaPackageDir = "internal/scala"
  private val ScalaPackage = "scala"
  private val RuntimeDir = "internal/rt"

  /** Exception classes the runtime instantiates, in the order of `rt.ExceptionKind`. */
  private val runtimeExceptions = List(
    "NullPointer"            -> "java.lang.NullPointerException",
    "Arithmetic"             -> "java.lang.ArithmeticException",
    "ArrayIndexOutOfBounds"  -> "java.lang.ArrayIndexOutOfBoundsException",
    "StringIndexOutOfBounds" -> "java.lang.StringIndexOutOfBoundsException",
    "ClassCast"              -> "java.lang.ClassCastException",
    "IllegalArgument"        -> "java.lang.IllegalArgumentException",
    "NegativeArraySize"      -> "java.lang.NegativeArraySizeException",
    "NumberFormat"           -> "java.lang.NumberFormatException",
    "UnsupportedOperation"   -> "java.lang.UnsupportedOperationException",
    "ArrayStore"             -> "java.lang.ArrayStoreException",
    "RuntimeError"           -> "java.lang.RuntimeException",
    "IllegalMonitorState"    -> "java.lang.IllegalMonitorStateException",
    "Interrupted"            -> "java.lang.InterruptedException",
    "NoClassDefFound"        -> "java.lang.NoClassDefFoundError",
    "IndexOutOfBounds"       -> "java.lang.IndexOutOfBoundsException",
    "IO"                     -> "java.io.IOException",
    "NoSuchMethod"           -> "java.lang.NoSuchMethodError",
    "NoSuchField"            -> "java.lang.NoSuchFieldError"
  )
  private val MessageConstructor = MethodName(MethodName.ConstructorSimpleName, List(Type.String), Type.Void)

  /** Links the program and writes its Go module; returns the warnings. */
  def link(classpath: Seq[Path], outputDir: Path, config: Config = Config()): List[String] =
    val program = Program(IRLoader.load(classpath))
    val errors = Errors()
    val analyzer = Analyzer(program, Lambdas(program), errors, config.missingApis)

    // Roots: the entry point (or the exports), the methods the runtime calls dynamically, and the
    // exceptions it throws.
    val mainModule = config.mainObject + "$"
    val isModuleMain =
      !config.library &&
        program.vtable(mainModule).get(MainMethod).exists(o => program.findMethod(o, MainMethod).exists(!_.isAbstract))
    val entry =
      if config.library then
        Exports.addRoots(program, analyzer)
        if Exports.functions(program).isEmpty && Exports.classes(program).isEmpty then
          throw LinkingException(List("nothing to link into a library: no definition is marked @GoExport"))
        ""
      else if isModuleMain then
        analyzer.instantiate(mainModule)
        analyzer.reach(mainModule, ModuleConstructor)
        analyzer.callVirtual(MainMethod)
        s"${Mangling.staticForwarder(config.mainObject, MainMethod)}(rt.Args())"
      else if program.findMethod(config.mainObject, MainMethod).exists(_.isStatic) then
        analyzer.reach(config.mainObject, MainMethod)
        analyzer.initializeStatics(config.mainObject)
        val init = if program.clinit(config.mainObject).isDefined then s"${Mangling.clinit(config.mainObject)}()\n\t" else ""
        s"$init${Mangling.impl(config.mainObject, MainMethod)}(rt.Args())"
      else
        throw LinkingException(List(s"entry point not found: ${config.mainObject} has no main(args: Array[String]) method"))
    Analyzer.runtimeCalledMethods.foreach(analyzer.callVirtual)
    val exceptionClasses = runtimeExceptions.filter((_, cls) => program.findMethod(cls, MessageConstructor).isDefined)
    for (_, cls) <- exceptionClasses do
      analyzer.instantiate(cls)
      analyzer.reach(cls, MessageConstructor)
    analyzer.run()
    if errors.all.nonEmpty then throw LinkingException(errors.all)

    // The Go module is set up first: the APIs of the Go packages used are read in its context.
    prepareOutputDir(outputDir)
    write(outputDir.resolve("go.mod"), goMod(config))
    val goPackages = analyzer.usedFacades.toList.flatMap(program.facade).flatMap(_.goFacade).map(_.pkg).filter(_.nonEmpty).distinct.sorted
    val goApi =
      try GoApi.load(outputDir, goPackages)
      catch case e: GoApi.GoApiException => throw LinkingException(List(s"loading Go packages failed: ${e.getMessage}"))

    val ctx = LinkContext(program, analyzer, errors, goApi, libraryMode = config.library, lineDirectives = config.lineDirectives)
    val fileNames = classFileNames(program.classes.toList.map(_.name))
    val classFiles = program.classes.toList
      .sortBy(_.name)
      .map(c => fileNames(c.name) -> ClassEmitter(ctx, c).emit())
      .filter(_._2.nonEmpty)
    val forwarder = Option.when(isModuleMain)(mainForwarder(ctx, config.mainObject, mainModule))
    val entryFile = entryCode(ctx, entry, forwarder, exceptionClasses)
    val exportsFile = Option.when(config.library)(ctx.exports.internalFile)
    val rootFile = Option.when(config.library)(ctx.exports.rootFile(config.rootPackageName, s"${config.goModulePath}/$ScalaPackageDir"))
    val support = supportFile(ctx)
    if errors.all.nonEmpty then throw LinkingException(errors.all)

    val scalaDir = Files.createDirectories(outputDir.resolve(ScalaPackageDir))
    rootFile match
      case Some(root) => write(outputDir.resolve("exports.go"), root)
      case None       => write(outputDir.resolve("main.go"), mainFile(config))
    def writeGo(fileName: String, body: String): Unit =
      write(scalaDir.resolve(fileName), LineDirectives.resolveRestores(goFile(ctx, config, body), fileName))
    exportsFile.foreach(writeGo("exports.go", _))
    writeGo("entry.go", entryFile)
    writeGo("support.go", support)
    // Named time zones are Go's: from the system's tz database or, where there is none (Windows,
    // minimal containers), from the copy embedded by time/tzdata (about 450 KB).
    if analyzer.usedNatives.contains(Natives.TimeZoneLoad) then
      write(scalaDir.resolve("tzdata.go"),
        s"// Code generated by scala-go. DO NOT EDIT.\n\npackage $ScalaPackage\n\nimport _ \"time/tzdata\"\n")
    for (fileName, body) <- classFiles do writeGo(fileName, body)
    copyRuntime(outputDir.resolve(RuntimeDir))
    // go.mod has the modules the facades' packages come from; the modules those import must be
    // listed too (as indirect requirements) before `go build` accepts it.
    if goPackages.nonEmpty && config.goRequires.nonEmpty then
      try GoApi.completeGoMod(outputDir)
      catch case e: GoApi.GoApiException => throw LinkingException(List(e.getMessage))
    errors.warnings

  /** Files of the generated package that are not a class's. */
  private val ReservedFiles = Set("entry.go", "support.go", "exports.go", "tzdata.go")

  /** The Go file of each class. Go rejects a package with two files whose names differ only in
   *  case, and a class must not take the name of one of the linker's files: classes `Entry` and
   *  `entry`, or `Entry` and the linker's `entry.go`, get distinct names.
   */
  private def classFileNames(classes: List[ClassName]): Map[ClassName, String] =
    val taken = mutable.HashSet.from(ReservedFiles)
    classes.sorted.map { cls =>
      val base = Mangling.fileName(cls).stripSuffix(".go")
      // An escaped class name never contains `__`, so a numbered name is not another class's.
      val name = Iterator.from(0).map(n => if n == 0 then s"$base.go" else s"${base}__$n.go").find(f => taken.add(f.toLowerCase)).get
      cls -> name
    }.toMap

  private def goMod(config: Config): String =
    val sb = StringBuilder(s"module ${config.goModulePath}\n\ngo 1.27\n")
    if config.goRequires.nonEmpty then
      sb ++= config.goRequires.map((m, v) => s"\t$m $v\n").mkString("\nrequire (\n", "", ")\n")
    for (m, dir) <- config.goReplaces do sb ++= s"\nreplace $m => $dir\n"
    sb.toString

  private def goFile(ctx: LinkContext, config: Config, body: String): String =
    // Imports follow from the code: not from what string literals and line directives spell.
    val code = "\"(?:[^\"\\\\\n]|\\\\.)*\"|/\\*line [^\n]*?\\*/".r.replaceAllIn(body, "")
    def uses(pkg: String) = s"(?<![A-Za-z0-9_.])$pkg\\.".r.findFirstIn(code).isDefined
    val goPackages = "(?<![A-Za-z0-9_.])(gp_[A-Za-z0-9_]+)\\.".r.findAllMatchIn(code).map(_.group(1)).toSet.toList.sorted
      .map(alias => s"$alias \"${ctx.interop.imports(alias)}\"")
    val imports = List(
      Option.when(uses("rt"))(s"\"${config.goModulePath}/$RuntimeDir\""),
      Option.when(uses("math"))("\"math\"")
    ).flatten ++ goPackages
    val importBlock = imports match
      case Nil         => ""
      case List(one)   => s"import $one\n\n"
      case several     => several.mkString("import (\n\t", "\n\t", "\n)\n\n")
    s"// Code generated by scala-go. DO NOT EDIT.\n\npackage $ScalaPackage\n\n$importBlock$body"

  /** The program's `main` package: a thin layer over the generated package. */
  private def mainFile(config: Config): String =
    s"""// Code generated by scala-go. DO NOT EDIT.
       |
       |package main
       |
       |import "${config.goModulePath}/$ScalaPackageDir"
       |
       |func main() { $ScalaPackage.Main() }
       |""".stripMargin

  /** The static `main` that the JVM's launcher calls for an object with a `main` method: `Main.main`,
   *  which calls `Main$.main`. It has a frame in JVM stack traces (without a line number), so it
   *  has one here too.
   */
  private def mainForwarder(ctx: LinkContext, mainClass: ClassName, mainModule: ClassName): String =
    val pos = ctx.program.get(mainModule).fold(Position.NoPosition)(_.pos)
    val header = s"func ${Mangling.staticForwarder(mainClass, MainMethod)}(args ${ctx.goType(MainMethod.params.head)}) {"
    val call = GoLine(s"${Mangling.module(mainModule)}().${Mangling.virtualMethod(MainMethod)}(args)", pos)
    val directives = LineDirectives(ctx.lineDirectives)
    (directives.function(header, pos, List(call)) ++ List("}") ++ directives.end()).mkString("", "\n", "\n\n")

  /** `Main()`, which runs the Scala entry point, and the hooks the runtime needs. */
  private def entryCode(ctx: LinkContext, entry: String, forwarder: Option[String], exceptions: List[(String, ClassName)]): String =
    val sb = StringBuilder()
    forwarder.foreach(sb ++= _)
    if ctx.analyzer.instantiated.contains(GoInterop.GoException) then
      val struct = Mangling.struct(GoInterop.GoException)
      sb ++= "func init() {\n"
      sb ++= "\trt.MakeGoException = func(err error) any {\n"
      sb ++= s"\t\treturn ${Mangling.newInstance(GoInterop.GoException, GoInterop.GoExceptionConstructor)}(err)\n"
      sb ++= "\t}\n"
      sb ++= "\trt.GoExceptionError = func(throwable any) error {\n"
      sb ++= s"\t\tif e, ok := throwable.(*$struct); ok {\n"
      sb ++= s"\t\t\treturn rt.Cast[error](e.${Mangling.field("error")})\n"
      sb ++= "\t\t}\n\t\treturn nil\n\t}\n}\n\n"
    if exceptions.nonEmpty then
      sb ++= "func init() {\n"
      sb ++= "\trt.MakeException = func(kind rt.ExceptionKind, message *rt.String) any {\n"
      sb ++= "\t\tswitch kind {\n"
      for (kind, cls) <- exceptions do
        sb ++= s"\t\tcase rt.$kind:\n"
        sb ++= s"\t\t\treturn ${Mangling.newInstance(cls, MessageConstructor)}(message)\n"
      sb ++= "\t\t}\n\t\treturn nil\n\t}\n}\n\n"
    if entry.isEmpty then return sb.toString // a library: no entry point
    sb ++= "// Main runs the Scala program's entry point with the command-line arguments.\n"
    sb ++= "// Like the JVM, it then waits for the non-daemon threads the program started.\n"
    sb ++= "func Main() {\n"
    sb ++= "\trt.RunMain(func() {\n"
    sb ++= s"\t\t${entry.replace("\n\t", "\n\t\t")}\n"
    sb ++= "\t})\n"
    sb ++= "}\n"
    sb.toString

  /** String literals, dispatchers and type tests requested while emitting method bodies. */
  private def supportFile(ctx: LinkContext): String =
    val sb = StringBuilder()
    def line(s: String = ""): Unit = sb ++= s ++= "\n"

    if ctx.stringLiterals.nonEmpty then
      line("var (")
      for (s, v) <- ctx.stringLiterals do
        val init =
          if FunctionEmitter.hasLoneSurrogate(s) then s"rt.StrUTF16(${s.map(c => s"0x${c.toInt.toHexString}").mkString(", ")})"
          else s"rt.Str(${FunctionEmitter.goStringLiteral(s)})"
        line(s"\t$v = $init")
      line(")")
      line()

    if ctx.classLiterals.nonEmpty then
      line("var (")
      for (name, v) <- ctx.classLiterals do line(s"\t$v = rt.ClassOf(${FunctionEmitter.goStringLiteral(name)})")
      line(")")
      line()

    for cls <- ctx.ifaceCasts.toList.sorted do
      val iface = Mangling.iface(cls)
      line(s"func ${Mangling.ifaceCast(cls)}(x any) $iface {")
      line("\tif x == nil {")
      line("\t\treturn nil")
      line("\t}")
      line(s"\treturn x.($iface)")
      line("}")
    for cls <- ctx.ifaceTests.toList.sorted do
      line(s"func ${Mangling.ifaceTest(cls)}(x any) bool {")
      line(s"\t_, ok := x.(${Mangling.iface(cls)})")
      line("\treturn ok")
      line("}")
    if ctx.ifaceCasts.nonEmpty || ctx.ifaceTests.nonEmpty then line()

    for cls <- ctx.exactReceiverConversions.toList.sorted do
      val struct = Mangling.struct(cls)
      line(s"func ${Mangling.exactReceiver(cls)}(x any) *$struct {")
      line(s"\tif p, ok := x.(*$struct); ok && p != nil {")
      line("\t\treturn p")
      line("\t}")
      line("\t// Only null gets here: every instance of the class is a " + struct + ".")
      line("\trt.ThrowKind(rt.NullPointer, \"\")")
      line("\treturn nil")
      line("}")
    if ctx.exactReceiverConversions.nonEmpty then line()

    // Before the type tests below: these add to them.
    for cls <- ctx.checkedCasts.toList.sorted do
      line(s"func ${Mangling.checkedCast(cls)}(x any) any {")
      line(s"\tif x != nil && !${ctx.typeTest("x", Type.Class(cls))} {")
      line(s"\t\trt.ThrowClassCast(x, ${FunctionEmitter.goStringLiteral(cls)})")
      line("\t}")
      line("\treturn x")
      line("}")
    if ctx.checkedCasts.nonEmpty then line()

    // Class.isAssignableFrom needs the class hierarchy: the classes with Go types, and those
    // `classOf` names, with their ancestors.
    if ctx.analyzer.usedNatives.contains(Natives.ClassIsAssignableFrom) then
      val classes = (ctx.declared ++ ctx.classLiterals.keys.filter(ctx.program.contains)).toList.sorted
      line("func init() {")
      line("\trt.RegisterClassAncestors(map[string][]string{")
      for c <- classes do
        val ancestors = (ctx.program.ancestors(c) - ClassName.Object).toList.sorted
        line(s"\t\t${FunctionEmitter.goStringLiteral(c)}: {${ancestors.map(FunctionEmitter.goStringLiteral).mkString(", ")}},")
      line("\t})")
      line("}")
      line()

    for m <- ctx.dispatchers.toList.sortBy(_.descriptor) do
      val params = m.params.zipWithIndex.map((t, i) => (s"a$i", ctx.goType(t)))
      val result = ctx.goType(m.result)
      val ret = if result.isEmpty then "" else "return "
      val args = params.map(_._1)
      line(s"func ${Mangling.dispatcher(m)}(r any${params.map((n, t) => s", $n $t").mkString})${if result.isEmpty then "" else s" $result"} {")
      line("\tswitch v := r.(type) {")
      for (h, goType) <- Hijacked.instanceGoType.toList.sortBy(_._1) do
        val call = Natives.lookup(h, m) match
          case Some(Natives.Native.Instance(gen)) => Some(gen("v", args))
          case Some(Natives.Native.Static(gen))   => Some(gen(args))
          case Some(Natives.Native.NullResult)    => Some("nil")
          // An interface's default method (see Program.hijackedDefault).
          case None =>
            ctx.program.hijackedDefault(h, m).map(i => s"${Mangling.impl(i, m)}(${("v" :: args).mkString(", ")})")
              .orElse(ctx.program.hijackedCompanionMethod(h, m).map { (module, method) =>
                // A method written in Scala, in the companion object (see Program.hijackedCompanionMethod).
                s"${Mangling.module(module)}().${Mangling.virtualMethod(method)}(${("v" :: args).mkString(", ")})"
              })
        for call <- call do
          line(s"\tcase $goType:")
          line(s"\t\t$ret$call")
          if result.isEmpty then line("\t\treturn")
      line(s"\tcase interface{ ${Mangling.virtualMethod(m)}(${params.map(_._2).mkString(", ")})${if result.isEmpty then "" else s" $result"} }:")
      line(s"\t\t${ret}v.${Mangling.virtualMethod(m)}(${args.mkString(", ")})")
      if result.isEmpty then line("\t\treturn")
      line("\tcase nil:")
      line("\t\trt.ThrowKind(rt.NullPointer, \"\")")
      line("\t}")
      line(s"""\trt.ThrowKind(rt.RuntimeError, "AbstractMethodError: " + rt.GetClass(r).String() + ".${m.simple}")""")
      if result.nonEmpty then line("\tpanic(\"unreachable\")")
      line("}")
      line()

    for c <- ctx.instanceTests.toList.sorted do
      val goTypes = Hijacked.subclassesOf(c).map(Hijacked.instanceGoType) ++
        Option.when(ctx.declared(c))(Mangling.iface(c))
      line(s"func ${Mangling.instanceTest(c)}(x any) bool {")
      line("\tswitch x.(type) {")
      line(s"\tcase ${goTypes.mkString(", ")}:")
      line("\t\treturn true")
      line("\t}")
      line("\treturn false")
      line("}")
      line()
    sb.toString

  /** Removes what a previous link produced: top-level `.go` files and the generated packages
   *  (also those of the previous layout, where everything was at the top level and in `rt/`).
   */
  private def prepareOutputDir(dir: Path): Unit =
    Files.createDirectories(dir)
    Using.resource(Files.list(dir)) { entries =>
      entries.iterator.asScala.filter(_.getFileName.toString.endsWith(".go")).foreach(Files.delete)
    }
    for sub <- List("rt", ScalaPackageDir, RuntimeDir) do
      val generated = dir.resolve(sub)
      if Files.isDirectory(generated) then
        Using.resource(Files.list(generated))(_.iterator.asScala.filter(Files.isRegularFile(_)).foreach(Files.delete))
        Files.delete(generated)

  private def copyRuntime(dest: Path): Unit =
    Files.createDirectories(dest)
    val loader = getClass.getClassLoader
    def resource(name: String): Array[Byte] =
      val in = loader.getResourceAsStream(s"$RuntimeResourceDir/$name")
      if in == null then throw IllegalStateException(s"missing runtime resource $RuntimeResourceDir/$name")
      Using.resource(in)(_.readAllBytes())
    val index = String(resource("files.txt"), UTF_8)
    for name <- index.linesIterator.map(_.trim).filter(_.nonEmpty) do Files.write(dest.resolve(name), resource(name))

  private def write(path: Path, content: String): Unit = Files.writeString(path, content, UTF_8)
