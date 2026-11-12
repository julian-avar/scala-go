// Known bug (docs/roadmap.md, M0): `return` in a `finally` after a `throw` generates Go code that
// go vet rejects. On the JVM this prints 1. When fixed, this case fails: replace go-errors.txt by
// expected.txt (and remove go-only.txt), and remove the bug from the roadmap.
object Main:
  def f(): Int =
    try throw IllegalStateException("discarded")
    finally return 1

  def main(args: Array[String]): Unit = println(f())
