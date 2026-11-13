// Known bugs in expressions and data, found by the expr-* and data-* cases: each line prints what
// scala-go does today; the comment says what the JVM does. Fixing a bug makes this case fail:
// move the check to the expr-* or data-* case it came from, and remove the bug from the roadmap.
object Main:
  def attempt(what: String)(body: => Any): Unit =
    val result =
      try String.valueOf(body)
      catch case e: Throwable => s"throws ${e.getClass.getName}${Option(e.getMessage).fold("")(": " + _)}"
    println(s"$what: $result")

  def main(args: Array[String]): Unit =

    // transpose of an array of arrays needs the element class of the rows, which arrays of
    // references do not record. JVM: List(List(1, 3), List(2, 4))
    attempt("Array(Array(1, 2), Array(3, 4)).transpose")(Array(Array(1, 2), Array(3, 4)).transpose.map(_.toList).toList)

