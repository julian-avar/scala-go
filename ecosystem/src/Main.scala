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

object Cli:
  @mainargs.main
  def greet(name: String, times: Int = 1): String = List.fill(times)(s"hi $name").mkString(", ")
  def parse(args: Seq[String]): String =
    mainargs.ParserForMethods(this).runEither(args) match
      case Right(result) => result.toString
      case Left(error)   => error
