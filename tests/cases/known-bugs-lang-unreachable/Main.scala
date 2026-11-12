// Known bug: an inline method that always throws for its constant argument, called as the body
// of a `try` whose value a by-name argument returns, generates Go code that go vet rejects
// (an assignment after the panic: unreachable code). On the JVM this prints 0. When fixed, this
// case fails: replace go-errors.txt by expected.txt (and remove go-only.txt), and update the docs.
object Main:
  inline def failing(n: Int): Int =
    if n < 0 then throw IllegalArgumentException("negative")
    n

  def show(body: => Any): Unit = println(body)

  def main(args: Array[String]): Unit =
    show(try failing(-1) catch case _: IllegalArgumentException => 0)
