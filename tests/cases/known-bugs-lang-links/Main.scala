import scala.reflect.TypeTest

// Known bugs: language features that fail to link. On the JVM this prints `JavaStyle`, `High` and
// `List(Num(1), Num(2))`. When one links, this case fails: move the use to a lang-* case that
// runs it, and update the docs.
enum JavaStyle extends java.lang.Enum[JavaStyle]:
  case Low, High

// A user-defined TypeTest: calling TypeTest.unapply makes ClassTag's unapply reachable, which
// needs Class.isInstance.
case class Num(n: Int)
given TypeTest[Any, Num] with
  def unapply(x: Any): Option[x.type & Num] = x match
    case n: (x.type & Num) @unchecked => Some(n)
    case _                            => None
def onlyOf[T](xs: List[Any])(using TypeTest[Any, T]): List[T] = xs.collect { case t: T => t }

object Main:
  def main(args: Array[String]): Unit =
    println(JavaStyle.Low.getDeclaringClass.getName)
    println(java.lang.Enum.valueOf(classOf[JavaStyle], "High"))
    println(onlyOf[Num](List(Num(1), "x", Num(2))))
