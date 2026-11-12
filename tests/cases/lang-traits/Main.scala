// Traits and dispatch (docs/_docs/spec/language.md, section 5 "Dispatch" and the Scala 3
// reference features; data.md "Traits"): linearization, super calls, stackable modifications,
// super[T], trait parameters, transparent traits, universal traits, export clauses.
trait Logger:
  def log(msg: String): String = s"log($msg)"

trait Timestamps extends Logger:
  override def log(msg: String): String = super.log(s"t:$msg")

trait Upper extends Logger:
  override def log(msg: String): String = super.log(msg.toUpperCase)

class Service extends Logger with Timestamps with Upper
class Service2 extends Logger with Upper with Timestamps

trait A:
  def who: String = "A"
  def trace: List[String] = List("A")
trait B extends A:
  override def who: String = "B"
  override def trace: List[String] = "B" :: super.trace
trait C extends A:
  override def who: String = "C"
  override def trace: List[String] = "C" :: super.trace
class D extends B with C:
  override def who: String = s"D(${super[B].who}, ${super[C].who}, ${super.who})"
  override def trace: List[String] = "D" :: super.trace

// Stackable modifications with abstract override.
abstract class IntQueue:
  def put(x: Int): Unit
  def get(): Int
class BasicQueue extends IntQueue:
  private val buf = scala.collection.mutable.ArrayBuffer.empty[Int]
  def put(x: Int): Unit = buf += x
  def get(): Int = buf.remove(0)
trait Doubling extends IntQueue:
  abstract override def put(x: Int): Unit = super.put(2 * x)
trait Incrementing extends IntQueue:
  abstract override def put(x: Int): Unit = super.put(x + 1)
trait Filtering extends IntQueue:
  abstract override def put(x: Int): Unit = if x >= 0 then super.put(x)

// Trait parameters.
trait Greeting(val name: String):
  def msg = s"How are you, $name"
class E extends Greeting("Bob"):
  println(msg)
trait FormalGreeting extends Greeting:
  override def msg = s"How do you do, $name"
class F extends E, FormalGreeting

// Trait initialization order.
trait InitA:
  Order.add("InitA")
  val fromA = "a"
trait InitB extends InitA:
  Order.add("InitB")
trait InitC extends InitA:
  Order.add(s"InitC sees $fromA")
class InitBase:
  Order.add("InitBase")
class InitAll extends InitBase with InitB with InitC:
  Order.add("InitAll")
object Order:
  var steps = Vector.empty[String]
  def add(s: String): Unit = steps :+= s

// Transparent traits and universal traits.
transparent trait Kind
trait Named { def name: String }
case object Cat extends Named, Kind { def name = "cat" }
case object Dog extends Named, Kind { def name = "dog" }

trait Printable extends Any:
  def print: String = s"<$this>"
class Wrapper(val n: Int) extends AnyVal with Printable:
  override def toString = s"W$n"

// Same method from a class and a trait: the class wins.
class Concrete:
  def greet: String = "class"
trait Default:
  def greet: String = "trait"
class Mixed extends Concrete with Default:
  override def greet: String = super.greet + "+" + super[Default].greet

// Export clauses.
class Printer:
  var printed = 0
  def print(s: String): String = { printed += 1; s"printed $s" }
  def status: String = s"printer: $printed"
class Scanner:
  def scan(): String = "scanned"
  def status: String = "scanner ok"
class Copier:
  private val printer = Printer()
  private val scanner = Scanner()
  export printer.{print, printed}
  export scanner.{status as _, *}
  def status = s"${printer.status}, ${scanner.status}"
object Utils:
  def twice(n: Int): Int = n * 2
  val label = "utils"
object Facade:
  export Utils.*

// A trait implemented by an anonymous class and a lambda (SAM).
trait Transformer:
  def apply(s: String): String
  def andThenUpper: Transformer = s => apply(s).toUpperCase

object Main:
  def main(args: Array[String]): Unit =
    println(s"linearization: ${Service().log("x")} ${Service2().log("x")}")
    println(s"super[T]: ${D().who}")
    println(s"super chain: ${D().trace}")

    val q1 = new BasicQueue with Doubling with Incrementing with Filtering
    val q2 = new BasicQueue with Filtering with Incrementing with Doubling
    for x <- List(-1, 0, 1) do { q1.put(x); q2.put(x) }
    println(s"stackable: ${q1.get()} ${q1.get()} | ${q2.get()} ${q2.get()}")

    print("trait parameters: ")
    val f = F()
    println(s"${f.msg} ${f.name} ${(f: Greeting).isInstanceOf[FormalGreeting]}")

    InitAll()
    println(s"init order: ${Order.steps.mkString(", ")}")

    val animals = List(Cat, Dog) // inferred List[Named & Product], not Kind
    println(s"transparent: ${animals.map(_.name)} ${animals.forall(_.isInstanceOf[Kind])}")
    println(s"universal trait: ${Wrapper(3).print}")
    println(s"class over trait: ${Mixed().greet}")

    val copier = Copier()
    println(s"exports: ${copier.print("a")} ${copier.print("b")} ${copier.printed} ${copier.scan()} ${copier.status}")
    println(s"exported object members: ${Facade.twice(21)} ${Facade.label}")

    val anon = new Transformer:
      def apply(s: String) = s.reverse
    val sam: Transformer = s => s + s
    println(s"traits as functions: ${anon("abc")} ${sam("ab")} ${sam.andThenUpper("xy")}")

    val traitRefs: List[Logger] = List(Service(), new Logger {}, new Upper {})
    println(s"type tests: ${traitRefs.map(l => (l.isInstanceOf[Upper], l.isInstanceOf[Timestamps]))}")
    println(s"trait dispatch: ${traitRefs.map(_.log("m"))}")
