// Type tests and casts (docs/_docs/spec/language.md, "Type tests and casts"): isInstanceOf,
// asInstanceOf, type patterns and catch clauses on boxes, strings, arrays, classes, traits and
// null. Failed casts print only the exception's class: its message is a documented deviation.
// Not here: arrays of references of another element type (limits-data).
trait Animal:
  def name: String
trait Pet
class Dog(val name: String) extends Animal with Pet
class Puppy(name: String) extends Dog(name)
class Cat(val name: String) extends Animal
final class Rock
abstract class NeverInstantiated
trait NeverImplemented
case class Box[T](value: T)
sealed trait Shape
case class Circle(r: Double) extends Shape
case class Square(s: Double) extends Shape
object Lone

object Main:
  def p(what: String)(body: => Any): Unit =
    val r =
      try String.valueOf(body)
      catch case e: Throwable => s"throws ${e.getClass.getName}"
    println(s"$what: $r")
  def any(x: Any): Any = x

  def describe(x: Any): String = x match
    case null                 => "null"
    case i: Int               => s"Int $i"
    case l: Long              => s"Long $l"
    case d: Double            => s"Double $d"
    case f: Float             => s"Float $f"
    case c: Char              => s"Char $c"
    case b: Byte              => s"Byte $b"
    case s: Short             => s"Short $s"
    case b: Boolean           => s"Boolean $b"
    case u: Unit              => s"Unit $u"
    case s: String            => s"String $s"
    case a: Array[Int]        => s"Array[Int] ${a.length}"
    case a: Array[Double]     => s"Array[Double] ${a.length}"
    case a: Array[?]          => s"Array of references ${a.length}"
    case p: Puppy             => s"Puppy ${p.name}"
    case d: Dog               => s"Dog ${d.name}"
    case a: Animal            => s"Animal ${a.name}"
    case Box(v: Int)          => s"Box of Int $v"
    case Box(v)               => s"Box of $v"
    case l: List[?]           => s"List of ${l.size}"
    case f: Function1[?, ?]   => "Function1"
    case f: Function2[?, ?, ?] => "Function2"
    case o: Option[?]         => s"Option $o"
    case Lone                 => "Lone"
    case n: Number            => s"Number ${n.getClass.getName}"
    case c: CharSequence      => s"CharSequence ${c.length}"
    case other                => s"other ${other.getClass.getName}"

  def main(args: Array[String]): Unit =
    val values: List[Any] = List(1, 2L, 3.0, 4.0f, 'c', 5.toByte, 6.toShort, true, (), "str", Array(1, 2), Array(1.0),
      Array("a", "b"), Array(Array(1)), Puppy("rex"), Dog("fido"), Cat("tom"), Box(7), Box("x"), (x: Int) => x,
      (a: Int, b: Int) => a + b, List(1, 2), Some(1), None, Lone, BigInt(3), java.math.BigInteger.ONE,
      new java.lang.StringBuilder("sb"), Rock(), null)
    for v <- values do println(describe(v))

    // Boxes are instances of their own class only, and of Number/Comparable/Serializable
    p("1 isInstanceOf Int, Long, Integer")(s"${any(1).isInstanceOf[Int]} ${any(1).isInstanceOf[Long]} ${any(1).isInstanceOf[Integer]}")
    p("1L isInstanceOf Int, Long")(s"${any(1L).isInstanceOf[Int]} ${any(1L).isInstanceOf[Long]}")
    p("'a' isInstanceOf Char, Int")(s"${any('a').isInstanceOf[Char]} ${any('a').isInstanceOf[Int]}")
    p("1.0f isInstanceOf Float, Double")(s"${any(1.0f).isInstanceOf[Float]} ${any(1.0f).isInstanceOf[Double]}")
    p("boxes are Numbers")(List[Any](1, 1L, 1.0, 1.0f, 1.toByte, 1.toShort, 'c', true).map(_.isInstanceOf[Number]))
    p("boxes are Comparable")(List[Any](1, 1L, 1.0, 'c', true, "s", ()).map(_.isInstanceOf[Comparable[?]]))
    p("boxes are Serializable")(List[Any](1, 'c', true, "s", Array(1)).map(_.isInstanceOf[java.io.Serializable]))
    p("() isInstanceOf Unit, BoxedUnit")(s"${any(()).isInstanceOf[Unit]} ${any(()).isInstanceOf[scala.runtime.BoxedUnit]}")
    p("String is CharSequence, Comparable, AnyRef")(s"${any("s").isInstanceOf[CharSequence]} ${any("s").isInstanceOf[Comparable[?]]} ${any("s").isInstanceOf[AnyRef]}")
    p("1 isInstanceOf AnyRef, Any, AnyVal-boxed")(s"${any(1).isInstanceOf[AnyRef]} ${any(1).isInstanceOf[Any]}")
    p("Comparable compareTo through the interface")(s"${any(3).asInstanceOf[Comparable[Any]].compareTo(5)} ${any("b").asInstanceOf[Comparable[Any]].compareTo("a")} ${any(2.5).asInstanceOf[Comparable[Any]].compareTo(2.5)}")
    p("Number methods through the interface")(List[Any](300, -1L, 2.75, 1.5f, (-3).toByte, 1000.toShort).map(v => { val n = v.asInstanceOf[Number]; s"${n.intValue}/${n.longValue}/${n.doubleValue}/${n.byteValue}" }))
    p("CharSequence through the interface")({ val cs = any("hello").asInstanceOf[CharSequence]; s"${cs.length} ${cs.charAt(1)} ${cs.subSequence(1, 3)}" })

    // Type arguments are not checked
    p("List(1) isInstanceOf List[String]")(any(List(1)).isInstanceOf[List[String]])
    p("Box(1) isInstanceOf Box[String]")(any(Box(1)).isInstanceOf[Box[String]])
    p("unchecked cast then use")(Box(1).asInstanceOf[Box[String]].value.asInstanceOf[Any])
    p("unchecked cast element access")(scala.util.Try(any(List(1)).asInstanceOf[List[String]].head.length).failed.get.getClass.getName)

    // null
    p("null isInstanceOf String, Any, AnyRef")(s"${any(null).isInstanceOf[String]} ${any(null).isInstanceOf[AnyRef]} ${(null: Any).isInstanceOf[Dog]}")
    p("null asInstanceOf Int, Long, Double, Boolean, Char")(s"${any(null).asInstanceOf[Int]} ${any(null).asInstanceOf[Long]} ${any(null).asInstanceOf[Double]} ${any(null).asInstanceOf[Boolean]} ${any(null).asInstanceOf[Char].toInt}")
    p("null asInstanceOf String, Dog")(s"${any(null).asInstanceOf[String]} ${any(null).asInstanceOf[Dog]}")
    def unbox[T](x: Any): T = x.asInstanceOf[T]
    p("generic unbox of null to Int")(unbox[Int](null) + 1)

    // Casts that succeed
    p("Puppy as Dog, Animal, Pet")({ val x = any(Puppy("p")); s"${x.asInstanceOf[Dog].name} ${x.asInstanceOf[Animal].name} ${x.asInstanceOf[Pet] != null}" })
    p("boxed Int as Int")(any(41).asInstanceOf[Int] + 1)
    p("boxed Char as Char")(any('x').asInstanceOf[Char])
    p("Array[Int] as Array[Int]")(any(Array(1, 2)).asInstanceOf[Array[Int]].sum)
    p("String array as Array[AnyRef]")(any(Array("a", "b")).asInstanceOf[Array[AnyRef]].length)
    p("Function1 as Function1[Int, Int]")(any((x: Int) => x * 2).asInstanceOf[Int => Int](21))

    // Casts that fail
    p("Cat as Dog")(any(Cat("c")).asInstanceOf[Dog].name)
    p("Dog as Puppy")(any(Dog("d")).asInstanceOf[Puppy].name)
    p("String as Dog")(any("s").asInstanceOf[Dog])
    p("Rock as Animal")(any(Rock()).asInstanceOf[Animal])
    p("Int as String")(any(1).asInstanceOf[String].length)
    p("Int as Long (unboxing)")(any(1).asInstanceOf[Long])
    p("Long as Int (unboxing)")(any(1L).asInstanceOf[Int])
    p("Double as Float")(any(1.0).asInstanceOf[Float])
    p("Char as Int")(any('a').asInstanceOf[Int])
    p("String as Int")(any("1").asInstanceOf[Int])
    p("Int as Boolean")(any(1).asInstanceOf[Boolean])
    p("Array[Int] as Array[Long]")(any(Array(1)).asInstanceOf[Array[Long]].length)
    p("Array[Int] as Array[AnyRef]")(any(Array(1)).asInstanceOf[Array[AnyRef]].length)
    p("String array as Array[Int]")(any(Array("a")).asInstanceOf[Array[Int]].length)
    p("Int as Comparable then String")(any(any(1).asInstanceOf[Comparable[?]]).asInstanceOf[String])
    p("Integer as Number as Long")(any(Integer.valueOf(1)).asInstanceOf[java.lang.Long])
    p("List as Option")(any(List(1)).asInstanceOf[Option[Int]])
    p("Some as None.type")(any(Some(1)).asInstanceOf[None.type])
    p("ClassCastException is a RuntimeException")(try { any(Cat("c")).asInstanceOf[Dog]; "no" } catch case e: RuntimeException => "caught as RuntimeException")

    // Type patterns on sealed hierarchies, with guards and alternatives
    def area(s: Shape): Double = s match
      case Circle(r) if r <= 0 => 0
      case Circle(r)           => 3 * r * r
      case Square(side)        => side * side
    p("areas")(List(Circle(1), Circle(-1), Square(2)).map(area))
    def kind(x: Any): String = x match
      case _: Int | _: Long     => "integral"
      case _: Float | _: Double => "floating"
      case _: (String | Char)   => "text"
      case _                    => "other"
    p("alternatives")(List[Any](1, 2L, 3.0f, 4.0, "s", 'c', true).map(kind))

    // Classes of values
    p("getClass.getName of boxes")(List[Any](1, 1L, 1.0, 1.0f, 'c', 1.toByte, 1.toShort, true, ()).map(_.getClass.getName))
    p("getClass.getName of primitive arrays")(List[Any](Array(1), Array(1L), Array(1.0), Array(1.0f), Array('c'), Array(1.toByte), Array(1.toShort), Array(true)).map(_.getClass.getName))
    p("classOf primitives")(List(classOf[Int], classOf[Long], classOf[Double], classOf[Boolean], classOf[Char], classOf[Unit]).map(_.getName))
    p("classOf boxes and String")(List(classOf[Integer], classOf[String], classOf[java.lang.Character]).map(_.getName))
    p("getClass of classes")(List[Any](Puppy("p"), Box(1), Lone, Circle(1), "s", List(1), Nil, None).map(_.getClass.getName))
    p("getSimpleName")(List[Any](Puppy("p"), Lone, "s", 1, Array(1)).map(_.getClass.getSimpleName))
    p("same class")(s"${Dog("a").getClass == Dog("b").getClass} ${Puppy("a").getClass == Dog("b").getClass} ${"a".getClass eq "b".getClass}")
    p("classOf[Int] vs Integer")(s"${classOf[Int] == classOf[Integer]} ${any(1).getClass == classOf[Integer]}")
    p("isAssignableFrom")(s"${classOf[Animal].isAssignableFrom(classOf[Puppy])} ${classOf[Puppy].isAssignableFrom(classOf[Dog])} ${classOf[Number].isAssignableFrom(classOf[Integer])}")
    p("never-instantiated classes and traits")(s"${any("s").isInstanceOf[NeverInstantiated]} ${any(Rock()).isInstanceOf[NeverImplemented]} ${any(null).asInstanceOf[NeverInstantiated]}")
    p("isArray, isPrimitive")(s"${Array(1).getClass.isArray} ${"s".getClass.isArray} ${classOf[Int].isPrimitive} ${classOf[Integer].isPrimitive}")
    p("getComponentType")(Array(1).getClass.getComponentType.getName)
