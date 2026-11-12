object Main {
  def fib(n: Int): Int = if (n < 2) n else fib(n - 1) + fib(n - 2)

  def main(args: Array[String]): Unit = println(fib(30))
}
