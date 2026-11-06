object Main {
  def main(args: Array[String]): Unit = {
    val big = Int.MaxValue
    println(big + 1)
    println(-7 / 2)
    println(-7 % 2)
    println(1 << 33)
    println(-16 >> 2)
    println(-16 >>> 28)
    println(1L << 40)
    println(Long.MinValue)
    println(7.5 % 2)
    println(1.0 / 3)
    println(1e7)
    println(0.1f + 0.2f)
    println(100.0)
    println(3.9.toInt + " " + (-3.9).toInt + " " + Double.NaN.toInt + " " + 1e20.toLong)
    println('a' + 1)
    println(('a' + 1).toChar)
    println((300).toByte)
    println(1 == 1L)
    val boxed: Any = 42
    println(boxed == 42L)
    println("len " + "héllo".length)
    // A constant zero divisor is an ArithmeticException at run time, after the dividend.
    def divide(f: => Int): String = try f.toString catch case e: ArithmeticException => e.getMessage
    println(divide(1 / 0) + " " + divide(7 % 0) + " " + divide({ print("dividend "); 3 } / 0))
    println(scala.util.Try(5L / 0L))
  }
}
