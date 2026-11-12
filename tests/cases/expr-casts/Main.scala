// Casts to types that strings and boxes implement are checked, as on the JVM; so are casts to
// classes that are never instantiated.
abstract class NeverInstantiated
trait NeverImplemented
final class Stone

object Main:
  final class Plain(val n: Int)

  def attempt(what: String)(body: => Any): Unit =
    try println(s"$what: ${body}")
    catch case e: ClassCastException => println(s"$what: ClassCastException")

  def main(args: Array[String]): Unit =
    val values: List[Any] = List("text", 42, 'c', 2.5, BigInt(7), new Plain(1), null)
    for v <- values do
      val name = if v == null then "null" else v.getClass.getSimpleName
      attempt(s"$name as Comparable")(v.asInstanceOf[Comparable[?]] != null)
      attempt(s"$name as CharSequence")(v.asInstanceOf[CharSequence] != null)
      attempt(s"$name as Number")(v.asInstanceOf[Number] != null)
    attempt("sort plain objects")(java.util.Arrays.asList(new Plain(2), new Plain(1)).stream().sorted().count())
    attempt("arrays are Serializable")(Array(1, 2).asInstanceOf[java.io.Serializable] != null)
    attempt("arrays are Cloneable")(Array("a").asInstanceOf[Cloneable] != null)

    // Operands whose static type is a String, an array or a BigInteger (Go pointers).
    val text = "text"
    val ints = Array(1)
    val big = java.math.BigInteger.ONE
    attempt("String as Integer")(text.asInstanceOf[Integer] != null)
    attempt("String as Number")(text.asInstanceOf[Number] != null)
    attempt("String as Comparable")(text.asInstanceOf[Comparable[?]] != null)
    attempt("array as Integer")(ints.asInstanceOf[Integer] != null)
    attempt("BigInteger as Long")(big.asInstanceOf[java.lang.Long] != null)
    attempt("BigInteger as Number")(big.asInstanceOf[Number] != null)

    // No value is an instance of a class never instantiated, or of a trait no instantiated class
    // implements: only null passes.
    val anyValues: List[Any] = List(1, "s", Stone(), null)
    for v <- anyValues do
      val name = if v == null then "null" else v.getClass.getSimpleName
      attempt(s"$name as NeverInstantiated")(v.asInstanceOf[NeverInstantiated] == null)
      attempt(s"$name as NeverImplemented")(v.asInstanceOf[NeverImplemented] == null)
