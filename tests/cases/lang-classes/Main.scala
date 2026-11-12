import java.util.concurrent.{CountDownLatch, CyclicBarrier}
import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.static

// Classes and objects (docs/_docs/spec/language.md, section 5; data.md "Classes and objects"):
// constructors, inner classes, case classes and their generated members, value classes, objects
// constructed on first access (once, also when threads race), companions and @static members.
class ++
class `a-b`

class Temperature(val celsius: Double):
  private var readings = 0
  def this(fahrenheit: Int, unit: String) =
    this((fahrenheit - 32) / 1.8)
    readings = 1
  def this() = this(0.0)
  def read(): Int = { readings += 1; readings }
  override def toString = f"$celsius%.1fC"

class Outer(val name: String):
  private val secret = name.length
  class Inner(val n: Int):
    def describe: String = s"$name.$n (secret $secret)"
    def outer: Outer = Outer.this
  def make(n: Int): Inner = Inner(n)

abstract class Container:
  type Elem
  def elems: List[Elem]
  def first: Elem = elems.head

class IntContainer extends Container:
  type Elem = Int
  def elems = List(3, 1, 2)

class Base:
  private def secret = "base secret"
  protected def prot = "protected"
  def callSecret: String = secret
  def hook: String = "base hook"
  def template: String = s"[$hook]"
  final def fixed = "fixed"

class Sub extends Base:
  def secret = "sub secret" // does not override the private method
  override def hook: String = s"sub hook, ${prot}"

case class Point(x: Int, y: Int):
  def +(o: Point) = Point(x + o.x, y + o.y)

case class Person(name: String, age: Int, tags: List[String] = Nil)
case class Box[T](value: T)
case class Mutable(var count: Int)
case class Empty()
case object Singleton
case class WithBody(a: Int):
  val doubled = a * 2
  override def toString = s"WithBody<$a>"

class Plain(val n: Int)

class Meters(val value: Double) extends AnyVal:
  def +(o: Meters): Meters = Meters(value + o.value)
  override def toString = s"${value}m"

object Counter:
  val created = AtomicInteger()
  def next(): Int = created.incrementAndGet()

object Lazy:
  println("Lazy: constructing")
  val ready = "ready"

object Slow:
  val constructions = AtomicInteger()
  constructions.incrementAndGet()
  Thread.sleep(50)
  val value = 42

class WithStatics
object WithStatics:
  @static var hits: Int = 0
  @static def hit(): Int = { hits += 1; hits }
  @static final val Label = "static label"

class Registry private (val name: String):
  Registry.count += 1
object Registry:
  private var count = 0
  def apply(name: String): Registry = new Registry(name.toUpperCase)
  def created: Int = count

object Main:
  def main(args: Array[String]): Unit =
    // Constructors.
    val t1 = Temperature(212, "F")
    val t2 = Temperature()
    println(s"constructors: $t1 ${t1.read()} $t2 ${t2.read()}")

    // Inner classes and their outer instance.
    val o1 = Outer("alpha")
    val o2 = Outer("beta")
    val i1 = o1.make(1)
    val i2 = new o2.Inner(2)
    println(s"inner: ${i1.describe} ${i2.describe} ${i1.outer eq o1} ${i2.outer.name}")
    val inners: List[Outer#Inner] = List(i1, i2)
    println(s"projection: ${inners.map(_.n)}")

    // Abstract type members.
    val c: Container = IntContainer()
    println(s"type member: ${c.first} ${c.elems}")

    // Private methods are not virtual; overridden methods are, also through `this`.
    val s: Base = Sub()
    println(s"dispatch: ${s.callSecret} | ${s.template} | ${s.fixed} | ${Sub().secret}")

    // Case classes: generated members.
    val p = Point(1, 2)
    println(s"case class: $p ${p == Point(1, 2)} ${p != Point(2, 1)} ${p eq Point(1, 2)} ${p + p}")
    println(s"hashCode: ${p.hashCode} ${Point(0, 0).hashCode} ${Person("Ann", 30).hashCode} ${Empty().hashCode} ${Singleton.hashCode} ${Box("x").hashCode}")
    println(s"copy: ${p.copy(y = 9)} ${Person("Bo", 4).copy(tags = List("a"))}")
    println(s"product: ${p.productArity} ${p.productElement(1)} ${p.productPrefix} ${p.productElementNames.toList} ${p.productIterator.toList}")
    val Person(name, age, tags) = Person("Cy", 40, List("x", "y"))
    println(s"unapply: $name $age $tags ${Point.unapply(p)}")
    println(s"companion: ${Point.apply(3, 4)} ${(Point.apply).tupled((5, 6))} ${List((7, 8)).map(Point.apply.tupled)}")
    println(s"defaults: ${Person("Di", 5)} ${Box(List(1))} ${Box(null)} ${Empty()} $Singleton ${WithBody(3)} ${WithBody(3).doubled}")
    val mc = Mutable(1)
    val before = mc.hashCode
    mc.count += 1
    println(s"var field: $mc ${before == mc.hashCode} ${mc == Mutable(2)}")
    println(s"equality across types: ${Box(1) == Box(1L)} ${Box(1) == Box("1")} ${(Point(1, 2): Any) == ((1, 2): Any)}")
    println(s"canEqual: ${p.canEqual(Point(0, 0))} ${p.canEqual("x")}")
    println(s"sets of case classes: ${Set(Point(1, 1), Point(1, 1), Point(2, 2)).size}")
    val plain1 = Plain(1)
    println(s"plain equality: ${plain1 == Plain(1)} ${plain1 == plain1}")

    // Value classes.
    val m = Meters(1.5) + Meters(2)
    val ms: List[Meters] = List(m, Meters(0))
    println(s"value class: $m ${ms.map(_.value)} ${(m: Any).isInstanceOf[Meters]} ${m == Meters(3.5)}")

    // Objects are constructed on first access, once.
    println("before Lazy")
    println(s"Lazy: ${Lazy.ready} ${Lazy.ready}")
    println(s"object: ${Counter.next()} ${Counter.next()} ${Counter eq Counter}")

    // Threads accessing an object under construction wait for it.
    val threads = 4
    val barrier = CyclicBarrier(threads)
    val done = CountDownLatch(threads)
    val sum = AtomicInteger()
    for _ <- 1 to threads do
      Thread(() => {
        barrier.await()
        sum.addAndGet(Slow.value)
        done.countDown()
      }).start()
    done.await()
    println(s"concurrent construction: ${Slow.constructions.get} construction(s), sum ${sum.get}")

    // @static members.
    WithStatics.hit()
    println(s"static: ${WithStatics.hit()} ${WithStatics.hits} ${WithStatics.Label}")

    // Companions: private constructor, factory, shared private state.
    val reg = Registry("main")
    Registry("other")
    println(s"companion: ${reg.name} ${Registry.created}")

    // Simple names: of local classes, operator-named ones ($plus$plus) and the library's :: .
    class Local
    println(s"simple names: ${classOf[Local].getSimpleName} ${classOf[++].getSimpleName} ${classOf[`a-b`].getSimpleName} ${List(1).getClass.getSimpleName} ${classOf[::[?]].getSimpleName}")
