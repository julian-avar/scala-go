import java.io.{ByteArrayInputStream, File, StringReader}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.io.{BufferedSource, Codec, Source, StdIn}
import scala.util.Using

/** scala.io (Source from strings, files and streams; StdIn reading stdin.txt; Console.withIn) and
  * scala.sys (props, env from env.txt, SystemProperties). */
object Main:
  def section(name: String): Unit = println(s"-- $name")

  def main(args: Array[String]): Unit =
    val dir = Files.createTempDirectory("scala-io-sys")
    try
      sources()
      files(dir)
      stdIn()
      sysProps()
      sysEnv()
    finally
      dir.toFile.listFiles.foreach(_.delete())
      Files.delete(dir)

  def sources(): Unit =
    section("Source from strings and streams")
    val text = "first line\nsecond, with comma\r\nthird\n\nlast"
    println(Source.fromString(text).getLines().toList)
    println(Source.fromString(text).getLines().map(_.length).sum)
    println(Source.fromString("abc").mkString.reverse)
    println(Source.fromString("hello").toList.distinct.mkString)
    println(Source.fromString("a b  c").mkString.split(" +").toList)
    val src = Source.fromString("x=1\ny=2\n")
    val pairs = src.getLines().map(_.split("=")).map(a => a(0) -> a(1).toInt).toMap
    src.close()
    println(pairs.toList.sorted)
    println(Source.fromIterable(List('a', 'b', '\n', 'c')).getLines().toList)
    println(Source.fromChars(Array('o', 'k')).mkString)
    val bytes = "héllo wörld\nzwei".getBytes(UTF_8)
    println(Source.fromBytes(bytes)(using Codec.UTF8).getLines().toList)
    println(Source.fromInputStream(ByteArrayInputStream(bytes), "UTF-8").mkString.length)
    println(Source.fromBytes("café".getBytes("ISO-8859-1"), "ISO-8859-1").mkString)
    val buffered = Source.fromString("one\ntwo\nthree")
    val it = buffered.getLines()
    println(s"${it.next()} ${it.hasNext} ${it.toList}")
    val chars = Source.fromString("abc\ndef")
    println(s"${chars.next()} ${chars.next()} ${chars.pos} ${chars.drop(3).mkString}")
    println(Source.fromString("1 2 3\n4 5").getLines().flatMap(_.split(" ")).map(_.toInt).sum)
    println(Source.fromString("").getLines().isEmpty)
    println(Source.fromString("no newline at end").getLines().toList)
    println(Source.fromString("trailing\n").getLines().toList)
    val reader = Source.fromInputStream(ByteArrayInputStream("read by\na reader".getBytes(UTF_8))).bufferedReader()
    println(s"${reader.readLine()} | ${reader.readLine()} | ${reader.readLine()}")
    println(Source.fromInputStream(ByteArrayInputStream("abc".getBytes(UTF_8))).reader().read().toChar)
    println(Source.fromString("tab\tseparated\tvalues").getLines().next().split('\t').toList)
    println(Codec.UTF8.name + " " + Codec.ISO8859.name + " " + Codec("US-ASCII").name + " " + Codec.default.name)
    println(Using(Source.fromString("closed by Using"))(_.mkString))

  def files(dir: Path): Unit =
    section("Source from files")
    val f = dir.resolve("data.csv")
    Files.writeString(f, "name,qty\napple,3\npear,5\nfig,12\n")
    val src = Source.fromFile(f.toFile)
    val rows = src.getLines().drop(1).map(_.split(",")).map(a => (a(0), a(1).toInt)).toList
    src.close()
    println(rows)
    println(rows.map(_._2).sum)
    println(Using.resource(Source.fromFile(f.toString))(_.getLines().size))
    println(Using.resource(Source.fromFile(f.toFile, "UTF-8"))(_.mkString.count(_ == '\n')))
    println(Using.resource(Source.fromFile(f.toUri))(_.getLines().toList.last))
    val utf = dir.resolve("utf.txt")
    Files.write(utf, "ünïcödé ✓\nzweite Zeile".getBytes(UTF_8))
    println(Using.resource(Source.fromFile(utf.toFile)(using Codec.UTF8))(_.getLines().toList))
    println(Using.resource(Source.fromFile(utf.toFile))(_.mkString.length))
    val big = dir.resolve("big.txt")
    Files.writeString(big, (1 to 20000).mkString("\n"))
    println(Using.resource(Source.fromFile(big.toFile))(_.getLines().map(_.toLong).sum))
    val written = File(dir.toFile, "written.txt")
    Using.resource(java.io.PrintWriter(written)) { w =>
      w.println("written by PrintWriter")
      w.print("second")
    }
    println(Using.resource(Source.fromFile(written))(_.getLines().toList))
    println(Using.resource(Source.fromFile(written))(s => s.getLines().map(_.toUpperCase).mkString("|")))
    println(try Source.fromFile(dir.resolve("missing.txt").toFile).mkString catch case e: java.io.FileNotFoundException => "FileNotFoundException")
    val lines = Using.resource(Source.fromFile(f.toFile))(_.getLines().toVector)
    println(lines.map(_.length))
    val withReader = Source.createBufferedSource(java.io.FileInputStream(f.toFile))
    println(withReader.getLines().take(2).mkString(";"))
    withReader.close()

  def stdIn(): Unit =
    section("StdIn, Console.withIn")
    val name = StdIn.readLine()
    println(s"line: $name")
    val n = StdIn.readInt()
    val d = StdIn.readDouble()
    val b = StdIn.readBoolean()
    println(s"int $n, double $d, boolean $b")
    val Array(x, y, z) = StdIn.readLine().split(" "): @unchecked
    println(s"split: $x $y $z")
    println(s"prompted: ${StdIn.readLine("prompt> ")}")
    val rest = Iterator.continually(StdIn.readLine()).takeWhile(_ != null).toList
    println(s"rest: $rest")
    println(s"at EOF: ${StdIn.readLine()}")
    Console.withIn(StringReader("from a reader\n42\n")) {
      println(s"withIn: ${StdIn.readLine()} ${StdIn.readInt() + 1}")
    }
    Console.withIn(ByteArrayInputStream("bytes in\n".getBytes(UTF_8))) {
      println(s"withIn stream: ${StdIn.readLine()}")
    }

  def sysProps(): Unit =
    section("sys.props")
    println(sys.props.get("scala-io-sys.missing"))
    sys.props("scala-io-sys.key") = "value"
    println(s"${sys.props("scala-io-sys.key")} ${System.getProperty("scala-io-sys.key")} ${sys.props.contains("scala-io-sys.key")}")
    sys.props += "scala-io-sys.other" -> "2"
    println(sys.props.getOrElse("scala-io-sys.other", "none"))
    sys.props -= "scala-io-sys.other"
    println(s"${sys.props.get("scala-io-sys.other")} ${sys.props.getOrElse("scala-io-sys.other", "default")}")
    println(sys.props.keys.filter(_.startsWith("scala-io-sys")).toList)
    println(s"${sys.props.remove("scala-io-sys.key")} ${sys.props.get("scala-io-sys.key")}")
    println(s"${sys.props("line.separator") == "\n"} ${sys.props("file.separator")} ${sys.props("path.separator")}")
    println(sys.props.get("user.dir").exists(_.nonEmpty))
    println(sys.SystemProperties.headless.key + " " + sys.BooleanProp.keyExists("scala-io-sys.flag").value)
    System.setProperty("scala-io-sys.flag", "")
    println(sys.BooleanProp.keyExists("scala-io-sys.flag").value)
    System.clearProperty("scala-io-sys.flag")
    println(scala.util.Properties.propOrElse("scala-io-sys.unset", "fallback") + " " + scala.util.Properties.envOrNone("SCALA_IO_SYS_NOPE"))
    println(scala.util.Properties.lineSeparator.length + " " + scala.util.Properties.isWin)

  def sysEnv(): Unit =
    section("sys.env")
    println(sys.env("SCALA_IO_SYS_GREETING"))
    println(sys.env.get("SCALA_IO_SYS_COUNT").map(_.toInt * 2))
    println(sys.env.get("SCALA_IO_SYS_UNSET"))
    println(sys.env.getOrElse("SCALA_IO_SYS_UNSET", "default"))
    println(sys.env.filter(_._1.startsWith("SCALA_IO_SYS_")).toList.sorted)
    println(s"${sys.env.contains("SCALA_IO_SYS_EMPTY")} [${sys.env("SCALA_IO_SYS_EMPTY")}]")
    println(System.getenv("SCALA_IO_SYS_GREETING") == sys.env("SCALA_IO_SYS_GREETING"))
    println(scala.util.Properties.envOrElse("SCALA_IO_SYS_COUNT", "0"))
    println(try sys.env("SCALA_IO_SYS_UNSET") catch case e: NoSuchElementException => e.getMessage)
