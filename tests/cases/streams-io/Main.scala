import java.io.{BufferedReader, StringReader, UncheckedIOException}
import java.nio.file.{Files, NoSuchFileException, NotDirectoryException, Path}
import java.util as ju
import java.util.stream.*

/** Streams of the rest of the JDK: String.chars/codePoints, BufferedReader.lines, Files.lines,
  * list, walk and find, Random.ints/longs/doubles and Pattern.splitAsStream.
  */
object Main:
  def section(name: String): Unit = println(s"== $name")

  def show(values: Any*): Unit = println(values.mkString(" "))

  def attempt(label: String)(body: => Any): Unit =
    try println(s"$label: ${body}")
    catch case e: Exception => println(s"$label: threw ${e.getClass.getName}")

  def main(args: Array[String]): Unit =
    strings()
    readers()
    files()
    randoms()
    patterns()

  def strings(): Unit =
    section("strings")
    val s = "héllo, wörld"
    println(s.chars().count())
    println(s.chars().filter(Character.isLetter).mapToObj(c => c.toChar.toString).collect(Collectors.joining()))
    println(s.chars().map(Character.toUpperCase).boxed().toList)
    // A surrogate pair and a lone surrogate, in a literal.
    val emoji = "a\uD83D\uDE00b\uD800c"
    show(emoji.chars().count(), emoji.codePoints().count())
    println(emoji.codePoints().mapToObj(Integer.toHexString).toList)
    val cs: CharSequence = "xyz"
    println(cs.chars().sum())
    val sb = new java.lang.StringBuilder("abc")
    val chars = sb.chars()
    sb.append("de") // late-binding: the length is read when the traversal starts
    println(chars.mapToObj(c => c.toChar.toString).toList)
    println(sb.codePoints().max())
    println("".chars().count())
    println("banana".chars().distinct().count())
    println("mississippi".chars().boxed().collect(Collectors.groupingBy((c: Integer) => c.intValue.toChar, Collectors.counting())))

  def readers(): Unit =
    section("readers")
    val reader = new BufferedReader(new StringReader("one\ntwo\r\nthree\r\rfive"))
    println(reader.lines().toList)
    println(new BufferedReader(new StringReader("")).lines().count())
    val partial = new BufferedReader(new StringReader("a\nb\nc\nd"))
    println(partial.lines().limit(2).toList)
    println(partial.readLine())
    val closed = new BufferedReader(new StringReader("x"))
    closed.close()
    attempt("closed reader")(closed.lines().count())

  def files(): Unit =
    section("files")
    val dir = Files.createTempDirectory("streams-io")
    try
      val text = dir.resolve("text.txt")
      Files.writeString(text, "alpha\nbeta\n\ngamma\n")
      println(Files.lines(text).toList)
      val lines = Files.lines(text)
      println(lines.filter(!_.isEmpty).map(_.toUpperCase).collect(Collectors.joining("|")))
      lines.close()
      scala.util.Using.resource(Files.lines(text, java.nio.charset.StandardCharsets.ISO_8859_1))(s => println(s.count()))
      Files.write(dir.resolve("bad.txt"), Array[Byte](0x61, 0xff.toByte, 0x62))
      attempt("malformed")(Files.lines(dir.resolve("bad.txt")).count())
      attempt("missing")(Files.lines(dir.resolve("nope.txt")))
      Files.createDirectories(dir.resolve("sub/deeper"))
      Files.writeString(dir.resolve("sub/one.txt"), "1")
      Files.writeString(dir.resolve("sub/deeper/two.md"), "22")
      Files.writeString(dir.resolve("sub/deeper/three.txt"), "333")
      def rel(p: Path): String = dir.relativize(p).toString
      println(Files.list(dir).map(rel).sorted().toList)
      attempt("list file")(Files.list(text))
      attempt("list missing")(Files.list(dir.resolve("nope")))
      println(Files.walk(dir).map(rel).sorted().toList)
      println(Files.walk(dir, 1).map(rel).sorted().toList)
      println(Files.walk(dir, 0).map(rel).toList)
      println(Files.walk(text).count())
      attempt("walk missing")(Files.walk(dir.resolve("nope")))
      attempt("walk negative depth")(Files.walk(dir, -1))
      // Directories come before their contents.
      val order = Files.walk(dir).map(rel).toList
      println(order.indexOf("sub") < order.indexOf("sub/deeper") && order.indexOf("sub/deeper") < order.indexOf("sub/deeper/two.md"))
      println(Files.find(dir, 10, (p, attrs) => attrs.isRegularFile && p.toString.endsWith(".txt")).map(rel).sorted().toList)
      println(Files.find(dir, 10, (_, attrs) => attrs.isDirectory).count())
      println(Files.walk(dir).filter(Files.isRegularFile(_)).mapToLong(Files.size).sum())
    finally
      Files.walk(dir).sorted(ju.Comparator.reverseOrder()).forEach(Files.delete)
    println(Files.exists(dir))

  def randoms(): Unit =
    section("randoms")
    println(new ju.Random(42).ints(5).boxed().toList)
    println(new ju.Random(42).ints(6, 10, 20).boxed().toList)
    println(new ju.Random(42).ints(-5, 5).limit(6).boxed().toList)
    println(new ju.Random(7).ints(4, Int.MinValue, Int.MaxValue).boxed().toList)
    println(new ju.Random(42).longs(3).boxed().toList)
    println(new ju.Random(42).longs(4, 0L, 1000L).boxed().toList)
    println(new ju.Random(42).doubles(3).boxed().toList)
    println(new ju.Random(42).doubles(3, 1.0, 2.0).boxed().toList)
    println(new ju.Random(1).ints().limit(3).boxed().toList)
    val r = new ju.Random(3)
    show(r.ints(2).sum(), r.nextInt())
    println(new ju.Random(9).ints(1000, 0, 6).boxed().collect(Collectors.groupingBy((i: Integer) => i, Collectors.counting())))
    println(new ju.SplittableRandom(5).ints(4, 0, 100).boxed().toList)
    println(new ju.SplittableRandom(5).longs(2).boxed().toList)
    println(new ju.SplittableRandom(5).doubles(2).boxed().toList)
    show(new ju.Random(4).ints(3, 0, 10).boxed().toList, {
      val q = new ju.Random(4)
      List(q.nextInt(0, 10), q.nextInt(0, 10), q.nextInt(0, 10))
    })
    attempt("negative size")(new ju.Random().ints(-1))
    attempt("bad range")(new ju.Random().ints(5, 5))
    attempt("bad double range")(new ju.Random().doubles(1.0, Double.PositiveInfinity))
    println(new ju.Random(42).ints(10).count())

  def patterns(): Unit =
    section("patterns")
    val comma = ju.regex.Pattern.compile("\\s*,\\s*")
    println(comma.splitAsStream("a , b,c ,, d,,").toList)
    println(comma.splitAsStream("").toList)
    println(ju.regex.Pattern.compile("").splitAsStream("abc").toList)
    println(ju.regex.Pattern.compile("x").splitAsStream("abc").count())
