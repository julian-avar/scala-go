/** JVM numeric semantics on operands known only at run time, where Go's own rules differ or leave
 *  the result to the implementation (see the specification's numeric operations): shift counts
 *  (Go does not mask them and panics on negative ones), out-of-range float-to-integer conversions
 *  (implementation-dependent in Go), division by zero, and the special floating-point values (Go
 *  constants cannot denote them). The constant forms are folded by the Scala compiler; these are not.
 */
object Main:
  def attempt(label: String)(body: => Any): Unit =
    val r = try String.valueOf(body) catch case e: ArithmeticException => s"ArithmeticException: ${e.getMessage}"
    println(s"$label: $r")

  def main(args: Array[String]): Unit =
    val n = args.length // 0, unknown to the compiler
    attempt("1 << -1") { 1 << (n - 1) }
    attempt("1 << 33") { 1 << (n + 33) }
    attempt("1L << 65") { 1L << (n + 65) }
    attempt("-8 >> 33") { -8 >> (n + 33) }
    attempt("-8 >>> 33") { -8 >>> (n + 33) }
    attempt("-8L >>> 65") { -8L >>> (n + 65) }
    val big = 1e20 * (n + 1)
    attempt("1e20.toInt") { big.toInt }
    attempt("-1e20.toInt") { (-big).toInt }
    attempt("1e20.toLong") { big.toLong }
    attempt("1e20.toChar") { big.toChar.toInt }
    attempt("1e20f.toShort") { big.toFloat.toShort }
    val nan = big * 0 * Double.PositiveInfinity
    attempt("NaN.toInt") { nan.toInt }
    attempt("NaN.toLong") { nan.toLong }
    attempt("NaN == NaN") { nan == nan }
    attempt("NaN < 1") { nan < 1 }
    attempt("NaN.equals(NaN)") { (nan: Any).equals(nan) }
    attempt("Long.MaxValue.toFloat") { (Long.MaxValue - n).toFloat }
    attempt("Int.MinValue / -1") { (Int.MinValue + n) / (-1 - n) }
    attempt("Long.MinValue % -1") { (Long.MinValue + n) % (-1L - n) }
    attempt("1 / 0") { 1 / n }
    attempt("1L % 0") { 1L % n }
    attempt("1.0 / 0") { 1.0 / n }
    attempt("-1.0 / 0") { -1.0 / n }
    attempt("0.0 / 0") { 0.0 / n }
    attempt("-(0.0)") { -(n.toDouble) }
    attempt("-(0.0) == 0.0") { -(n.toDouble) == 0.0 }
    attempt("-5.5 % 2") { (n - 5.5) % 2 }
    attempt("5.5 % 0") { (n + 5.5) % n }
    attempt("300.toByte") { (n + 300).toByte }
    attempt("65535.toShort") { (n + 65535).toShort }
    attempt("-1.toChar") { (n - 1).toChar.toInt }
    attempt("(-1: Byte).toChar") { (n - 1).toByte.toChar.toInt }
