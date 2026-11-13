import scala.language.implicitConversions
import scala.reflect.{ClassTag, TypeTest}
import scala.util.NotGiven

// Implicits (docs/_docs/spec/language.md, section 7, and the Scala 3 reference features): givens
// and using clauses, implicit conversions, implicit classes, extension methods, context
// functions, multiversal equality and ClassTags. At run time they are ordinary values, methods
// and lambdas.
trait Ord[T]:
  def compare(a: T, b: T): Int
  extension (a: T)
    def isLess(b: T): Boolean = compare(a, b) < 0
    def maxOf(b: T): T = if compare(a, b) >= 0 then a else b

object Ord:
  given Ord[Int] with
    def compare(a: Int, b: Int) = Integer.compare(a, b)
  given Ord[String] = (a, b) => a.compareTo(b)
  given listOrd[T](using o: Ord[T]): Ord[List[T]] with
    def compare(a: List[T], b: List[T]): Int = (a, b) match
      case (Nil, Nil)         => 0
      case (Nil, _)           => -1
      case (_, Nil)           => 1
      case (x :: xs, y :: ys) =>
        val c = o.compare(x, y)
        if c != 0 then c else compare(xs, ys)

def maximum[T: Ord](xs: List[T]): T = xs.reduce(_ maxOf _)
def sortDesc[T](xs: List[T])(using ord: Ord[T]): List[T] = xs.sortWith((a, b) => ord.compare(a, b) > 0)

// Given initialization: an alias given is lazy, evaluated once.
object Config:
  var created = 0
  case class Settings(verbose: Boolean)
  given settings: Settings =
    created += 1
    Settings(verbose = true)

def describe(using s: Config.Settings): String = s"verbose=${s.verbose}"

// Extension methods.
extension (s: String)
  def shout: String = s.toUpperCase + "!"
  def words: List[String] = s.split(" ").toList
extension [T](xs: List[T])
  def second: T = xs(1)
  def mapSecond(f: T => T): List[T] = xs.updated(1, f(xs(1)))
extension (n: Int)(using o: Ord[Int])
  def clampMax(m: Int): Int = if o.compare(n, m) > 0 then m else n

// Implicit conversions and classes.
case class Celsius(degrees: Double)
given Conversion[Int, Celsius] = Celsius(_)
def warm(c: Celsius): String = s"${c.degrees}C"

object Legacy:
  implicit class RichInt(val n: Int) extends AnyVal:
    def times(f: => Unit): Unit = for _ <- 1 to n do f
  implicit val defaultSep: String = ", "
  def join(xs: List[String])(implicit sep: String): String = xs.mkString(sep)
  implicit def intToString(n: Int): String = s"#$n"
  def label(s: String): String = s"label $s"

// Context functions.
case class Transaction(id: Int):
  var log = Vector.empty[String]
type Transactional[T] = Transaction ?=> T
def record(msg: String): Transactional[Unit] = summon[Transaction].log :+= msg
def inTransaction[T](id: Int)(body: Transactional[T]): (T, Vector[String]) =
  given t: Transaction = Transaction(id)
  val r = body
  (r, t.log)

// Prioritization and NotGiven.
trait Describe[T]:
  def describe: String
object Describe:
  given fallback[T](using NotGiven[T <:< AnyVal]): Describe[T] with
    def describe = "a reference type"
  given forAnyVal[T](using T <:< AnyVal): Describe[T] with
    def describe = "a value type"
def what[T](using d: Describe[T]): String = d.describe

// Multiversal equality.
case class UserId(n: Int) derives CanEqual
case class Email(s: String)
given CanEqual[Email, String] = CanEqual.derived

// Givens for type class instances on generic types, and by-name givens.
trait Monoid[T]:
  def empty: T
  def combine(a: T, b: T): T
given Monoid[Int] with
  def empty = 0
  def combine(a: Int, b: Int) = a + b
given Monoid[String] with
  def empty = ""
  def combine(a: String, b: String) = a + b
given pairMonoid[A, B](using a: Monoid[A], b: Monoid[B]): Monoid[(A, B)] with
  def empty = (a.empty, b.empty)
  def combine(x: (A, B), y: (A, B)) = (a.combine(x._1, y._1), b.combine(x._2, y._2))
