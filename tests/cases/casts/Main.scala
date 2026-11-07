// Casts to types that strings and boxes implement are checked, as on the JVM.
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
