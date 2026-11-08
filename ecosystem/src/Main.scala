import cats.syntax.all.*
import cats.data.{NonEmptyList, Validated}
import upickle.default.{ReadWriter, read, write}

case class Point(x: Int, y: Int, label: String) derives ReadWriter

object Main:
  def parseExpr(s: String): Either[String, Int] =
    import fastparse.*, NoWhitespace.*
    def number[$: P]: P[Int] = P(CharIn("0-9").rep(1).!.map(_.toInt))
    def factor[$: P]: P[Int] = P(number | "(" ~ expr ~ ")")
    def term[$: P]: P[Int] = P(factor ~ (CharIn("*/").! ~ factor).rep).map { case (a, ops) =>
      ops.foldLeft(a) { case (acc, ("*", b)) => acc * b; case (acc, (_, b)) => acc / b }
    }
    def expr[$: P]: P[Int] = P(term ~ (CharIn("+\\-").! ~ term).rep).map { case (a, ops) =>
      ops.foldLeft(a) { case (acc, ("+", b)) => acc + b; case (acc, (_, b)) => acc - b }
    }
    def all[$: P]: P[Int] = P(expr ~ End)
    parse(s, all(using _)) match
      case Parsed.Success(v, _) => Right(v)
      case f: Parsed.Failure    => Left(f.msg)

  def main(args: Array[String]): Unit =
    // cats
    println(List(1, 2, 3).foldMap(_.toString))
    println((Option(1), Option(2)).mapN(_ + _))
    println(NonEmptyList.of(3, 1, 2).sorted.toList)
    val v = (Validated.validNel[String, Int](1), Validated.invalidNel[String, Int]("bad")).mapN(_ + _)
    println(v)
    println(List(Right(1), Left("e"), Right(3)).sequence)
    // upickle
    val json = write(Point(1, 2, "a\"b"))
    println(json)
    println(read[Point](json))
    println(write(Map("k" -> List(1, 2)), indent = 2))
    // fastparse
    println(parseExpr("2*(3+4)-5"))
    println(parseExpr("2*(3+").isLeft)
    // pprint
    println(pprint.apply(Point(3, 4, "z")).plainText)
    // mainargs
    println(Cli.parse(Seq("--name", "Ada", "--times", "2")))
    // os-lib
    osLib()

  /** Files and subprocesses through os-lib, in a fresh temporary directory. */
  def osLib(): Unit =
    val dir = os.temp.dir(prefix = "scalago-ecosystem")
    try
      os.write(dir / "a.txt", "hello\nworld\n")
      os.write.append(dir / "a.txt", "more\n")
      println(os.read.lines(dir / "a.txt"))
      os.write(dir / "x" / "y" / "f.txt", "f", createFolders = true)
      os.copy(dir / "x", dir / "x2")
      os.move(dir / "x2", dir / "x3")
      println(os.walk(dir).map(_.relativeTo(dir).toString).sorted)
      println((os.size(dir / "a.txt"), os.isDir(dir / "x"), os.exists(dir / "x2"), os.stat(dir / "a.txt").fileType))
      os.remove.all(dir / "x3")
      println(os.list(dir).map(_.last))
      println((os.rel / "x" / os.up / "y", os.root / "a" / "b.txt" relativeTo os.root / "c", (os.root / "a.tar.gz").ext))
      println(os.proc("sh", "-c", "cat; echo $FOO").call(stdin = "piped in ", env = Map("FOO" -> "bar")).out.text())
      println(os.proc("sh", "-c", "exit 3").call(check = false).exitCode)
      val cat = os.proc("cat").spawn()
      cat.stdin.writeLine("spawned")
      cat.stdin.flush()
      println(cat.stdout.readLine())
      cat.stdin.close()
      println(cat.waitFor(10000))
    finally os.remove.all(dir)

object Cli:
  @mainargs.main
  def greet(name: String, times: Int = 1): String = List.fill(times)(s"hi $name").mkString(", ")
  def parse(args: Seq[String]): String =
    mainargs.ParserForMethods(this).runEither(args) match
      case Right(result) => result.toString
      case Left(error)   => error
