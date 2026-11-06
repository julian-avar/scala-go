package scalago.linker

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

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
      packageName: Option[String] = None
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
    "IndexOutOfBounds"       -> "java.lang.IndexOutOfBoundsException"
  )
  private val MessageConstructor = MethodName(MethodName.ConstructorSimpleName, List(Type.String), Type.Void)

  def link(classpath: Seq[Path], outputDir: Path, config: Config = Config()): Unit =
    val program = Program(IRLoader.load(classpath))
    val errors = Errors()
    val analyzer = Analyzer(program, Lambdas(program), errors)

    // Roots: the entry point (or the exports), the methods the runtime calls dynamically, and the
    // exceptions it throws.
    val mainModule = config.mainObject + "$"
    val entry =
      if config.library then
        Exports.addRoots(program, analyzer)
        if Exports.functions(program).isEmpty && Exports.classes(program).isEmpty then
          throw LinkingException(List("nothing to link into a library: no definition is marked @GoExport"))
        ""
      else if program.vtable(mainModule).get(MainMethod).exists(o => program.findMethod(o, MainMethod).exists(!_.isAbstract)) then
        analyzer.instantiate(mainModule)
        analyzer.reach(mainModule, ModuleConstructor)
        analyzer.callVirtual(MainMethod)
        s"${Mangling.module(mainModule)}().${Mangling.virtualMethod(MainMethod)}(rt.Args())"
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

    val ctx = LinkContext(program, analyzer, errors, goApi, libraryMode = config.library)
    val classFiles = program.classes.toList
      .sortBy(_.name)
      .map(c => Mangling.fileName(c.name) -> ClassEmitter(ctx, c).emit())
      .filter(_._2.nonEmpty)
    val entryFile = entryCode(ctx, entry, exceptionClasses)
    val exportsFile = Option.when(config.library)(ctx.exports.internalFile)
    val rootFile = Option.when(config.library)(ctx.exports.rootFile(config.rootPackageName, s"${config.goModulePath}/$ScalaPackageDir"))
    val support = supportFile(ctx)
    if errors.all.nonEmpty then throw LinkingException(errors.all)

    val scalaDir = Files.createDirectories(outputDir.resolve(ScalaPackageDir))
    rootFile match
      case Some(root) => write(outputDir.resolve("exports.go"), root)
      case None       => write(outputDir.resolve("main.go"), mainFile(config))
    exportsFile.foreach(f => write(scalaDir.resolve("exports.go"), goFile(ctx, config, f)))
    write(scalaDir.resolve("entry.go"), goFile(ctx, config, entryFile))
    write(scalaDir.resolve("support.go"), goFile(ctx, config, support))
    for (fileName, body) <- classFiles do write(scalaDir.resolve(fileName), goFile(ctx, config, body))
    copyRuntime(outputDir.resolve(RuntimeDir))

  private def goMod(config: Config): String =
    val sb = StringBuilder(s"module ${config.goModulePath}\n\ngo 1.22\n")
    if config.goRequires.nonEmpty then
      sb ++= config.goRequires.map((m, v) => s"\t$m $v\n").mkString("\nrequire (\n", "", ")\n")
    for (m, dir) <- config.goReplaces do sb ++= s"\nreplace $m => $dir\n"
    sb.toString

  private def goFile(ctx: LinkContext, config: Config, body: String): String =
    def uses(pkg: String) = s"(?<![A-Za-z0-9_.])$pkg\\.".r.findFirstIn(body).isDefined
    val goPackages = "(?<![A-Za-z0-9_.])(gp_[A-Za-z0-9_]+)\\.".r.findAllMatchIn(body).map(_.group(1)).toSet.toList.sorted
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

  /** `Main()`, which runs the Scala entry point, and the hooks the runtime needs. */
  private def entryCode(ctx: LinkContext, entry: String, exceptions: List[(String, ClassName)]): String =
    val sb = StringBuilder()
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

    for m <- ctx.dispatchers.toList.sortBy(_.descriptor) do
      val params = m.params.zipWithIndex.map((t, i) => (s"a$i", ctx.goType(t)))
      val result = ctx.goType(m.result)
      val ret = if result.isEmpty then "" else "return "
      val args = params.map(_._1)
      line(s"func ${Mangling.dispatcher(m)}(r any${params.map((n, t) => s", $n $t").mkString})${if result.isEmpty then "" else s" $result"} {")
      line("\tswitch v := r.(type) {")
      for (h, goType) <- Hijacked.instanceGoType.toList.sortBy(_._1); native <- Natives.lookup(h, m) do
        val call = native match
          case Natives.Native.Instance(gen) => gen("v", args)
          case Natives.Native.Static(gen)   => gen(args)
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
