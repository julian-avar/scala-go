import scala.annotation.{switch, tailrec, targetName, varargs, nowarn, implicitNotFound}
import scala.beans.BeanProperty

// Basic definitions and annotations (docs/_docs/spec/language.md, sections 4 and 11): vals, vars,
// lazy vals, defs with by-name, default, named and repeated parameters, @tailrec, @targetName,
// @BeanProperty, @varargs, @throws and the annotations without a run-time effect.
class Account(initial: Int):
  private var _balance = initial
  def balance: Int = _balance
  def balance_=(v: Int): Unit =
    require(v >= 0, s"negative balance $v")
    _balance = v
  @volatile var touched = false
  @transient var cache: String = "cached"

class Bean:
  @BeanProperty var name: String = "initial"
  @BeanProperty val id: Int = 7
  @BeanProperty var active: Boolean = false

class Holder:
  var evaluations = 0
  lazy val value: String =
    evaluations += 1
    s"computed $evaluations"
  lazy val dependent: Int = value.length * 2

@SerialVersionUID(42L)
class Serial(val n: Int) extends Serializable

@implicitNotFound("no Show for ${T}")
trait Show[T]:
  def show(t: T): String

object Main:
  var log = List.empty[String]
  def note[T](s: String, v: T): T =
    log ::= s
    v
  def flushLog(): String =
    val r = log.reverse.mkString(",")
    log = Nil
    r

  // By-name parameters are evaluated at each use.
  def twice(body: => Int): Int = body + body
  def maybe(cond: Boolean)(body: => String): String = if cond then body else "skipped"

  // Default arguments are evaluated at each call, may refer to earlier parameters and to members.
  var counter = 0
  def nextId(): Int = { counter += 1; counter }
  def make(name: String, id: Int = nextId(), tag: String = "t")(size: Int = name.length + id): String =
    s"$name/$size/$id/$tag"

  // Repeated parameters.
  def sum(xs: Int*): Int = xs.sum
  def describe(prefix: String, xs: Any*): String = s"$prefix(${xs.length}): ${xs.mkString(" ")}"
  @varargs def joined(sep: String, parts: String*): String = parts.mkString(sep)

  // Multiple parameter lists and currying.
  def add(a: Int)(b: Int)(c: Int): Int = a * 100 + b * 10 + c

  @tailrec def sumTo(n: Long, acc: Long = 0): Long = if n == 0 then acc else sumTo(n - 1, acc + n)
  @tailrec def gcd(a: Int, b: Int): Int = if b == 0 then a else gcd(b, a % b)

  @targetName("plusInts") def plus(xs: List[Int]): Int = xs.sum
  @targetName("plusStrings") def plus(xs: List[String]): String = xs.mkString

  // Overloading.
  def kind(x: Int): String = "Int"
  def kind(x: Long): String = "Long"
  def kind(x: Double): String = "Double"
  def kind(x: String): String = "String"
  def kind(x: Any): String = "Any"

  @throws[IllegalStateException]("always")
  def failing(): Int = throw IllegalStateException("thrown")

  @deprecated("use other", "1.0") def old(): Int = 1
  @inline def inlined(x: Int): Int = x + 1
  @noinline def notInlined(x: Int): Int = x + 2

  def letter(n: Int): Char = (n: @switch) match
    case 0 => 'a'
    case 1 => 'b'
    case 2 => 'c'
    case _ => '?'

  @nowarn("msg=deprecated")
  def main(args: Array[String]): Unit =
    // val, var, setters.
    val acc = Account(10)
    acc.balance = 25
    acc.balance += 5
    println(s"balance: ${acc.balance}")
    try acc.balance = -1
    catch case e: IllegalArgumentException => println(s"setter: ${e.getMessage}")
    acc.touched = true
    println(s"volatile: ${acc.touched}, transient: ${acc.cache}")

    // Pattern definitions.
    val (q, r) = (17 / 5, 17 % 5)
    val head :: tail = List(1, 2, 3): @unchecked
    val Some(found) = Option("x"): @unchecked
    val Array(first, second, _*) = Array(10, 20, 30, 40): @unchecked
    println(s"pattern vals: $q $r $head $tail $found $first $second")
    var (m, n) = (1, 2)
    m += 10; n *= 3
    println(s"pattern vars: $m $n")

    // lazy val: evaluated once, on first access; dependent lazy vals.
    val h = Holder()
    println(s"before access: ${h.evaluations}")
    println(s"${h.dependent} ${h.value} ${h.value} evaluations=${h.evaluations}")
    lazy val local = note("local lazy", 99)
    println(s"local lazy declared: [${flushLog()}]")
    println(s"local lazy: ${local + local} ${flushLog()}")

    // By-name parameters.
    println(s"by-name: ${twice(note("eval", 21))} ${flushLog()}")
    println(s"by-name skipped: ${maybe(false)(note("never", "x"))} [${flushLog()}]")
    println(s"by-name used: ${maybe(true)(note("once", "y"))} [${flushLog()}]")

    // Defaults and named arguments (named arguments are evaluated in the order written).
    println(s"defaults: ${make("abc")()} ${make("abc")()} ${make("de", tag = "named")()} ${make(id = 0, name = "xyz")(size = 1)}")
    val named = make(tag = note("tag", "T"), name = note("name", "n"))(size = note("size", 1))
    println(s"named order: $named ${flushLog()}")

    // Repeated parameters, splices, @varargs.
    val nums = List(1, 2, 3)
    println(s"varargs: ${sum()} ${sum(1)} ${sum(1, 2, 3)} ${sum(nums*)} ${sum(Array(4, 5)*)} ${sum((1 to 4)*)}")
    println(s"${describe("p")} | ${describe("q", 1, "two", 3.0, null)} | ${joined("-", "a", "b", "c")} | ${joined("+", Seq("x", "y")*)}")

    // Currying and partial application.
    val add1 = add(1)
    val add12 = add1(2)
    println(s"curried: ${add(1)(2)(3)} ${add12(4)} ${List(5, 6).map(add(7)(8))}")

    // @tailrec: a loop, so a deep recursion does not overflow.
    println(s"tailrec: ${sumTo(1_000_000)} ${gcd(1071, 462)}")
    println(s"targetName: ${plus(List(1, 2))} ${plus(List("a", "b"))}")
    println(s"overloads: ${kind(1)} ${kind(1L)} ${kind(1.5)} ${kind("s")} ${kind('c')} ${kind(List(1))} ${kind(1.5f)}")

    // Annotations.
    val bean = Bean() // Scala 3: the bean accessors are for Java code only
    bean.name = "set"
    println(s"bean: ${bean.name} ${bean.id} ${bean.active}")
    try failing()
    catch case e: IllegalStateException => println(s"@throws: ${e.getMessage}")
    println(s"misc: ${old()} ${inlined(1)} ${notInlined(1)} ${Serial(3).n} ${(0 to 3).map(letter).mkString}")
    val unchecked = (List(1): Any) match
      case l: List[String @unchecked] => l.length
      case _ => -1
    println(s"@unchecked: $unchecked")
    given Show[Int] = _.toString + "!"
    println(s"given with @implicitNotFound: ${summon[Show[Int]].show(5)}")

    // Local definitions see and update enclosing vars.
    var total = 0
    def addToTotal(k: Int): Unit = total += k
    (1 to 4).foreach(addToTotal)
    println(s"closures over vars: $total")

    // Type aliases have no run-time existence.
    type Pair = (Int, String)
    val p: Pair = (1, "one")
    println(s"alias: $p ${p.getClass.getName}")
