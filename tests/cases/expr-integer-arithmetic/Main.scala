// Integer arithmetic (docs/_docs/spec/language.md, "Numeric operations"): wrapping, truncating
// division, shifts with masked counts, narrowing conversions, Char arithmetic, and the boxes'
// static helpers. Operands pass through `i`/`l`/... so that the compiler cannot fold them; the
// folded forms are printed next to them where they differ in kind.
object Main:
  def i(x: Int): Int = x
  def l(x: Long): Long = x
  def b(x: Byte): Byte = x
  def s(x: Short): Short = x
  def c(x: Char): Char = x

  def attempt(what: String)(body: => Any): Unit =
    val r =
      try String.valueOf(body)
      catch case e: ArithmeticException => s"ArithmeticException(${e.getMessage})"
    println(s"$what: $r")

  def main(args: Array[String]): Unit =
    // Boundaries and wrapping
    attempt("Int.MaxValue + 1")(i(Int.MaxValue) + 1)
    attempt("Int.MinValue - 1")(i(Int.MinValue) - 1)
    attempt("-Int.MinValue")(-i(Int.MinValue))
    attempt("Int.MaxValue * 2")(i(Int.MaxValue) * 2)
    attempt("Int.MaxValue * Int.MaxValue")(i(Int.MaxValue) * i(Int.MaxValue))
    attempt("65536 * 65536")(i(65536) * i(65536))
    attempt("46341 * 46341")(i(46341) * i(46341))
    attempt("Long.MaxValue + 1")(l(Long.MaxValue) + 1)
    attempt("-Long.MinValue")(-l(Long.MinValue))
    attempt("Long.MinValue * -1")(l(Long.MinValue) * -1)
    attempt("Long.MaxValue * 3")(l(Long.MaxValue) * 3)
    attempt("Int.MaxValue + 1L")(i(Int.MaxValue) + 1L)
    attempt("Int.MaxValue.toLong * 2")(i(Int.MaxValue).toLong * 2)
    attempt("Long.MaxValue.toInt")(l(Long.MaxValue).toInt)
    attempt("0x123456789L.toInt")(l(0x123456789L).toInt)
    attempt("-1L.toInt")(l(-1L).toInt)
    attempt("Int.MinValue.toLong")(i(Int.MinValue).toLong)
    attempt("abs(Int.MinValue)")(math.abs(i(Int.MinValue)))
    attempt("abs(Long.MinValue)")(math.abs(l(Long.MinValue)))
    attempt("~0, ~Int.MaxValue")(s"${~i(0)} ${~i(Int.MaxValue)}")
    attempt("~5L")(~l(5L))

    // Division and remainder: every sign combination, and the edges
    for (x, y) <- List((7, 2), (-7, 2), (7, -2), (-7, -2), (1, 3), (-1, 3), (0, -5)) do
      attempt(s"$x / $y, $x % $y")(s"${i(x) / i(y)} ${i(x) % i(y)}")
    for (x, y) <- List((7L, 2L), (-7L, 2L), (7L, -2L), (-7L, -2L)) do
      attempt(s"${x}L / ${y}L, ${x}L % ${y}L")(s"${l(x) / l(y)} ${l(x) % l(y)}")
    attempt("Int.MinValue / -1")(i(Int.MinValue) / i(-1))
    attempt("Int.MinValue % -1")(i(Int.MinValue) % i(-1))
    attempt("Long.MinValue / -1")(l(Long.MinValue) / l(-1))
    attempt("Long.MinValue % -1")(l(Long.MinValue) % l(-1))
    attempt("Int.MinValue / 2")(i(Int.MinValue) / 2)
    attempt("1 / 0")(i(1) / i(0))
    attempt("1 % 0")(i(1) % i(0))
    attempt("1L / 0L")(l(1L) / l(0L))
    attempt("1L % 0L")(l(1L) % l(0L))
    attempt("0 / 0")(i(0) / i(0))
    attempt("byte / 0")(b(1) / b(0))
    attempt("char % 0")(c('a') % c(0))
    attempt("Math.floorDiv(-7, 2)")(Math.floorDiv(i(-7), i(2)))
    attempt("Math.floorMod(-7, 2)")(Math.floorMod(i(-7), i(2)))
    attempt("Math.floorMod(7, -2)")(Math.floorMod(i(7), i(-2)))
    attempt("Math.floorDiv(-7L, 2L)")(Math.floorDiv(l(-7L), l(2L)))
    attempt("Math.floorMod(-7L, 3L)")(Math.floorMod(l(-7L), l(3L)))
    attempt("Math.floorDiv(1, 0)")(Math.floorDiv(i(1), i(0)))

    // Exact arithmetic
    attempt("addExact(Int.MaxValue, 1)")(Math.addExact(i(Int.MaxValue), 1))
    attempt("subtractExact(Int.MinValue, 1)")(Math.subtractExact(i(Int.MinValue), 1))
    attempt("multiplyExact(65536, 65536)")(Math.multiplyExact(i(65536), i(65536)))
    attempt("multiplyExact(1L << 32, 1L << 31)")(Math.multiplyExact(l(1L << 32), l(1L << 31)))
    attempt("negateExact(Int.MinValue)")(Math.negateExact(i(Int.MinValue)))
    attempt("toIntExact(1L << 31)")(Math.toIntExact(l(1L << 31)))
    attempt("incrementExact(Long.MaxValue)")(Math.incrementExact(l(Long.MaxValue)))
    attempt("addExact(1, 2)")(Math.addExact(i(1), i(2)))
    attempt("multiplyExact(-3L, 4L)")(Math.multiplyExact(l(-3L), l(4L)))

    // Shifts: counts are masked (5 bits for Int, 6 for Long); negative counts too
    for n <- List(0, 1, 31, 32, 33, 63, 64, -1, -31, -32) do
      attempt(s"shifts by $n")(s"${i(1) << i(n)} ${i(-256) >> i(n)} ${i(-256) >>> i(n)} ${l(1L) << i(n)} ${l(-256L) >> i(n)} ${l(-256L) >>> i(n)}")
    attempt("1 << 33L (Long count)")(i(1) << l(33L))
    attempt("Int.MinValue >> 31")(i(Int.MinValue) >> 31)
    attempt("Int.MinValue >>> 31")(i(Int.MinValue) >>> 31)
    attempt("-1 >>> 1")(i(-1) >>> 1)
    attempt("-1L >>> 1")(l(-1L) >>> 1)
    attempt("byte -128 >>> 4")(b(-128) >>> 4)
    attempt("byte -128 >> 4")(b(-128) >> 4)
    attempt("char 0xFFFF >> 4")(c('\uffff') >> 4)
    attempt("short -1 >>> 16")(s(-1) >>> 16)
    attempt("1 << 31")(i(1) << 31)
    attempt("1L << 63")(l(1L) << 63)
    attempt("bit ops")(s"${i(0x0F0F) & i(0x00FF)} ${i(0x0F0F) | i(0x00FF)} ${i(0x0F0F) ^ i(0x00FF)} ${l(-1L) & l(0xFFL)}")

    // Byte, Short, Char: promoted to Int; narrowing wraps
    attempt("byte 127 + 1")(b(127) + 1)
    attempt("(byte 127 + 1).toByte")((b(127) + 1).toByte)
    attempt("byte * byte")(b(-128) * b(-128))
    attempt("short 32767 + 1")((s(32767) + 1).toShort)
    attempt("-(byte -128)")(-b(-128))
    attempt("(-(byte -128)).toByte")((-b(-128)).toByte)
    var bv: Byte = b(120)
    bv = (bv + 10).toByte
    attempt("byte var += 10")(bv)
    var sv: Short = s(32760)
    sv = (sv + 10).toShort
    attempt("short var += 10")(sv)
    for x <- List(127, 128, 255, 256, -129, 300, 65535, 65536, 70000, -1, Int.MinValue) do
      attempt(s"$x to Byte/Short/Char")(s"${i(x).toByte} ${i(x).toShort} ${i(x).toChar.toInt}")
    for x <- List(0x7fffffffffL, -1L, Long.MinValue, 0x80000000L, 0x1234567890abcdefL) do
      attempt(s"${x}L to Int/Short/Byte/Char")(s"${l(x).toInt} ${l(x).toShort} ${l(x).toByte} ${l(x).toChar.toInt}")
    attempt("byte -1 to Char/Int/Long")(s"${b(-1).toChar.toInt} ${b(-1).toInt} ${b(-1).toLong}")
    attempt("short -1 to Char")(s(-1).toChar.toInt)
    attempt("char 0xFFFF to Short/Byte/Int")(s"${c('\uffff').toShort} ${c('\uffff').toByte} ${c('\uffff').toInt}")
    attempt("char 0x8000 to Short")(c('\u8000').toShort)

    // Char arithmetic
    attempt("'a' + 1")(c('a') + 1)
    attempt("('a' + 1).toChar")((c('a') + 1).toChar)
    attempt("'z' - 'a'")(c('z') - c('a'))
    attempt("'a' * 2")(c('a') * 2)
    attempt("'0' + 9 to Char")((c('0') + 9).toChar)
    attempt("0xFFFF + 1 to Char")((c('\uffff') + 1).toChar.toInt)
    attempt("0.toChar - 1")(c(0) - 1)
    var ch = c('x')
    ch = (ch + 1).toChar
    ch = (ch + 1).toChar
    attempt("char var incremented twice")(ch)
    attempt("'a' < 'b'")(c('a') < c('b'))
    attempt("'\\uffff' > 'a'")(c('\uffff') > c('a'))
    attempt("Char range")((c('a') to c('e')).mkString)
    attempt("'A' + 'B' (Int)")(c('A') + c('B'))
    attempt("\"\" + 'A' + 'B'")("" + c('A') + c('B'))

    // Comparisons across widths
    attempt("Int.MaxValue < Long.MaxValue")(i(Int.MaxValue) < l(Long.MaxValue))
    attempt("-1 < 0xFFFFFFFFL")(i(-1) < l(0xFFFFFFFFL))
    attempt("byte -1 == char 0xFFFF")(b(-1) == c('\uffff'))
    attempt("byte -1 == short -1")(b(-1) == s(-1))
    attempt("Int.MinValue compare 0")(i(Int.MinValue).compare(0))
    attempt("Integer.compare / signum")(s"${Integer.compare(i(-5), i(3))} ${Integer.signum(i(-5))} ${java.lang.Long.signum(l(Long.MinValue))}")

    // Static helpers of the boxes (natives)
    attempt("Integer.MIN_VALUE, MAX_VALUE")(s"${Integer.MIN_VALUE} ${Integer.MAX_VALUE}")
    attempt("toHexString(-1)")(Integer.toHexString(i(-1)))
    attempt("toBinaryString(10)")(Integer.toBinaryString(i(10)))
    attempt("toOctalString(-8)")(Integer.toOctalString(i(-8)))
    attempt("Long.toHexString(Long.MinValue)")(java.lang.Long.toHexString(l(Long.MinValue)))
    attempt("Integer.toString(-255, 16)")(Integer.toString(i(-255), 16))
    attempt("Long.toString(Long.MinValue, 36)")(java.lang.Long.toString(l(Long.MinValue), 36))
    attempt("toUnsignedString(-1)")(Integer.toUnsignedString(i(-1)))
    attempt("Long.toUnsignedString(-1L)")(java.lang.Long.toUnsignedString(l(-1L)))
    attempt("divideUnsigned(-1, 2)")(Integer.divideUnsigned(i(-1), i(2)))
    attempt("remainderUnsigned(-1, 7)")(Integer.remainderUnsigned(i(-1), i(7)))
    attempt("compareUnsigned(-1, 1)")(Integer.compareUnsigned(i(-1), i(1)))
    attempt("toUnsignedLong(-1)")(Integer.toUnsignedLong(i(-1)))
    attempt("bitCount")(s"${Integer.bitCount(i(-1))} ${Integer.bitCount(i(0xF0))} ${java.lang.Long.bitCount(l(-1L))}")
    attempt("leading/trailing zeros")(s"${Integer.numberOfLeadingZeros(i(1))} ${Integer.numberOfLeadingZeros(i(0))} ${Integer.numberOfTrailingZeros(i(0))} ${java.lang.Long.numberOfTrailingZeros(l(1L << 40))}")
    attempt("highest/lowestOneBit")(s"${Integer.highestOneBit(i(100))} ${Integer.lowestOneBit(i(100))} ${Integer.highestOneBit(i(-1))}")
    attempt("rotate")(s"${Integer.rotateLeft(i(0x80000001), 1)} ${Integer.rotateRight(i(1), 1)} ${java.lang.Long.rotateLeft(l(1L), 65)}")
    attempt("reverse, reverseBytes")(s"${Integer.reverse(i(1))} ${Integer.reverseBytes(i(0x01020304))}")
    attempt("Integer.parseInt edges")(s"${Integer.parseInt("-2147483648")} ${Integer.parseInt("+7")} ${Integer.parseInt("ff", 16)} ${Integer.parseInt("-1010", 2)}")
    attempt("Long.parseLong(Long.MinValue)")(java.lang.Long.parseLong("-9223372036854775808"))
    attempt("Integer.MAX_VALUE.toString.toInt + 0")("2147483647".toInt)
    attempt("Character.MAX_VALUE")(Character.MAX_VALUE.toInt)
    attempt("Byte/Short MIN, MAX")(s"${Byte.MinValue} ${Byte.MaxValue} ${Short.MinValue} ${Short.MaxValue}")
    attempt("Integer.hashCode / Long.hashCode")(s"${Integer.hashCode(i(-1))} ${java.lang.Long.hashCode(l(-1L))} ${java.lang.Long.hashCode(l(1L << 32))}")
    attempt("Integer.sum, max, min")(s"${Integer.sum(i(Int.MaxValue), 1)} ${Integer.max(i(-1), i(-2))} ${java.lang.Long.min(l(-1), l(-2))}")
    attempt("Math.abs, max, min, signum")(s"${Math.abs(i(-3))} ${Math.max(l(-3), l(5))} ${Math.min(i(-3), i(5))}")
