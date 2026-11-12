import scala.language.dynamics

// Types (docs/_docs/spec/language.md, section 3 and the Scala 3 reference features): erased at
// run time. Union and intersection types, match types, type lambdas, higher-kinded and
// kind-polymorphic types, dependent and polymorphic function types, singleton types, user-defined
// Selectable, Dynamic, generic tuples and named tuples.
trait Walks { def walk: String = "walks" }
trait Swims { def swim: String = "swims" }
class Duck extends Walks with Swims
class Fish extends Swims

def both(x: Walks & Swims): String = s"${x.walk} and ${x.swim}"
def unionName(x: Int | String | Null): String = x match
  case i: Int    => s"int $i"
  case s: String => s"string $s"
  case null      => "null"

type Elem[X] = X match
  case String      => Char
  case Array[t]    => t
  case Iterable[t] => t
  case Option[t]   => t

def firstElem[X](x: X): Elem[X] = x match
  case s: String      => s.charAt(0)
  case a: Array[t]    => a(0)
  case i: Iterable[t] => i.head
  case o: Option[t]   => o.get

type Len[T <: Tuple] <: Int = T match
  case EmptyTuple => 0
  case _ *: t     => scala.compiletime.ops.int.S[Len[t]]

trait Functor[F[_]]:
  extension [A](fa: F[A]) def fmap[B](f: A => B): F[B]
given Functor[List] with
  extension [A](fa: List[A]) def fmap[B](f: A => B): List[B] = fa.map(f)
given Functor[Option] with
  extension [A](fa: Option[A]) def fmap[B](f: A => B): Option[B] = fa.map(f)
type EitherStr = [X] =>> Either[String, X]
given Functor[EitherStr] with
  extension [A](fa: Either[String, A]) def fmap[B](f: A => B): Either[String, B] = fa.map(f)
def incAll[F[_]: Functor](fa: F[Int]): F[Int] = fa.fmap(_ + 1)

class Box[K <: AnyKind]:
  def name: String = "box"

trait Keyed:
  type Key
  val key: Key
def keyOf(e: Keyed): e.Key = e.key
val dependent: (e: Keyed) => e.Key = (e: Keyed) => e.key

val polyId: [T] => T => T = [T] => (t: T) => t
val polyPair: [A, B] => (A, B) => (B, A) = [A, B] => (a: A, b: B) => (b, a)

class Record(fields: (String, Any)*) extends Selectable:
  private val map = fields.toMap
  def selectDynamic(name: String): Any = map(name)
  def applyDynamic(name: String)(args: Any*): Any = s"$name(${args.mkString(",")})"
type PersonRecord = Record { val name: String; val age: Int; def greet(who: String): String }

class Dyn extends Dynamic:
  private var store = Map.empty[String, Any]
  def selectDynamic(name: String): String = s"select $name"
  def updateDynamic(name: String)(value: Any): Unit = store += name -> value
  def applyDynamic(name: String)(args: Any*): String = s"apply $name${args.mkString("(", ",", ")")}"
  def applyDynamicNamed(name: String)(args: (String, Any)*): String =
    s"named $name${args.map((k, v) => s"$k=$v").mkString("(", ",", ")")}"
  def stored: Map[String, Any] = store

sealed trait Animal
final case class Cat(name: String) extends Animal
final case class Dog(name: String) extends Animal

object Main:
  def main(args: Array[String]): Unit =
    println(s"intersection: ${both(Duck())}")
    println(s"union: ${unionName(1)} ${unionName("a")} ${unionName(null)}")
    val pets: List[Cat | Dog] = List(Cat("Tom"), Dog("Rex"))
    println(s"union of classes: ${pets.map { case Cat(n) => s"cat $n"; case Dog(n) => s"dog $n" }}")
    val swimmers: List[Swims] = List(Duck(), Fish())
    println(s"type tests: ${swimmers.map(_.isInstanceOf[Walks & Swims])} ${swimmers.map(_.isInstanceOf[Duck | Fish])}")

    val c: Char = firstElem("hello")
    val i: Int = firstElem(Array(7, 8))
    val d: Double = firstElem(List(1.5))
    val s: String = firstElem(Some("opt"))
    println(s"match types: $c $i $d $s")
    println(s"recursive match type: ${scala.compiletime.constValue[Len[(Int, String, Boolean)]]}")

    val either: Either[String, Int] = Right(1)
    println(s"higher-kinded: ${incAll(List(1, 2))} ${incAll(Option(3))} ${incAll[EitherStr](either)} ${incAll[EitherStr](Left("err"))}")
    println(s"kind polymorphism: ${Box[Int]().name} ${Box[List]().name} ${Box[[X] =>> Map[X, X]]().name}")

    val entry = new Keyed { type Key = String; val key = "k1" }
    val k: String = keyOf(entry)
    println(s"dependent: $k ${dependent(entry)}")
    println(s"polymorphic functions: ${polyId(5)} ${polyId("s")} ${polyPair(1, "x")}")

    val one: 1 = 1
    val yes: "yes" = "yes"
    println(s"singleton types: ${one + 1} $yes ${valueOf[3]} ${valueOf[one.type]}")

    val rec = Record("name" -> "Ann", "age" -> 30).asInstanceOf[PersonRecord]
    println(s"Selectable: ${rec.name} ${rec.age + 1} ${rec.greet("Bo")}")

    val dyn = Dyn()
    dyn.color = "red"
    dyn.size = 3
    println(s"Dynamic: ${dyn.anything} ${dyn.method(1, "a")} ${dyn.named(x = 1, y = 2)} ${dyn.stored.toList.sortBy(_._1)}")

    // Generic tuples.
    val t = (1, "two", 3.0)
    val consed = 0 *: t
    println(s"tuples: $consed ${consed.size} ${t.head} ${t.tail} ${t ++ ("x", 'y')} ${t.toList} ${t.reverse} ${t(1)}")
    println(s"tuple ops: ${t.take(2)} ${t.drop(2)} ${t.zip(("a", "b", "c"))} ${t.map[[X] =>> String]([X] => (x: X) => x.toString * 2)}")
    val big = (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25)
    println(s"TupleXXL: ${big.size} ${big(24)} ${big.getClass.getName} ${big.toList.sum} ${big == big.toArray.toSeq.foldRight(EmptyTuple: Tuple)(_ *: _)}")
    val bigger = 0 *: big
    println(s"TupleXXL cons: ${bigger.size} ${bigger.head} ${bigger.productArity}")
    println(s"tuple from array: ${Tuple.fromArray(Array[Object]("a", "b"))} ${EmptyTuple} ${Tuple1("solo")}")

    // Named tuples.
    val city = (name = "Paris", population = 2_100_000)
    println(s"named tuples: ${city.name} ${city.population} $city ${city.toTuple}")
    val (name = n, population = p) = city
    println(s"named destructuring: $n $p")
    city match
      case (name = "Paris", population = pop) => println(s"named pattern: $pop")
      case _                                  => println("named pattern: other")
