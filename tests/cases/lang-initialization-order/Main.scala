import scala.util.boundary, boundary.break

// Initialization and evaluation order as specified in docs/_docs/spec/language.md: objects are
// constructed on first use, and a cycle sees the partially constructed object; trait and class
// fields are initialized in linearization order; a failed lazy val is retried; `finally` runs
// after `return`, and an exception thrown in it replaces the outcome; operands and arguments
// are evaluated left to right.

object A:
  println("A init start")
  val x: Int = B.y + 1
  println("A init end")

object B:
  println("B init start")
  val y: Int = A.x + 1
  println("B init end")

object Failing:
  println("Failing init")
  if Main.fail then throw new IllegalStateException("boom")
  val v = 1

class Retry:
  var attempts = 0
  lazy val v: Int =
    attempts += 1
    if attempts < 2 then throw new RuntimeException(s"attempt $attempts") else attempts

trait T:
  val x: Int
  val y: Int = x + 1
  println(s"T init: x=$x y=$y")

class C extends T:
  val x = 5
  println(s"C init: x=$x y=$y")

abstract class Base:
  def name: String
  println(s"Base sees name=$name")

class Derived extends Base:
  val name = "derived"

class WithStatic
object WithStatic:
  @scala.annotation.static val s: Int = { println("static init"); 7 }

object Main:
  def fail: Boolean = true

  def returnThenFinally(): Int =
    try
      println("try")
      return 1
    finally println("finally")

  def firstAbove(limit: Int, xs: List[Int]): Int =
    boundary:
      xs.foreach(x => if x > limit then break(x))
      0

  def main(args: Array[String]): Unit =
    println(s"A.x=${A.x} B.y=${B.y}")

    // The first access throws ExceptionInInitializerError, caused by the original exception;
    // later ones NoClassDefFoundError, caused by an ExceptionInInitializerError that names it.
    try println(Failing.v)
    catch
      case e: ExceptionInInitializerError =>
        println(s"Failing, first access: ${e.getClass.getName} ${e.getMessage} ${e.getCause.getMessage} ${e.getException eq e.getCause}")
    try println(Failing.v)
    catch
      case e: NoClassDefFoundError =>
        println(s"Failing, second access: ${e.getMessage}, cause ${e.getCause.getClass.getName}: ${e.getCause.getMessage}")

    val r = Retry()
    for _ <- 1 to 3 do
      try println(s"lazy: ${r.v}")
      catch case e: RuntimeException => println(s"lazy threw: ${e.getMessage}")

    C()
    Derived()
    println(s"static: ${WithStatic.s}")

    println(s"returnThenFinally=${returnThenFinally()}")
    try
      try throw new RuntimeException("first")
      finally throw new IllegalStateException("second")
    catch case e: Throwable => println(s"finally replaced: ${e.getMessage}")
    println(s"boundary: ${firstAbove(1, List(1, 2, 3))}")

    var trace = List.empty[String]
    def t(s: String, v: Int): Int =
      trace ::= s
      v
    val sum = t("a", 1) + t("b", 2) * t("c", 3)
    println(s"operands: ${trace.reverse.mkString(",")} = $sum")
    trace = Nil
    def f3(a: Int, b: Int, c: Int) = a + b + c
    f3(t("x", 1), t("y", 2), t("z", 3))
    println(s"arguments: ${trace.reverse.mkString(",")}")
    trace = Nil
    println(s"concat: ${t("p", 1)}${t("q", 2)} ${trace.reverse.mkString(",")}")