def combineAll[T](xs: List[T])(using m: Monoid[T]): T = xs.foldLeft(m.empty)(m.combine)

def makeArray[T: ClassTag](n: Int, x: T): Array[T] = Array.fill(n)(x)
def className[T](using ct: ClassTag[T]): String = ct.runtimeClass.getName

// Type tests: a user-defined TypeTest, a ClassTag pattern and PartialFunction.elementWise.
case class Num(n: Int)
given TypeTest[Any, Num] with
  def unapply(x: Any): Option[x.type & Num] = x match
    case n: (x.type & Num) @unchecked => Some(n)
    case _                            => None
def onlyOf[T](xs: List[Any])(using TypeTest[Any, T]): List[T] = xs.collect { case t: T => t }
def tagged[T: ClassTag](xs: List[Any]): List[T] = xs.collect { case t: T => t }

object Main:
  def main(args: Array[String]): Unit =
    println(s"givens: ${maximum(List(3, 9, 2))} ${maximum(List("pear", "apple"))} ${maximum(List(List(1, 2), List(1, 3), List(1)))}")
    println(s"using: ${sortDesc(List(1, 3, 2))} ${sortDesc(List("b", "c", "a"))(using Ord.given_Ord_String)}")
    println(s"summon: ${summon[Ord[Int]].compare(1, 2)} ${summon[Ord[List[Int]]].compare(List(2), List(1, 5))}")
    import Ord.given
    println(s"extension in given: ${3.isLess(4)} ${"b".isLess("a")} ${List(1).isLess(List(1, 0))}")
    val local: Ord[Int] = (a, b) => Integer.compare(b, a)
    println(s"explicit using: ${maximum(List(3, 9, 2))(using local)}")

    println(s"lazy given: before ${Config.created}")
    println(s"${describe(using Config.settings)} ${describe(using Config.settings)} created ${Config.created}")
    locally:
      given Config.Settings = Config.Settings(false)
      println(s"local given: $describe")

    println(s"extensions: ${"hi".shout} ${"a b c".words} ${List(1, 2, 3).second} ${List("x", "y").mapSecond(_ * 3)} ${15.clampMax(10)} ${shout("direct")}")

    println(s"conversion: ${warm(21)} ${warm(Celsius(1.5))}")
    import Legacy.*
    var count = 0
    3.times { count += 1 }
    println(s"implicit class: $count ${join(List("a", "b"))} ${join(List("a", "b"))(" | ")} ${label(5)}")

    val (result, log) = inTransaction(7) {
      record("start")
      record(s"id ${summon[Transaction].id}")
      42
    }
    println(s"context function: $result $log")
    val ctxFun: Transaction ?=> Int = summon[Transaction].id * 2
    println(s"context function value: ${ctxFun(using Transaction(5))}")

    println(s"NotGiven: ${what[Int]} ${what[String]} ${what[List[Int]]}")

    println(s"CanEqual: ${UserId(1) == UserId(1)} ${UserId(1) == UserId(2)} ${Email("a") == "a"} ${Email("a") == Email("a")}")

    println(s"derived instances: ${combineAll(List(1, 2, 3))} ${combineAll(List("a", "b"))} ${combineAll(List((1, "x"), (2, "y")))}")

    val arr = makeArray(3, "s")
    val ints = makeArray(2, 7)
    println(s"ClassTag: ${arr.mkString(",")} ${ints.sum} ${className[String]} ${className[Int]} ${className[List[Int]]} ${className[Array[Int]]}")
    println(s"ClassTag newArray: ${summon[ClassTag[Int]].newArray(3).length} ${summon[ClassTag[Int]].runtimeClass == classOf[Int]}")

    println(s"TypeTest: ${onlyOf[Num](List(Num(1), "x", Num(2)))} ${tagged[String](List(1, "a", 2.5, "b"))} ${tagged[Int](List(1, "a", 2))}")
    val positive: PartialFunction[Int, Int] = { case x if x > 0 => x }
    println(s"elementWise: ${positive.elementWise.unapplySeq(List(1, 2))} ${positive.elementWise.unapplySeq(List(1, -2))}")
