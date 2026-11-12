// Known bug: a `throw` expression as an argument followed by other arguments generates Go code
// that does not parse (`syntax error: unexpected comma, expected expression`). On the JVM this
// prints `caught x`. When fixed, this case fails: replace go-errors.txt by expected.txt (and
// remove go-only.txt), and remove the bug from the roadmap.
object Main:
  def pair(a: String, b: Int): String = a + b

  def main(args: Array[String]): Unit =
    try println(pair(throw IllegalStateException("x"), 1))
    catch case e: IllegalStateException => println(s"caught ${e.getMessage}")
