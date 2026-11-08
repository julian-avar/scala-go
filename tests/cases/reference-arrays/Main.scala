import scala.reflect.ClassTag

trait Shape
class Circle extends Shape
final class Unit2 extends Circle

object Main:
  def mk[T: ClassTag](n: Int): Array[T] = new Array[T](n)
  def main(args: Array[String]): Unit =
    println(mk[String](2).toList)
    println((Array("x") ++ Array("y")).toList)
    println(classOf[CharSequence].isAssignableFrom(classOf[String]))
    // Classes of the program: the linker gives the runtime their hierarchy.
    println(List(
      classOf[Shape].isAssignableFrom(classOf[Unit2]), classOf[Circle].isAssignableFrom(classOf[Shape]),
      classOf[Circle].isAssignableFrom(new Unit2().getClass), classOf[Seq[?]].isAssignableFrom(List(1).getClass),
      classOf[Iterable[?]].isAssignableFrom(classOf[Map[?, ?]]), classOf[String].isAssignableFrom(classOf[Circle])))
    println(Array("b", "a").sorted.toList)
    println(Array("b", "a").toIndexedSeq)
    println(Array.copyOf(Array("q"), 2).toList)
    println(Array.copyOf(Array((), ()), 3).toList)
    println(Array[Unit]((), ()).sorted(using Ordering.Unit).toList)
    println(Array(1, 2) ++ Array(3) sameElements Array(1, 2, 3))
