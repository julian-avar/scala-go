import scala.util.{Either, Failure, Left, Right, Success, Try}
import scala.util.control.Exception.*
import scala.util.control.{ControlThrowable, NoStackTrace, NonFatal}

/** Option, Either and Try with their combinators, and scala.util.control's exception handling
  * (NonFatal, Exception.catching and friends). */
object Main:
  def section(name: String): Unit = println(s"-- $name")

  def main(args: Array[String]): Unit =
    options()
    eithers()
    tries()
    exceptionHandling()

  def parse(s: String): Option[Int] = s.toIntOption

  def options(): Unit =
    section("Option")
    val some: Option[Int] = Some(4)
    val none: Option[Int] = None
    println(s"${some.map(_ * 2)} ${none.map(_ * 2)} ${some.flatMap(x => parse(s"$x$x"))} ${some.flatMap(_ => none)}")
    println(s"${some.filter(_ > 5)} ${some.filterNot(_ > 5)} ${some.exists(_ == 4)} ${none.forall(_ > 100)} ${some.contains(4)}")
    println(s"${some.getOrElse(0)} ${none.getOrElse(0)} ${none.orElse(Some(9))} ${some.orElse(Some(9))} ${none.orNull}")
    println(s"${some.fold("empty")(_.toString)} ${none.fold("empty")(_.toString)} ${some.isDefined} ${none.isEmpty}")
    println(s"${some.toList} ${none.toList} ${some.iterator.toList} ${some.toRight("err")} ${none.toLeft(1)}")
    println(s"${some.zip(Some("a"))} ${some.zip(None)} ${Some((1, "b")).unzip} ${Some(Some(3)).flatten} ${some.collect { case 4 => "four" }}")
    println(s"${Option(null)} ${Option("x")} ${Option.when(true)(1)} ${Option.unless(true)(1)} ${Option.empty[String]}")
    println(s"${some.knownSize} ${none.size} ${some.sum} ${(some ++ List(5)).toList}")
    println(for a <- parse("1"); b <- parse("2"); c <- parse("3") yield a + b + c)
    println(for a <- parse("1"); b <- parse("x") yield a + b)
    println(for a <- parse("10") if a > 5 yield a)
    var seen = 0
    some.foreach(seen += _)
    none.foreach(seen += _)
    println(seen)
    println(some match { case Some(x) if x > 3 => s"big $x"; case Some(x) => s"small $x"; case None => "none" })
    println(s"${List(Some(1), None, Some(3)).flatten} ${List("1", "x", "3").flatMap(parse)}")
    println(try none.get catch case e: NoSuchElementException => e.getMessage)
    println(s"${Some(1) == Some(1)} ${None == Option.empty[Int]} ${Some(1).hashCode == Some(1).hashCode}")
    println(List(Some(2), None, Some(1)).sorted)
    println(s"${some.tapEach(x => seen += x)} $seen ${some.whenDefined}")

  extension (o: Option[Int]) def whenDefined: String = o.map(v => s"defined $v").getOrElse("undefined")

  def divide(a: Int, b: Int): Either[String, Int] =
    if b == 0 then Left(s"$a / 0") else Right(a / b)

  def eithers(): Unit =
    section("Either")
    val r: Either[String, Int] = Right(10)
    val l: Either[String, Int] = Left("bad")
    println(s"${r.map(_ + 1)} ${l.map(_ + 1)} ${r.flatMap(divide(_, 2))} ${r.flatMap(divide(_, 0))}")
    println(s"${r.left.map(_.length)} ${l.left.map(_.length)} ${l.swap} ${r.swap}")
    println(s"${r.fold(_.length, _ * 2)} ${l.fold(_.length, _ * 2)} ${r.getOrElse(0)} ${l.getOrElse(0)}")
    println(s"${r.toOption} ${l.toOption} ${r.toSeq} ${l.left.map(RuntimeException(_)).toTry.isFailure} ${r.left.map(RuntimeException(_)).toTry}")
    println(s"${r.isRight} ${l.isLeft} ${r.contains(10)} ${r.exists(_ > 5)} ${l.forall(_ > 5)}")
    println(s"${r.filterOrElse(_ > 50, "too small")} ${r.filterOrElse(_ > 5, "too small")}")
    println(s"${l.orElse(Right(0))} ${r.orElse(Right(0))} ${l.left.getOrElse("")} ${r.left.toOption}")
    println(s"${Either.cond(true, 1, "no")} ${Either.cond(false, 1, "no")}")
    println(s"${Right(Right(1)).joinRight} ${Left(Left("x")).joinLeft} ${(Left(3): Either[Int, Int]).merge}")
    val result = for
      a <- divide(100, 5)
      b <- divide(a, 2)
      c <- divide(b, 0)
    yield c
    println(result)
    println(for a <- divide(9, 3); b <- divide(a, 1) yield a * b)
    val (errors, values) = List(divide(1, 1), divide(2, 0), divide(6, 2), divide(3, 0)).partitionMap(identity)
    println(s"$errors $values")
    println(l match { case Left(msg) => s"error: $msg"; case Right(v) => s"value: $v" })
    println(try l.toOption.get catch case e: NoSuchElementException => "no value")
    println(List(Right(1), Left("a"), Right(2)).collect { case Right(v) => v })
    println(s"${r.left.foreach(println)} ${r.foreach(v => println(s"foreach $v"))}")

  def risky(n: Int): Int =
    if n < 0 then throw IllegalArgumentException(s"negative: $n")
    else if n == 0 then throw ArithmeticException("zero")
    else 100 / n

  def tries(): Unit =
    section("Try")
    def show(t: Try[Any]): String = t match
      case Success(v) => s"Success($v)"
      case Failure(e) => s"Failure(${e.getClass.getSimpleName}: ${e.getMessage})"
    val ok = Try(risky(4))
    val bad = Try(risky(-1))
    println(s"${show(ok)} ${show(bad)} ${show(Try(risky(0)))}")
    println(s"${show(ok.map(_ * 2))} ${show(bad.map(_ * 2))} ${show(ok.flatMap(v => Try(risky(v - 25))))}")
    println(s"${ok.getOrElse(-1)} ${bad.getOrElse(-1)} ${show(bad.orElse(Try(7)))} ${ok.isSuccess} ${bad.isFailure}")
    println(show(bad.recover { case e: IllegalArgumentException => e.getMessage.length }))
    println(show(Try(risky(0)).recover { case e: IllegalArgumentException => 0 }))
    println(show(bad.recoverWith { case _ => Try(risky(1)) }))
    println(s"${show(ok.filter(_ > 100))} ${show(ok.filter(_ > 1))}")
    println(s"${ok.toOption} ${bad.toOption} ${ok.toEither} ${bad.toEither.left.map(_.getMessage)}")
    println(s"${ok.fold(_.getMessage, _.toString)} ${bad.fold(_.getMessage, _.toString)}")
    println(show(ok.transform(v => Success(v.toString * 2), e => Success(e.getMessage))))
    println(show(bad.transform(v => Success(v.toString * 2), e => Success(e.getMessage))))
    println(s"${show(bad.failed)} ${show(ok.failed)}")
    println(show(Try(Try(1)).flatten))
    println(show(ok.collect { case 25 => "quarter" }))
    println(show(for a <- Try(risky(2)); b <- Try(risky(a)) yield a + b))
    println(show(for a <- Try(risky(2)); b <- Try(risky(-a)) yield a + b))
    println(show(Try("12a".toInt)))
    println(show(Try(List(1)(5))))
    println(Try(null.asInstanceOf[String].length).failed.map(_.getClass.getName))
    println(show(Try(throw new Exception("plain"))))
    println(try bad.get catch case e: IllegalArgumentException => s"get threw ${e.getMessage}")
    println(show(Success(1).map(_ => throw IllegalStateException("in map"))))
    var ran = false
    ok.foreach(_ => ran = true)
    println(s"$ran ${Try(1) == Success(1)} ${Try.apply(5).get}")
    val results = List(1, 0, -1, 5).map(n => Try(risky(n)))
    println(s"${results.collect { case Success(v) => v }} ${results.count(_.isFailure)}")
    println(show(Try(sys.error("custom"))))
    println(show(Try(???)))

  class Recoverable(msg: String) extends RuntimeException(msg)
  class Quiet extends RuntimeException("quiet") with NoStackTrace
  class Control extends ControlThrowable

  def exceptionHandling(): Unit =
    section("scala.util.control.Exception, NonFatal")
    def classify(t: Throwable): String = t match
      case NonFatal(e) => s"non-fatal ${e.getClass.getSimpleName}"
      case e           => s"fatal ${e.getClass.getSimpleName}"
    println(classify(RuntimeException()))
    println(classify(Exception()))
    println(classify(InterruptedException()))
    println(classify(Control()))
    println(classify(LinkageError()))
    println(classify(Quiet()))
    println(s"${Quiet().getStackTrace.length} ${Quiet().getMessage}")
    println(catching(classOf[NumberFormatException]).opt("12".toInt))
    println(catching(classOf[NumberFormatException]).opt("x".toInt))
    println(catching(classOf[NumberFormatException]).either("x".toInt).left.map(_.getClass.getSimpleName))
    println(catching(classOf[NumberFormatException]).withTry("x".toInt).isFailure)
    println(failing(classOf[NumberFormatException])("x".toInt))
    println(s"${allCatch.opt(risky(0))} ${allCatch.either(risky(5))}")
    println(nonFatalCatch.opt(risky(-5)))
    println(try catching(classOf[ArithmeticException]).opt(risky(-5)) catch case e: IllegalArgumentException => s"escaped: ${e.getMessage}")
    println(handling(classOf[ArithmeticException]).by(e => s"handled ${e.getMessage}")(risky(0).toString))
    println(failAsValue(classOf[ArithmeticException])(-1)(risky(0)))
    println(ignoring(classOf[ArithmeticException])(risky(0)))
    val logged = scala.collection.mutable.ListBuffer.empty[String]
    val withCleanup = ultimately(logged += "cleanup")
    println(withCleanup(42))
    println(try withCleanup(risky(0)) catch case e: ArithmeticException => s"rethrown ${logged.size}")
    val handler = catching(classOf[ArithmeticException], classOf[IllegalArgumentException]).withApply(e => -e.getMessage.length)
    println(s"${handler(risky(0))} ${handler(risky(-1))} ${handler(risky(50))}")
    val pf: Catcher[String] = { case e: Recoverable => s"recovered ${e.getMessage}" }
    println(catching(pf)(throw Recoverable("r")))
    println(s"${catching(classOf[Exception]).andFinally(logged += "finally").apply("body")} ${logged.toList}")
    println(unwrapping(classOf[java.util.concurrent.ExecutionException]).opt(
      throw java.util.concurrent.ExecutionException(RuntimeException("inner"))).isEmpty)
