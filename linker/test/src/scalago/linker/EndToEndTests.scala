package scalago.linker

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

import java.util.concurrent.Executors

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** For each directory under `tests/cases/`: compiles its `.scala` files with the plugin, runs the
 *  program on the JVM and as Go, and checks that both print exactly `expected.txt`.
 *
 *  Cases run concurrently (`SCALAGO_TEST_PARALLELISM`, by default a quarter of the processors,
 *  since each `go build` is itself parallel); each test waits for its own case.
 */
class EndToEndTests extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  private def env(name: String): String =
    sys.env.getOrElse(name, throw IllegalStateException(s"$name is not set; run the tests through mill"))

  private val casesDir = Path.of(env("SCALAGO_TEST_CASES"))
  private val pluginClasspath = env("SCALAGO_PLUGIN_CP")

  /** The scala-go user library (`scalago.go`): on the compile classpath, and linked. */
  private val userLibrary = env("SCALAGO_USER_LIBRARY")

  /** IR of javalib and of the Scala library, linked after the program's own classes. */
  private val libraryIR = env("SCALAGO_LIBRARY_IR").split(File.pathSeparator).toSeq.map(Path.of(_))

  /** Third-party Scala libraries (gears): jars to compile and run against, and their IR (with the
   *  scala-go overrides first), linked before javalib.
   */
  private val extraClasspath = env("SCALAGO_EXTRA_CP").split(File.pathSeparator).toSeq.filter(_.nonEmpty)
  private val extraIR = env("SCALAGO_EXTRA_IR").split(File.pathSeparator).toSeq.filter(_.nonEmpty).map(Path.of(_))

  /** The Scala library jars, located from classes on the test classpath. */
  private val scalaLibrary: Seq[String] =
    Seq(classOf[Option[?]], Class.forName("scala.Tuple"))
      .map(c => Path.of(c.getProtectionDomain.getCodeSource.getLocation.toURI).toString)
      .distinct

  /** `SCALAGO_TEST_ONLY=a,b` runs only the cases named `a` and `b`. */
  private val only: Option[Set[String]] = sys.env.get("SCALAGO_TEST_ONLY").map(_.split(',').map(_.trim).toSet)

  private val cases: Seq[Path] =
    Using.resource(Files.list(casesDir))(_.iterator.asScala.filter(Files.isDirectory(_)).toSeq.sortBy(_.toString))
      .filter(dir => only.forall(_.contains(dir.getFileName.toString)))

  private val parallelism =
    sys.env.get("SCALAGO_TEST_PARALLELISM").map(_.toInt).getOrElse(math.max(1, Runtime.getRuntime.availableProcessors / 4))

  private given ExecutionContext =
    ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(parallelism, r => {
      val t = Thread(r, "end-to-end-case")
      t.setDaemon(true)
      t
    }))

  /** All cases, started on first use. */
  private lazy val runs: Map[Path, Future[Unit]] = cases.map(dir => dir -> Future(runCase(dir))).toMap

  for dir <- cases do test(dir.getFileName.toString)(runs(dir))

  private def runCase(dir: Path): Unit =
    val expectedErrors = dir.resolve("link-errors.txt")
    val expected = if Files.exists(expectedErrors) then "" else Files.readString(dir.resolve("expected.txt"), UTF_8)
    // The entry point is `Main` unless the case names another one in `main.txt`.
    val mainClass =
      if Files.exists(dir.resolve("main.txt")) then Files.readString(dir.resolve("main.txt"), UTF_8).trim else "Main"
    val work = Files.createTempDirectory(s"scalago-${dir.getFileName}-")
    val classes = Files.createDirectories(work.resolve("classes"))
    val io = programIO(dir)

    compile(dir, classes)
    // A library (`library.txt`) is linked and used by the Go module in `consumer/`.
    val isLibrary = Files.exists(dir.resolve("library.txt"))
    // Programs calling Go code (`go-only.txt` says why) cannot run on the JVM.
    if !Files.exists(dir.resolve("go-only.txt")) && !isLibrary then
      val jvmClasspath = (classes.toString +: userLibrary +: (extraClasspath ++ scalaLibrary)).mkString(File.pathSeparator)
      assertNoDiff(run(Seq(javaBinary, "-cp", jvmClasspath, mainClass), work, io = io), expected, "JVM output")

    // Subdirectories with a go.mod are local Go modules the program uses.
    val goModules = Using.resource(Files.list(dir))(_.iterator.asScala.filter(d => Files.exists(d.resolve("go.mod"))).toList)
      .map(d => goModulePath(d.resolve("go.mod")) -> d.toAbsolutePath.toString)
    val config = Linker.Config(
      mainObject = mainClass,
      goModulePath = if isLibrary then LibraryModule else "scalagoapp",
      goRequires = goModules.map((m, _) => m -> "v0.0.0"),
      goReplaces = goModules,
      library = isLibrary,
      // SCALAGO_LINE_DIRECTIVES=false: Go positions in Go error messages, for debugging the linker.
      lineDirectives = !sys.env.get("SCALAGO_LINE_DIRECTIVES").contains("false"),
      // `missing-apis.txt`: linked with --missing-apis throw (the file says why).
      missingApis =
        if Files.exists(dir.resolve("missing-apis.txt")) then Analyzer.MissingApis.Throw else Analyzer.MissingApis.Error
    )
    val goDir = work.resolve("go")
    val classpath = (classes +: Path.of(userLibrary) +: extraIR) ++ libraryIR
    if Files.exists(expectedErrors) then
      // The case must fail to link, with (at least) the errors listed in `link-errors.txt`.
      val e = intercept[Linker.LinkingException](Linker.link(classpath, goDir, config))
      for line <- Files.readAllLines(expectedErrors, UTF_8).asScala if line.trim.nonEmpty do
        assert(e.errors.exists(_.contains(line.trim)), s"missing link error: $line\nactual errors:\n${e.errors.mkString("\n")}")
      deleteRecursively(work)
    else if isLibrary then
      Linker.link(classpath, goDir, config)
      assertEquals(run(Seq("go", "vet", "./..."), goDir, goEnv), "", "go vet output (library)")
      val consumer = copyDirectory(dir.resolve("consumer"), work.resolve("consumer"))
      Files.writeString(consumer.resolve("go.mod"),
        s"\nrequire $LibraryModule v0.0.0\n\nreplace $LibraryModule => ${goDir.toAbsolutePath}\n",
        java.nio.file.StandardOpenOption.APPEND)
      runGo(consumer, expected, raceCheck(dir), io)
      deleteRecursively(work)
    else
      linkAndRun(classpath, goDir, config, expected, raceCheck(dir), io)
      deleteRecursively(work) // kept on failure, for inspection

  /** Module path of libraries linked by the tests (their consumers import it). */
  private val LibraryModule = "scalagolib"

  private def copyDirectory(from: Path, to: Path): Path =
    Using.resource(Files.walk(from)) { paths =>
      for p <- paths.iterator.asScala do
        val target = to.resolve(from.relativize(p).toString)
        if Files.isDirectory(p) then Files.createDirectories(target) else Files.copy(p, target)
    }
    to

  private def linkAndRun(classpath: Seq[Path], goDir: Path, config: Linker.Config, expected: String, race: Option[RaceCheck],
      io: ProgramIO): Unit =
    Linker.link(classpath, goDir, config)
    runGo(goDir, expected, race, io)

  /** What the program reads on standard input (the case's `stdin.txt`; otherwise standard input
   *  is closed), the exit status it must end with (`exit-status.txt`; otherwise 0), and the
   *  environment variables it runs with (`NAME=value` lines of `env.txt`, such as a fixed `TZ`).
   */
  private final case class ProgramIO(stdin: Option[Path], exitStatus: Int, env: Map[String, String] = Map.empty)

  private def programIO(dir: Path): ProgramIO =
    val stdin = dir.resolve("stdin.txt")
    val status = dir.resolve("exit-status.txt")
    val env = dir.resolve("env.txt")
    ProgramIO(
      Option.when(Files.exists(stdin))(stdin),
      if Files.exists(status) then Files.readString(status, UTF_8).trim.toInt else 0,
      if !Files.exists(env) then Map.empty
      else Files.readAllLines(env, UTF_8).asScala.toSeq.collect { case s"$name=$value" => name.trim -> value.trim }.toMap)

  /** A case with a `race.txt` also runs under Go's race detector, which must report no data race,
   *  except in the generated Go functions that its `allow-race: <function>` lines name (races in
   *  third-party libraries, with the reason in the file).
   */
  private final case class RaceCheck(allowedIn: Seq[String])

  private def raceCheck(dir: Path): Option[RaceCheck] =
    val file = dir.resolve("race.txt")
    Option.when(Files.exists(file))(RaceCheck(
      Files.readAllLines(file, UTF_8).asScala.toSeq.collect { case s"allow-race: $function" => function.trim }))

  /** The tests' own Go build cache. Every change to the linker, javalib or the runtime gives each
   *  case a new generated package, tens of MB in the cache (more with the race detector), and Go
   *  only trims entries unused for five days: a day of development can add a hundred GB. So when
   *  the tests start, this cache is pruned of entries unused for a day, and then, if it is still
   *  larger than `SCALAGO_TEST_GOCACHE_GB` (10 by default), of the least recently used ones until
   *  it is half that (Go refreshes an entry's modification time when it uses it).
   */
  private val goCache: Path =
    val dir = Path.of(sys.props("user.home"), ".cache", "scalago-tests", "go-build")
    Files.createDirectories(dir)
    val maxBytes = sys.env.get("SCALAGO_TEST_GOCACHE_GB").map(_.toLong).getOrElse(10L) << 30
    val cutoff = java.time.Instant.now().minus(java.time.Duration.ofDays(1))
    val files = Using.resource(Files.walk(dir))(_.iterator.asScala.filter(Files.isRegularFile(_)).toList)
      .map(p => (p, Files.getLastModifiedTime(p).toInstant, Files.size(p)))
    val (stale, kept) = files.partition(_._2.isBefore(cutoff))
    stale.foreach(f => Files.deleteIfExists(f._1))
    var total = kept.map(_._3).sum
    if total > maxBytes then
      for (path, _, size) <- kept.sortBy(_._2) if total > maxBytes / 2 do
        Files.deleteIfExists(path)
        total -= size
    dir

  /** Each case builds in a new temporary directory. Without `-trimpath` the directory is part of
   *  every Go build cache key, so unchanged generated code would not be a cache hit.
   */
  private val goEnv = Map("GOFLAGS" -> "-trimpath", "GOCACHE" -> goCache.toString)

  private val raceReport = raw"(?s)==================\nWARNING: DATA RACE\n.*?==================\n".r

  /** Vets, builds and runs the Go main package in `goDir`, checking its output. */
  private def runGo(goDir: Path, expected: String, race: Option[RaceCheck], io: ProgramIO): Unit =
    assertEquals(run(Seq("go", "vet", "./..."), goDir, goEnv), "", "go vet output")
    assertEquals(run(Seq("go", "build", "-o", "app", "."), goDir, goEnv), "", "go build output")
    assertNoDiff(run(Seq(goDir.resolve("app").toString), goDir, io = io), expected, "Go output")
    for check <- race do
      assertEquals(run(Seq("go", "build", "-race", "-o", "app-race", "."), goDir, goEnv), "", "go build -race output")
      // Reports are checked here rather than through the exit status, so that allowed ones can be
      // told apart and dropped from the output.
      val output = run(Seq(goDir.resolve("app-race").toString), goDir, Map("GORACE" -> "exitcode=0"), io)
      for report <- raceReport.findAllIn(output) do
        assert(check.allowedIn.exists(f => report.contains(s"internal/scala.$f(")), s"data race:\n$report")
      val programOutput = raceReport.replaceAllIn(output, "").replaceAll(raw"Found \d+ data race\(s\)\n", "")
      assertNoDiff(programOutput, expected, "Go output under the race detector")

  private def goModulePath(goMod: Path): String =
    Files.readAllLines(goMod, UTF_8).asScala.collectFirst { case s"module $path" => path.trim }
      .getOrElse(throw IllegalStateException(s"no module line in $goMod"))

  /** Work directories are deleted after passing cases, unless SCALAGO_KEEP_WORK is set. */
  private def deleteRecursively(dir: Path): Unit =
    if !sys.env.contains("SCALAGO_KEEP_WORK") then Using.resource(Files.walk(dir))(_.iterator.asScala.toList.reverse.foreach(Files.delete))

  private def compile(sourceDir: Path, outDir: Path): Unit =
    val sources = Using.resource(Files.list(sourceDir))(_.iterator.asScala.map(_.toString).filter(_.endsWith(".scala")).toList)
    val args = Array(
      "-d", outDir.toString,
      "-classpath", (userLibrary +: (extraClasspath ++ scalaLibrary)).mkString(File.pathSeparator),
      s"-Xplugin:$pluginClasspath",
      "-color:never"
    ) ++ sources
    val reporter = dotty.tools.dotc.Main.process(args)
    assert(!reporter.hasErrors, s"compilation of $sourceDir failed (see output above)")

  private def javaBinary: String = Path.of(sys.props("java.home"), "bin", "java").toString

  /** Runs a process to completion, failing the test unless it exits with the expected status.
   *  Returns stdout+stderr.
   */
  private def run(command: Seq[String], cwd: Path, env: Map[String, String] = Map.empty,
      io: ProgramIO = ProgramIO(None, 0)): String =
    val builder = ProcessBuilder(command.asJava).directory(cwd.toFile).redirectErrorStream(true)
    builder.environment.putAll((io.env ++ env).asJava)
    io.stdin.foreach(f => builder.redirectInput(f.toFile))
    val process = builder.start()
    if io.stdin.isEmpty then process.getOutputStream.close()
    val output = String(process.getInputStream.readAllBytes(), UTF_8)
    val exit = process.waitFor()
    assertEquals(exit, io.exitStatus, s"`${command.mkString(" ")}` exited with $exit:\n$output")
    output
