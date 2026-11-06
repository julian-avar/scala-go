package scalago.linker

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*
import scala.util.Using

import scalago.ir.*

/** Links the `.goir` files of a whole program into a Go module that `go build` can compile.
 *
 *  Output layout (the output directory is owned by the linker; previous output is replaced):
 *  {{{
 *  go.mod          module <goModulePath>
 *  main.go         entry point, calls <mainObject>.main(args)
 *  <class>.go      one file per reachable class, all in package main
 *  support.go      string literals, dynamic dispatchers and type tests
 *  rt/             the runtime support package
 *  }}}
 */
object Linker:
  final case class Config(
      /** The entry point: an object with a `main(Array[String])` method, or a class with a static
       *  one (what `@main def` generates).
       */
      mainObject: String = "Main",
      goModulePath: String = "scalagoapp"
  )

  final class LinkingException(val errors: List[String])
      extends Exception(errors.mkString(s"Linking failed with ${errors.size} error(s):\n", "\n", ""))

  private val MainMethod = MethodName("main", List(Type.Array(Type.String)), Type.Void)
  private val ModuleConstructor = MethodName(MethodName.ConstructorSimpleName, Nil, Type.Void)
  private val RuntimeResourceDir = "scalago/rt"

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
    "RuntimeError"           -> "java.lang.RuntimeException"
  )
  private val MessageConstructor = MethodName(MethodName.ConstructorSimpleName, List(Type.String), Type.Void)

  def link(classpath: Seq[Path], outputDir: Path, config: Config = Config()): Unit =
    val program = Program(IRLoader.load(classpath))
    val errors = Errors()
    val analyzer = Analyzer(program, Lambdas(program), errors)

    // Roots: the entry point, the methods the runtime calls dynamically, and the exceptions it throws.
    val mainModule = config.mainObject + "$"
    val entry =
      if program.vtable(mainModule).get(MainMethod).exists(o => program.findMethod(o, MainMethod).exists(!_.isAbstract)) then
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

    val ctx = LinkContext(program, analyzer, errors)
    val classFiles = program.classes.toList
      .sortBy(_.name)
      .map(c => Mangling.fileName(c.name) -> ClassEmitter(ctx, c).emit())
      .filter(_._2.nonEmpty)
    val main = mainFile(entry, exceptionClasses)
    val support = supportFile(ctx)
    if errors.all.nonEmpty then throw LinkingException(errors.all)

    prepareOutputDir(outputDir)
    write(outputDir.resolve("go.mod"), s"module ${config.goModulePath}\n\ngo 1.22\n")
    write(outputDir.resolve("main.go"), goFile(config, main))
    write(outputDir.resolve("support.go"), goFile(config, support))
    for (fileName, body) <- classFiles do write(outputDir.resolve(fileName), goFile(config, body))
    copyRuntime(outputDir.resolve("rt"))

  private def goFile(config: Config, body: String): String =
    def uses(pkg: String) = s"(?<![A-Za-z0-9_.])$pkg\\.".r.findFirstIn(body).isDefined
    val imports = List(
      Option.when(uses("rt"))(s"\"${config.goModulePath}/rt\""),
      Option.when(uses("math"))("\"math\"")
    ).flatten
    val importBlock = imports match
      case Nil         => ""
      case List(one)   => s"import $one\n\n"
      case several     => several.mkString("import (\n\t", "\n\t", "\n)\n\n")
    s"// Code generated by scala-go. DO NOT EDIT.\n\npackage main\n\n$importBlock$body"

  private def mainFile(entry: String, exceptions: List[(String, ClassName)]): String =
    val sb = StringBuilder()
    if exceptions.nonEmpty then
      sb ++= "func init() {\n"
      sb ++= "\trt.MakeException = func(kind rt.ExceptionKind, message *rt.String) any {\n"
      sb ++= "\t\tswitch kind {\n"
      for (kind, cls) <- exceptions do
        sb ++= s"\t\tcase rt.$kind:\n"
        sb ++= s"\t\t\treturn ${Mangling.newInstance(cls, MessageConstructor)}(message)\n"
      sb ++= "\t\t}\n\t\treturn nil\n\t}\n}\n\n"
    sb ++= "func main() {\n"
    sb ++= "\tdefer rt.HandleUncaught()\n"
    sb ++= s"\t$entry\n"
    sb ++= "\trt.FlushStdout()\n"
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

  /** Removes what a previous link produced: top-level `.go` files and the runtime directory. */
  private def prepareOutputDir(dir: Path): Unit =
    Files.createDirectories(dir)
    Using.resource(Files.list(dir)) { entries =>
      entries.iterator.asScala.filter(_.getFileName.toString.endsWith(".go")).foreach(Files.delete)
    }
    val rt = dir.resolve("rt")
    if Files.isDirectory(rt) then
      Using.resource(Files.list(rt))(_.iterator.asScala.foreach(Files.delete))
      Files.delete(rt)

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
