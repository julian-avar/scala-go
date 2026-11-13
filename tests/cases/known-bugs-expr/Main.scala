// Known bugs in expressions and data, found by the expr-* and data-* cases: each line prints what
// scala-go does today; the comment says what the JVM does. Fixing a bug makes this case fail:
// move the check to the expr-* or data-* case it came from, and remove the bug from the roadmap.
abstract class NeverInstantiated
trait NeverImplemented
final class Stone

object Main:
  def attempt(what: String)(body: => Any): Unit =
    val result =
      try String.valueOf(body)
      catch case e: Throwable => s"throws ${e.getClass.getName}${Option(e.getMessage).fold("")(": " + _)}"
    println(s"$what: $result")

  def main(args: Array[String]): Unit =
    // BoxedUnit's hash code is an identity hash. JVM: true, true
    val unit: Any = ()
    attempt("().## == 0")(unit.## == 0)
    attempt("().hashCode == 0")(unit.hashCode == 0)

    // transpose of an array of arrays needs the element class of the rows, which arrays of
    // references do not record. JVM: List(List(1, 3), List(2, 4))
    attempt("Array(Array(1, 2), Array(3, 4)).transpose")(Array(Array(1, 2), Array(3, 4)).transpose.map(_.toList).toList)

    // Casts to a class never instantiated, or to a trait no instantiated class implements, let any
    // value through when the operand's static type is Any (the spec says only null passes).
    // JVM: throws java.lang.ClassCastException (each)
    def anyOf(x: Any): Any = x
    val one: Any = 1
    val str: Any = "s"
    attempt("1 asInstanceOf NeverInstantiated")(one.asInstanceOf[NeverInstantiated] != null)
    attempt("1 asInstanceOf NeverImplemented")(one.asInstanceOf[NeverImplemented] != null)
    attempt("\"s\" asInstanceOf NeverInstantiated")(str.asInstanceOf[NeverInstantiated] != null)
    attempt("Stone() asInstanceOf NeverImplemented")(anyOf(Stone()).asInstanceOf[NeverImplemented] != null)
    attempt("Stone() asInstanceOf NeverInstantiated")(anyOf(Stone()).asInstanceOf[NeverInstantiated].hashCode == 0)
