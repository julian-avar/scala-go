// Enums, ADTs and opaque types (docs/_docs/spec/language.md, "Scala 3 reference features";
// data.md "Enums and opaque types"). Enums extending java.lang.Enum are in
// tests/cases/lang-enums-java.
enum Color:
  case Red, Green, Blue
  def isWarm: Boolean = this == Red

object Color:
  def fromName(s: String): Option[Color] = values.find(_.toString.equalsIgnoreCase(s))

enum Planet(val mass: Double, val radius: Double):
  private final val G = 6.67300e-11
  def surfaceGravity: Double = G * mass / (radius * radius)
  case Mercury extends Planet(3.303e+23, 2.4397e6)
  case Earth extends Planet(5.976e+24, 6.37814e6)

enum Opt[+T]:
  case Sm(value: T)
  case Nn
  def getOrElse[U >: T](default: U): U = this match
    case Sm(v) => v
    case Nn    => default
  def map[U](f: T => U): Opt[U] = this match
    case Sm(v) => Sm(f(v))
    case Nn    => Nn

enum Tree[T]:
  case Leaf(value: T)
  case Node(left: Tree[T], right: Tree[T])
  def sum(using n: Numeric[T]): T = this match
    case Leaf(v)    => v
    case Node(l, r) => n.plus(l.sum, r.sum)

trait HasCode:
  def code: String
enum Status(val code: String) extends HasCode:
  case Active extends Status("A")
  case Suspended(reason: String) extends Status("S")
  case Closed extends Status("C")

enum Direction derives CanEqual:
  case North, East, South, West
  def turnRight: Direction = Direction.fromOrdinal((ordinal + 1) % Direction.values.length)

enum JavaStyle extends java.lang.Enum[JavaStyle]:
  case Low, Medium, High

object Units:
  opaque type Meters = Double
  object Meters:
    def apply(d: Double): Meters = d
  extension (m: Meters)
    def value: Double = m
    def +(o: Meters): Meters = m + o
    def show: String = s"${m}m"

  opaque type UserId <: Long = Long
  object UserId:
    def apply(n: Long): UserId = n
    def parse(s: String): Option[UserId] = s.toLongOption.filter(_ > 0)

  opaque type Name = String
  object Name:
    def apply(s: String): Name = s.trim
  extension (n: Name) def greeting: String = s"Hello, $n"

  opaque type Counter = Array[Int]
  object Counter:
    def apply(): Counter = Array(0)
  extension (c: Counter)
    def inc(): Unit = c(0) += 1
    def get: Int = c(0)

import Units.*

object Main:
  def main(args: Array[String]): Unit =
    // Simple cases.
    println(s"values: ${Color.values.toList} ordinals: ${Color.values.map(_.ordinal).toList}")
    println(s"valueOf: ${Color.valueOf("Green")} fromOrdinal: ${Color.fromOrdinal(2)} companion: ${Color.fromName("blue")}")
    try Color.valueOf("Purple")
    catch case e: IllegalArgumentException => println(s"valueOf missing: ${e.getMessage}")
    try Color.fromOrdinal(5)
    catch case e: NoSuchElementException => println(s"fromOrdinal missing: ${e.getClass.getName} ${e.getMessage}")
    println(s"methods: ${Color.values.map(_.isWarm).toList}")
    println(s"identity: ${Color.Red eq Color.valueOf("Red")} ${Color.Red == Color.fromOrdinal(0)} ${Color.Red != Color.Blue}")
    println(s"class names: ${Color.Red.getClass.getName} ${Color.Red.getClass == Color.Blue.getClass} ${classOf[Color].getName}")
    println(s"type tests: ${(Color.Red: Any).isInstanceOf[Color]} ${(Color.Red: Any).isInstanceOf[scala.reflect.Enum]} ${(Color.Red: Any).isInstanceOf[Product]}")
    println(s"productPrefix: ${Color.Red.productPrefix} ${Opt.Nn.productPrefix}")
    val color: Color = Color.Blue
    val described = color match
      case Color.Red   => "red"
      case Color.Green => "green"
      case Color.Blue  => "blue"
    println(s"match: $described")

    // Cases with parameters of the enum.
    for p <- Planet.values do println(f"planet: $p ${p.ordinal} ${p.surfaceGravity}%.2f")

    // ADTs: parameterized cases are case classes.
    val some = new Opt.Sm(41) // `Opt.Sm(41)` has type Opt[Int]
    val none: Opt[Int] = Opt.Nn
    println(s"adt: $some ${some.map(_ + 1)} $none ${none.map(_ + 1)} ${some.getOrElse(0)} ${none.getOrElse(0)}")
    println(s"adt members: ${some.ordinal} ${none.ordinal} ${some.value} ${some == Opt.Sm(41)} ${some.hashCode} ${some.copy(value = 1)} ${Opt.Sm.unapply(some)}")
    println(s"adt class names: ${some.getClass.getName} ${none.getClass.getName}")
    val tree = Tree.Node(Tree.Leaf(1), Tree.Node(Tree.Leaf(2), Tree.Leaf(3)))
    println(s"tree: $tree sum ${tree.sum}")
    val doubles = Tree.Node(Tree.Leaf(1.5), Tree.Leaf(2.25))
    println(s"tree of doubles: ${doubles.sum}")

    val statuses = List(Status.Active, Status.Suspended("late"), Status.Closed)
    println(s"mixed: ${statuses.map(s => s"$s:${s.code}:${s.ordinal}")}")
    val hc: HasCode = Status.Suspended("x")
    println(s"enum as trait: ${hc.code} ${hc.isInstanceOf[Status]}")

    println(s"derives CanEqual: ${Direction.North.turnRight} ${Direction.West.turnRight} ${Direction.North == Direction.North}")

    // Java-style enums: Enum methods.
    println(s"java enum: ${JavaStyle.Medium.name} ${JavaStyle.Medium.ordinal} ${JavaStyle.Low.compareTo(JavaStyle.High)} ${JavaStyle.values.toList} ${JavaStyle.valueOf("High")}")
    println(s"java enum sorted: ${List(JavaStyle.High, JavaStyle.Low, JavaStyle.Medium).sorted}")
    println(s"java enum class: ${(JavaStyle.Low: Any).isInstanceOf[java.lang.Enum[?]]}")

    // Opaque types: the underlying value, no wrapper.
    val d = Meters(1.5) + Meters(2.0)
    println(s"opaque: ${d.show} ${d.value} ${(d: Any).isInstanceOf[Double]} ${(d: Any).getClass.getName}")
    val ids = List(UserId(3), UserId(1), UserId(2))
    val total: Long = ids.map(id => id: Long).sum // a subtype of Long
    println(s"opaque bounded: ${ids.sorted(using Ordering.Long)} $total ${UserId.parse("17")} ${UserId.parse("-1")} ${UserId.parse("x")}")
    val name = Name("  Ada ")
    println(s"opaque string: ${name.greeting} ${name == ("Ada": Any)}")
    val c = Counter()
    c.inc(); c.inc()
    println(s"opaque array: ${c.get}")
    val meters: List[Meters] = List(Meters(1), Meters(2))
    println(s"opaque in collections: ${meters.map(_.value).sum} ${meters.map(_.show)}")
