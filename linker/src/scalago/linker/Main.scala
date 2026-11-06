package scalago.linker

import java.io.File
import java.nio.file.Path

/** Command-line entry point:
 *  {{{
 *  scalago-link --classpath <cp> --out <dir> [--main <Object>] [--go-module <path>]
 *  }}}
 */
object Main:
  private val usage = "usage: scalago-link --classpath <cp> --out <dir> [--main <Object>] [--go-module <path>]"

  def main(args: Array[String]): Unit =
    val options = args.grouped(2).collect { case Array(k, v) => k -> v }.toMap
    if args.length % 2 != 0 || !options.contains("--classpath") || !options.contains("--out") then
      System.err.println(usage)
      sys.exit(2)

    val classpath = options("--classpath").split(File.pathSeparator).toSeq.filter(_.nonEmpty).map(Path.of(_))
    val defaults = Linker.Config()
    val config = Linker.Config(
      mainObject = options.getOrElse("--main", defaults.mainObject),
      goModulePath = options.getOrElse("--go-module", defaults.goModulePath)
    )
    try Linker.link(classpath, Path.of(options("--out")), config)
    catch
      case e: Linker.LinkingException =>
        e.errors.foreach(System.err.println)
        sys.exit(1)
