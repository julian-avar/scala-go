import scala.compiletime.*
import scala.compiletime.ops.int.*
import scala.deriving.Mirror

// Inline, compile-time operations, macros and type class derivation with Mirrors
// (docs/_docs/spec/language.md, "Scala 3 reference features"): all expanded by the compiler.
trait Show[T]:
  def show(t: T): String

object Show:
  given Show[Int] = _.toString
  given Show[String] = s => s"\"$s\""
  given Show[Boolean] = b => if b then "yes" else "no"
  given [T](using s: Show[T]): Show[List[T]] = _.map(s.show).mkString("[", ", ", "]")

  inline def summonShows[T <: Tuple]: List[Show[?]] = inline erasedValue[T] match
    case _: EmptyTuple => Nil
    case _: (t *: ts)  => summonOrDerive[t] :: summonShows[ts]

  inline def summonOrDerive[T]: Show[T] = summonFrom {
    case s: Show[T]      => s
    case m: Mirror.Of[T] => derived[T](using m)
  }

  inline def derived[T](using m: Mirror.Of[T]): Show[T] =
    lazy val shows = summonShows[m.MirroredElemTypes]
    val label = constValue[m.MirroredLabel]
    inline m match
      case s: Mirror.SumOf[T] =>
        (t: T) => shows(s.ordinal(t)).asInstanceOf[Show[T]].show(t)
      case p: Mirror.ProductOf[T] =>
        val names = constValueTuple[p.MirroredElemLabels].toList.map(_.toString)
        (t: T) =>
          val fields = t.asInstanceOf[Product].productIterator.toList
          val parts = names.lazyZip(fields).lazyZip(shows).map((n, f, s) => s"$n=${s.asInstanceOf[Show[Any]].show(f)}")
          parts.mkString(s"$label(", ", ", ")")

case class Person(name: String, age: Int, admin: Boolean) derives Show
enum Shape derives Show:
  case Circle(radius: Int)
  case Square(side: Int, tags: List[String])

object Main:
  inline val Limit = 3
  var evaluations = 0
  def effect(n: Int): Int = { evaluations += 1; n }

  inline def twiceInline(inline x: Int): Int = x + x    // the argument is duplicated
  inline def twiceValue(x: Int): Int = x + x            // the argument is evaluated once

  inline def choose(inline flag: Boolean): String = inline if flag then "then-branch" else "else-branch"

  transparent inline def default(inline kind: String): Any = inline kind match
    case "int"    => 0
    case "string" => ""
    case _        => false

  inline def sizeOf[T]: Int = inline erasedValue[T] match
    case _: Byte   => 1
    case _: Short  => 2
    case _: Int    => 4
    case _: Long   => 8
    case _: String => -1

  inline def describe[T]: String = summonFrom {
    case o: Ordering[T] => s"ordered (${o.getClass.getName.nonEmpty})"
    case _              => "unordered"
  }

  inline def fact(inline n: Int): Int = inline if n <= 1 then 1 else n * fact(n - 1)

  def main(args: Array[String]): Unit =
    evaluations = 0
    val a = twiceInline(effect(5))
    val evalInline = evaluations
    evaluations = 0
    val b = twiceValue(effect(5))
    println(s"inline parameters: $a $evalInline evaluation(s), $b $evaluations evaluation(s)")
    println(s"inline if: ${choose(true)} ${choose(false)} ${Limit * 2}")
    val i: Int = default("int")         // transparent: the type is Int
    val s: String = default("string")
    println(s"transparent inline: ${i + 1} [${s}] ${default("other")}")
    println(s"inline match: ${sizeOf[Byte]} ${sizeOf[Long]} ${sizeOf[String]}")
    println(s"recursive inline: ${fact(10)}")

    println(s"constValue: ${constValue[Limit.type]} ${constValue[2 + 3]} ${constValue[10 * 10 - 1]} ${constValueOpt[Int]} ${constValue["literal"]}")
    println(s"constValueTuple: ${constValueTuple[(1, "two", true)]}")
    println(s"summonFrom: ${describe[Int]} ${describe[Thread]}")
    println(s"summonInline: ${summonInline[Ordering[String]].compare("a", "b")}")
    println(s"codeOf: ${codeOf(1 + 2 * 3)}")

    // Type class derivation.
    println(s"derives: ${summon[Show[Person]].show(Person("Ann", 30, true))}")
    val shapes = List(Shape.Circle(1), Shape.Square(2, List("a", "b")))
    println(s"derives sum: ${shapes.map(summon[Show[Shape]].show)}")

    // Mirrors at run time.
    val pm = summon[Mirror.ProductOf[Person]]
    println(s"fromProduct: ${pm.fromProduct(("Bo", 7, false))} ${pm.fromTuple(("Cy", 8, true))}")
    val sm = summon[Mirror.SumOf[Shape]]
    println(s"ordinal: ${shapes.map(sm.ordinal)}")
    println(s"tuple of product: ${Tuple.fromProductTyped(Person("Di", 9, false))}")

    // Macros.
    val x = 1.5
    println(s"macro power: ${Macros.power(2.0, 10)} ${Macros.power(x, 3)} ${Macros.power(x, 0)}")
    println(s"macro fields: ${Macros.fieldNames[Person]}")
    println(s"macro debug: ${Macros.debug(x * 2)}")
