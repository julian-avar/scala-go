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

  def d(x: Double): Double = x

  def main(args: Array[String]): Unit =
    // Double.NaN is Go's math.NaN(), whose payload is 1. JVM: 9221120237041090560 (0x7ff8000000000000)
    attempt("doubleToRawLongBits(Double.NaN)")(java.lang.Double.doubleToRawLongBits(d(Double.NaN)))

    // scala.math.BigDecimal(Double), and BigDecimal == Double, call new java.math.BigDecimal(String,
    // MathContext); the generated MathContext lacks the goBits method that native calls. (The
    // jdk-bigdecimal case, which uses MathContext itself, is not affected.) JVM: 1.5, true
    attempt("BigDecimal(1.5)")(BigDecimal(d(1.5)))
    attempt("BigDecimal(\"1.5\") == 1.5")(BigDecimal("1.5") == d(1.5))

    // BoxedUnit's hash code is an identity hash. JVM: true, true
    val unit: Any = ()
    attempt("().## == 0")(unit.## == 0)
    attempt("().hashCode == 0")(unit.hashCode == 0)

    // initCause overwrites a cause set before. JVM: throws java.lang.IllegalStateException:
    // Can't overwrite cause with java.lang.RuntimeException
    val withCause = Exception("e")
    withCause.initCause(IllegalStateException("first"))
    attempt("initCause twice")(withCause.initCause(RuntimeException()).getCause)

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

    // intern does not return the literal. JVM: true
    val built = new StringBuilder("lit").append("eral").toString
    attempt("built.intern eq \"literal\"")(built.intern() eq "literal")
