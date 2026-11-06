import scala.annotation.tailrec

object Main {
  @tailrec def gcd(a: Long, b: Long): Long = if (b == 0) a else gcd(b, a % b)

  def classify(n: Int): String =
    if (n < 0) "negative"
    else if (n == 0) "zero"
    else if (n % 2 == 0) "even"
    else "odd"

  def firstOver(limit: Int): Int = {
    var i = 1
    while (true) {
      if (i * i > limit) return i
      i += 1
    }
    -1
  }

  def sideEffect(log: Counter, v: Int): Int = { log.add(v); v }

  def main(args: Array[String]): Unit = {
    println(gcd(1071, 462))
    println(classify(-3) + " " + classify(0) + " " + classify(4) + " " + classify(7))
    println(firstOver(50))
    val log = new Counter
    // Operands must be evaluated left to right even when one needs statements.
    val r = sideEffect(log, 1) + (if (log.total > 0) sideEffect(log, 10) else 0)
    println(r + " " + log.total)
    val t = true
    val f = false
    println("" + (t && !f) + " " + (f || t) + " " + (t & f) + " " + (t ^ f))
    val x = { val a = 3; val b = 4; a * b }
    println(x)
  }
}

class Counter {
  var total = 0
  def add(v: Int): Unit = total += v
}
