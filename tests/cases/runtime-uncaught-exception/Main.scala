object Main {
  def fail(n: Int): Unit =
    if (n == 0) throw new IllegalStateException("boom", new RuntimeException("cause"))
    else fail(n - 1)

  def main(args: Array[String]): Unit = {
    println("before")
    fail(2)
  }
}
