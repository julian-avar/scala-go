package scalago.linker

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*
import scala.util.Using

/** For each directory under `tests/cases/`: compiles its `.scala` files with the plugin, runs the
 *  program on the JVM and as Go, and checks that both print exactly `expected.txt`.
 */
class EndToEndTests extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  private def env(name: String): String =
    sys.env.getOrElse(name, throw IllegalStateException(s"$name is not set; run the tests through mill"))

  private val casesDir = Path.of(env("SCALAGO_TEST_CASES"))
  private val pluginClasspath = env("SCALAGO_PLUGIN_CP")

  /** The scala-go user library (`scalago.go`): on the compile classpath, and linked. */
  private val userLibrary = env("SCALAGO_USER_LIBRARY")

  /** IR of javalib and of the Scala library, linked after the program's own classes. */
  private val libraryIR = env("SCALAGO_LIBRARY_IR").split(File.pathSeparator).toSeq.map(Path.of(_))

  /** The Scala library jars, located from classes on the test classpath. */
  private val scalaLibrary: Seq[String] =
    Seq(classOf[Option[?]], Class.forName("scala.Tuple"))
      .map(c => Path.of(c.getProtectionDomain.getCodeSource.getLocation.toURI).toString)
      .distinct

  private val cases: Seq[Path] =
    Using.resource(Files.list(casesDir))(_.iterator.asScala.filter(Files.isDirectory(_)).toSeq.sortBy(_.toString))

  for dir <- cases do
    test(dir.getFileName.toString) {
      val expectedErrors = dir.resolve("link-errors.txt")
      val expected = if Files.exists(expectedErrors) then "" else Files.readString(dir.resolve("expected.txt"), UTF_8)
      // The entry point is `Main` unless the case names another one in `main.txt`.
      val mainClass =
        if Files.exists(dir.resolve("main.txt")) then Files.readString(dir.resolve("main.txt"), UTF_8).trim else "Main"
      val work = Files.createTempDirectory(s"scalago-${dir.getFileName}-")
      val classes = Files.createDirectories(work.resolve("classes"))

      compile(dir, classes)
      // Programs calling Go code (`go-only.txt` says why) cannot run on the JVM.
      if !Files.exists(dir.resolve("go-only.txt")) then
        val jvmClasspath = (classes.toString +: userLibrary +: scalaLibrary).mkString(File.pathSeparator)
        assertNoDiff(run(Seq(javaBinary, "-cp", jvmClasspath, mainClass), work), expected, "JVM output")

      // Subdirectories with a go.mod are local Go modules the program uses.
      val goModules = Using.resource(Files.list(dir))(_.iterator.asScala.filter(d => Files.exists(d.resolve("go.mod"))).toList)
        .map(d => goModulePath(d.resolve("go.mod")) -> d.toAbsolutePath.toString)
      val config = Linker.Config(
        mainObject = mainClass,
        goRequires = goModules.map((m, _) => m -> "v0.0.0"),
        goReplaces = goModules
      )
      val goDir = work.resolve("go")
      val classpath = classes +: Path.of(userLibrary) +: libraryIR
      if Files.exists(expectedErrors) then
        // The case must fail to link, with (at least) the errors listed in `link-errors.txt`.
        val e = intercept[Linker.LinkingException](Linker.link(classpath, goDir, config))
        for line <- Files.readAllLines(expectedErrors, UTF_8).asScala if line.trim.nonEmpty do
          assert(e.errors.exists(_.contains(line.trim)), s"missing link error: $line\nactual errors:\n${e.errors.mkString("\n")}")
        deleteRecursively(work)
      else
        linkAndRun(classpath, goDir, config, expected)
        deleteRecursively(work) // kept on failure, for inspection
    }

  private def linkAndRun(classpath: Seq[Path], goDir: Path, config: Linker.Config, expected: String): Unit =
    Linker.link(classpath, goDir, config)
    assertEquals(run(Seq("go", "vet", "./..."), goDir), "", "go vet output")
    assertEquals(run(Seq("go", "build", "-o", "app", "."), goDir), "", "go build output")
    assertNoDiff(run(Seq(goDir.resolve("app").toString), goDir), expected, "Go output")

  private def goModulePath(goMod: Path): String =
    Files.readAllLines(goMod, UTF_8).asScala.collectFirst { case s"module $path" => path.trim }
      .getOrElse(throw IllegalStateException(s"no module line in $goMod"))

  private def deleteRecursively(dir: Path): Unit =
    Using.resource(Files.walk(dir))(_.iterator.asScala.toList.reverse.foreach(Files.delete))

  private def compile(sourceDir: Path, outDir: Path): Unit =
    val sources = Using.resource(Files.list(sourceDir))(_.iterator.asScala.map(_.toString).filter(_.endsWith(".scala")).toList)
    val args = Array(
      "-d", outDir.toString,
      "-classpath", (userLibrary +: scalaLibrary).mkString(File.pathSeparator),
      s"-Xplugin:$pluginClasspath",
      "-color:never"
    ) ++ sources
    val reporter = dotty.tools.dotc.Main.process(args)
    assert(!reporter.hasErrors, s"compilation of $sourceDir failed (see output above)")

  private def javaBinary: String = Path.of(sys.props("java.home"), "bin", "java").toString

  /** Runs a process to completion, failing the test on a non-zero exit. Returns stdout+stderr. */
  private def run(command: Seq[String], cwd: Path): String =
    val process = ProcessBuilder(command.asJava).directory(cwd.toFile).redirectErrorStream(true).start()
    process.getOutputStream.close()
    val output = String(process.getInputStream.readAllBytes(), UTF_8)
    val exit = process.waitFor()
    assertEquals(exit, 0, s"`${command.mkString(" ")}` failed:\n$output")
    output
