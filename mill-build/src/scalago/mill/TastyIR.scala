package scalago.mill

/** Compiles Scala 3 libraries to scala-go IR from the TASTy files their jars ship: published Scala
 *  libraries need no scala-go specific artifact. Untranslatable code becomes `Unsupported` IR
 *  (lenient mode), which only fails linking if the program reaches it.
 */
object TastyIR {

  /** Whether `jar` contains TASTy but no IR (a Scala 3 library not compiled with the plugin). */
  def needsIR(jar: os.Path): Boolean = {
    val zip = new java.util.zip.ZipFile(jar.toIO)
    try {
      var tasty = false
      var ir = false
      val entries = zip.entries()
      while (entries.hasMoreElements) {
        val name = entries.nextElement().getName
        if (name.endsWith(".tasty")) tasty = true
        if (name.endsWith(".goir")) ir = true
      }
      tasty && !ir
    } finally zip.close()
  }

  /** Compiles the TASTy files of `jar` (except those `skip` rejects) into `.goir` files in `out`.
   *
   *  @param classpath the jar's dependencies
   *  @param work      a scratch directory
   */
  def compile(
      jar: os.Path,
      classpath: Seq[os.Path],
      out: os.Path,
      work: os.Path,
      compilerClasspath: Seq[os.Path],
      plugin: os.Path,
      skip: os.Path => Boolean = _ => false
  ): Unit = {
    val tastyDir = work / "tasty"
    os.remove.all(tastyDir)
    os.remove.all(out)
    os.makeDir.all(tastyDir)
    os.makeDir.all(out)
    os.unzip(jar, tastyDir, includePatterns = Seq(".*\\.tasty".r))
    val files = os.walk(tastyDir).filter(_.ext == "tasty").filterNot(skip).map(_.toString)
    if (files.nonEmpty) {
      val argsFile = work / "args.txt"
      os.write.over(argsFile, files.mkString("\n"))
      val sep = java.io.File.pathSeparator
      os.proc(
        "java", "-Xss8m", "-cp", compilerClasspath.mkString(sep),
        "dotty.tools.dotc.Main",
        "-from-tasty", "-experimental", "-nowarn", "-Ystop-after:genGoIR",
        "-classpath", (jar +: classpath).mkString(sep),
        "-d", out.toString,
        s"-Xplugin:$plugin", "-P:scala-go:lenient",
        s"@$argsFile"
      ).call(stdout = os.Inherit)
    }
  }
}
