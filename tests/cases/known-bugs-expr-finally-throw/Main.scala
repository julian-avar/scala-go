// Known bug: a `finally` that always throws, after a `try` that always throws, generates Go code
// that go vet rejects (unreachable code), like `try throw e finally return x`
// (known-bugs-finally-return). On the JVM this prints `from finally`. When fixed, this case fails:
// replace go-errors.txt by expected.txt (and remove go-only.txt), and remove the bug from the
// roadmap.
object Main:
  def f(): Int =
    try throw IllegalStateException("from try")
    finally throw UnsupportedOperationException("from finally")

  def main(args: Array[String]): Unit =
    try println(f())
    catch case e: UnsupportedOperationException => println(e.getMessage)
