package scalago.mill

import mill.*
import mill.scalalib.*

/** A Scala 3 module compiled to a native executable through Go.
 *
 *  {{{
 *  //| mvnDeps: ["io.github.scalago:mill-scalago_mill1_3:<version>"]
 *  import scalago.mill.ScalaGoModule
 *
 *  object app extends ScalaGoModule
 *  }}}
 *
 *  `mill app.goRun` builds and runs the program; `mill app.goLink` only produces the Go module and
 *  `mill app.goBuild` the binary. The module still compiles (and runs, tests, ...) on the JVM as
 *  usual: the plugin only adds `.goir` files next to the `.class` files.
 *
 *  This file is compiled both into this repository's own build (see `mill-build/`) and into the
 *  published plugin.
 */
trait ScalaGoModule extends ScalaModule {

  /** User code must be compiled with exactly the Scala version the compiler plugin was built for. */
  def scalaVersion: T[String] = Task { ScalaGoModule.ScalaVersion }

  def scalaGoVersion: T[String] = Task { ScalaGoModule.Version }

  /** The compiler plugin jar, passed with `-Xplugin`. */
  def scalaGoPlugin: T[PathRef] = Task {
    defaultResolver()
      .classpath(Seq(mvn"${ScalaGoModule.Organization}:scalago-plugin_${scalaVersion()}:${scalaGoVersion()}"))
      .find(_.path.last.startsWith("scalago-plugin"))
      .get
  }

  /** Classpath for running the linker (in its own JVM, since it targets a newer Scala than Mill). */
  def scalaGoLinkerClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(Seq(mvn"${ScalaGoModule.Organization}::scalago-linker:${scalaGoVersion()}"))
  }

  /** IR of the Java standard library (javalib) and of the Scala standard library. */
  def scalaGoLibraryIR: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      Seq(
        mvn"${ScalaGoModule.Organization}::scalago-javalib:${scalaGoVersion()}",
        mvn"${ScalaGoModule.Organization}:scalago-scalalib-ir_${scalaVersion()}:${scalaGoVersion()}"
      ).map(_.exclude("*" -> "*"))
    )
  }

  /** The scala-go user library (`scalago.go.GoString`, ...). */
  def scalaGoLibraryDeps: T[Seq[Dep]] = Task {
    Seq(mvn"${ScalaGoModule.Organization}::scalago-library:${scalaGoVersion()}")
  }

  override def mvnDeps: T[Seq[Dep]] = Task { super.mvnDeps() ++ scalaGoLibraryDeps() }

  override def scalacOptions: T[Seq[String]] = Task {
    val plugin = scalaGoPlugin()
    // The second option makes recompilation depend on the plugin's contents.
    super.scalacOptions() ++ Seq(s"-Xplugin:${plugin.path}", s"-P:scala-go:plugin-signature:${plugin.sig}")
  }

  /** The object whose `main` method starts the program. */
  def scalaGoMainObject: T[String] = Task { finalMainClass() }

  /** Module path of the generated Go module. */
  def goModulePath: T[String] = Task { "scalagoapp" }

  /** Go modules that the program's Go facades import, as `module@version`
   *  (e.g. `github.com/google/uuid@v1.6.0`). Standard library packages need nothing here.
   */
  def goRequires: T[Seq[String]] = Task { Seq.empty[String] }

  /** Whether this module links into a Go library (see `ScalaGoLibraryModule`). */
  def goLibrary: T[Boolean] = Task { false }

  /** Package name of a library's root Go package ("" for the module path's last element). */
  def goPackageName: T[String] = Task { "" }

  /** Directories of local Go modules (each with its own `go.mod`) that the program's Go facades
   *  import, e.g. `Task.Sources(moduleDir / "go" / "mylib")`.
   */
  def goLocalModules: T[Seq[PathRef]] = Task { Seq.empty[PathRef] }

  /** Links the program's IR (and its dependencies') into a Go module. */
  def goLink: T[PathRef] = Task {
    val out = Task.dest / "go"
    val sep = _root_.java.io.File.pathSeparator
    val classpath = (runClasspath() ++ scalaGoLibraryIR()).map(_.path).filter(os.exists(_))
    val javaExe = os.Path(sys.props("java.home")) / "bin" / "java"
    val localModules = goLocalModules().map { dir =>
      val goMod = dir.path / "go.mod"
      val modulePath = os.read.lines(goMod).collectFirst { case l if l.startsWith("module ") => l.stripPrefix("module ").trim }
        .getOrElse(throw new Exception(s"scala-go: no module line in $goMod"))
      Seq("--go-require", s"$modulePath@v0.0.0", "--go-replace", s"$modulePath=${dir.path}")
    }
    val result = os.proc(
      javaExe, "-cp", scalaGoLinkerClasspath().map(_.path).mkString(sep),
      "scalago.linker.Main",
      "--classpath", classpath.mkString(sep),
      "--out", out,
      if (goLibrary()) Seq("--library", "true") else Seq("--main", scalaGoMainObject()),
      if (goPackageName().nonEmpty) Seq("--package", goPackageName()) else Seq.empty[String],
      "--go-module", goModulePath(),
      goRequires().flatMap(r => Seq("--go-require", r)),
      localModules.flatten
    ).call(stdout = os.Inherit, stderr = os.Inherit, check = false)
    if (result.exitCode != 0) throw new Exception("scala-go: linking failed (see errors above)")
    PathRef(out)
  }

  /** Compiles the linked Go module into an executable (requires `go` on the PATH). */
  def goBuild: T[PathRef] = Task {
    val binary = Task.dest / "app"
    os.proc("go", "build", "-o", binary, ".").call(cwd = goLink().path, stdout = os.Inherit, stderr = os.Inherit)
    PathRef(binary)
  }

  /** Builds and runs the executable with the given arguments. */
  def goRun(args: String*): Command[Unit] = Task.Command {
    val result = os.proc(goBuild().path, args).call(
      stdin = os.Inherit,
      stdout = os.Inherit,
      stderr = os.Inherit,
      check = false
    )
    if (result.exitCode != 0) throw new Exception(s"program exited with status ${result.exitCode}")
  }
}

/** A Scala library compiled to a Go module that other Go code requires and imports: its Go API
 *  is the definitions marked `@scalago.go.GoExport`. `goLink` produces the module; set
 *  `goModulePath` to the path Go code imports it by.
 */
trait ScalaGoLibraryModule extends ScalaGoModule {
  override def goLibrary: T[Boolean] = Task { true }
  override def scalaGoMainObject: T[String] = Task { "" }

  /** Type-checks the linked library with `go vet` (a library has no executable). */
  override def goBuild: T[PathRef] = Task {
    os.proc("go", "vet", "./...").call(cwd = goLink().path, stdout = os.Inherit, stderr = os.Inherit)
    goLink()
  }
}

object ScalaGoModule {
  val Organization = "io.github.scalago"
  val Version = "0.1.0-SNAPSHOT"
  val ScalaVersion = "3.9.0"
}
