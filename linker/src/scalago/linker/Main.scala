package scalago.linker

import java.io.File
import java.nio.file.Path

/** Command-line entry point:
 *  {{{
 *  scalago-link --classpath <cp> --out <dir> [--main <Object>] [--go-module <path>]
 *               [--go-require <module>@<version>]... [--go-replace <module>=<dir>]...
 *               [--library true] [--package <name>] [--line-directives false]
 *  }}}
 */
object Main:
  private val usage =
    "usage: scalago-link --classpath <cp> --out <dir> [--main <Object>] [--go-module <path>]\n" +
      "                    [--go-require <module>@<version>]... [--go-replace <module>=<dir>]...\n" +
      "                    [--library true] [--package <name>] [--line-directives false]\n" +
      "                    [--missing-apis error|throw]"

  def main(args: Array[String]): Unit =
    val pairs = args.grouped(2).collect { case Array(k, v) => k -> v }.toList
    def all(key: String): List[String] = pairs.collect { case (`key`, v) => v }
    def one(key: String): Option[String] = all(key).lastOption
    if args.length % 2 != 0 || one("--classpath").isEmpty || one("--out").isEmpty then
      System.err.println(usage)
      sys.exit(2)

    def split(s: String, sep: Char): (String, String) =
      val i = s.indexOf(sep)
      if i <= 0 then
        System.err.println(s"bad value `$s`\n$usage")
        sys.exit(2)
      (s.substring(0, i), s.substring(i + 1))

    val classpath = one("--classpath").get.split(File.pathSeparator).toSeq.filter(_.nonEmpty).map(Path.of(_))
    val defaults = Linker.Config()
    val config = Linker.Config(
      mainObject = one("--main").getOrElse(defaults.mainObject),
      goModulePath = one("--go-module").getOrElse(defaults.goModulePath),
      goRequires = all("--go-require").map(split(_, '@')),
      goReplaces = all("--go-replace").map(split(_, '=')),
      library = one("--library").contains("true"),
      packageName = one("--package"),
      lineDirectives = !one("--line-directives").contains("false"),
      missingApis = one("--missing-apis") match
        case None | Some("error") => Analyzer.MissingApis.Error
        case Some("throw")        => Analyzer.MissingApis.Throw
        case Some(other) =>
          System.err.println(s"bad value `$other` for --missing-apis\n$usage")
          sys.exit(2)
    )
    try Linker.link(classpath, Path.of(one("--out").get), config).foreach(System.err.println)
    catch
      case e: Linker.LinkingException =>
        e.errors.foreach(System.err.println)
        sys.exit(1)
