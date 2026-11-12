import java.lang.{Double as JDouble, Float as JFloat}

// Floating point (docs/_docs/spec/language.md, "Numeric operations"): IEEE arithmetic on Float
// and Double, special values, %, conversions to and from every primitive type with edge values,
// comparison by value and by bits. Operands pass through `d`/`f` so that the compiler cannot
// fold them.
object Main:
  def d(x: Double): Double = x
  def f(x: Float): Float = x
  def i(x: Int): Int = x
  def l(x: Long): Long = x

  def p(what: String)(body: => Any): Unit = println(s"$what: ${String.valueOf(body)}")

  val NaN = d(Double.NaN)
  val Inf = d(Double.PositiveInfinity)
  val doubles = List(0.0, -0.0, 0.5, -0.5, 1.5, -1.5, 2.5, 0.9999999999999999, 2147483647.0, 2147483647.5,
    2147483648.0, -2147483648.0, -2147483649.0, 9.223372036854776E18, -9.223372036854776E18, 1e20, -1e20,
    300.7, -300.7, 65535.9, 65536.0, 32767.9, -32769.0, Double.MinPositiveValue, Double.MaxValue,
    Double.PositiveInfinity, Double.NegativeInfinity, Double.NaN)

  def main(args: Array[String]): Unit =
    // Arithmetic and rounding
    p("0.1 + 0.2")(d(0.1) + d(0.2))
    p("0.1f + 0.2f")(f(0.1f) + f(0.2f))
    p("1.0 / 3")(d(1.0) / 3)
    p("1.0f / 3")(f(1.0f) / 3)
    p("2.0 / 3 (float vs double)")(s"${f(2.0f) / 3} ${d(2.0) / 3}")
    p("1e308 * 10")(d(1e308) * 10)
    p("-1e308 * 10")(d(-1e308) * 10)
    p("3e38f * 10")(f(3e38f) * 10)
    p("MinPositive / 2")(d(Double.MinPositiveValue) / 2)
    p("Float.MinPositive / 2")(f(Float.MinPositiveValue) / 2)
    p("1e-320 (subnormal)")(d(1e-320) * 1)
    p("1.0 + 1e-16")(d(1.0) + 1e-16)
    p("(1L << 53) + 1.0")(l(1L << 53).toDouble + 1.0)
    p("16777216f + 1f")(f(16777216f) + 1f)
    p("0.1 * 3")(d(0.1) * 3)
    p("100.0 * 1.1")(d(100.0) * 1.1)
    // Rounded twice, not fused into one multiply-add (as Go may on arm64 and amd64 v3)
    p("0.1 * 10 - 1")(d(0.1) * d(10) - d(1))
    p("0.1f * 10 - 1")(f(0.1f) * f(10) - f(1))
    p("x * x - y * y")({ val x = d(1 + 1e-8); val y = d(1); x * x - y * y })
    p("0.1 * 10 in a val, - 1")({ val p = d(0.1) * d(10); p - d(1) })
    p("Double.MaxValue + Double.MaxValue")(d(Double.MaxValue) + Double.MaxValue)
    p("-(0.0)")(-d(0.0))
    p("0.0 * -1")(d(0.0) * -1)
    p("-0.0 + 0.0")(d(-0.0) + 0.0)
    p("-0.0 - 0.0")(d(-0.0) - 0.0)

    // Division by zero and special values
    p("1.0 / 0, -1.0 / 0, 0.0 / 0")(s"${d(1.0) / 0} ${d(-1.0) / 0} ${d(0.0) / 0}")
    p("1.0 / -0.0")(d(1.0) / d(-0.0))
    p("1f / 0, 0f / 0")(s"${f(1f) / 0} ${f(0f) / 0}")
    p("Inf - Inf, Inf * 0, Inf / Inf")(s"${Inf - Inf} ${Inf * 0} ${Inf / Inf}")
    p("Inf + 1, -Inf * -1")(s"${Inf + 1} ${-Inf * -1}")
    p("NaN + 1, NaN * 0")(s"${NaN + 1} ${NaN * 0}")
    p("1 / Inf, -1 / Inf")(s"${1 / Inf} ${-1 / Inf}")

    // Floating remainder: sign of the dividend; x % 0 is NaN
    for (x, y) <- List((5.5, 2.0), (-5.5, 2.0), (5.5, -2.0), (-5.5, -2.0), (1.0, 0.1), (7.0, Double.PositiveInfinity),
      (Double.PositiveInfinity, 2.0), (0.0, 1.0), (-0.0, 1.0), (5.0, 0.0), (1e300, 3.0)) do
      p(s"$x % $y")(d(x) % d(y))
    p("5.5f % 2f")(f(5.5f) % f(2f))
    p("-7.25f % 2f")(f(-7.25f) % f(2f))

    // Comparisons: IEEE for ==, <; by bits for equals and compare
    p("NaN == NaN, NaN != NaN")(s"${NaN == NaN} ${NaN != NaN}")
    p("NaN < 1, NaN > 1, NaN <= NaN")(s"${NaN < 1} ${NaN > 1} ${NaN <= NaN}")
    p("-0.0 == 0.0, -0.0 < 0.0")(s"${d(-0.0) == 0.0} ${d(-0.0) < 0.0}")
    p("equals: NaN, -0.0")(s"${JDouble.valueOf(NaN).equals(NaN)} ${JDouble.valueOf(-0.0).equals(d(0.0))}")
    p("Double.compare")(s"${JDouble.compare(d(-0.0), 0.0)} ${JDouble.compare(NaN, Inf)} ${JDouble.compare(NaN, NaN)} ${JDouble.compare(1.0, 2.0)}")
    p("Float.compare")(s"${JFloat.compare(f(-0.0f), 0.0f)} ${JFloat.compare(Float.NaN, Float.PositiveInfinity)}")
    p("compareTo via RichDouble")(s"${d(1.0).compare(NaN)} ${d(-0.0).compare(0.0)}")
    p("sorted with NaN and -0.0")(List(NaN, 1.0, -0.0, Double.NegativeInfinity, 0.0, -1.0).sorted)
    p("Array sorted with NaN and -0.0")(Array(NaN, 1.0, 0.0, -0.0, -1.0).sorted.mkString(","))
    p("java.util.Arrays.sort")({ val a = Array(NaN, 1.0, 0.0, -0.0); java.util.Arrays.sort(a); a.mkString(",") })
    p("max/min with NaN")(s"${math.max(NaN, 1.0)} ${math.min(1.0, NaN)} ${math.max(-0.0, 0.0)} ${math.min(0.0, -0.0)}")
    p("Float max/min")(s"${math.max(f(-0.0f), f(0.0f))} ${math.min(f(Float.NaN), f(1f))}")
    p("isNaN, isInfinite, isFinite")(s"${NaN.isNaN} ${Inf.isInfinite} ${JDouble.isFinite(Inf)} ${JDouble.isFinite(1.0)} ${JFloat.isNaN(f(Float.NaN))}")

    // Bits
    p("doubleToLongBits")(s"${JDouble.doubleToLongBits(d(1.0))} ${JDouble.doubleToLongBits(d(-0.0))} ${JDouble.doubleToLongBits(NaN)}")
    p("longBitsToDouble")(s"${JDouble.longBitsToDouble(l(0x7ff0000000000000L))} ${JDouble.longBitsToDouble(l(1L))} ${JDouble.longBitsToDouble(l(0x3ff8000000000000L))}")
    p("floatToIntBits")(s"${JFloat.floatToIntBits(f(1.0f))} ${JFloat.floatToIntBits(f(-0.0f))} ${JFloat.floatToIntBits(f(Float.NaN))}")
    p("intBitsToFloat")(s"${JFloat.intBitsToFloat(i(0x7f800000))} ${JFloat.intBitsToFloat(i(1))} ${JFloat.intBitsToFloat(i(0x3fc00000))}")
    p("hashCode of 1.5, -0.0, NaN")(s"${d(1.5).hashCode} ${JDouble.hashCode(d(-0.0))} ${JDouble.hashCode(NaN)}")
    p("Float hashCode")(s"${JFloat.hashCode(f(1.5f))} ${JFloat.hashCode(f(-0.0f))}")

    // Double -> integer types: truncation, NaN -> 0, saturation, then narrowing
    for x <- doubles do
      val v = d(x)
      p(s"$x to Int Long Short Byte Char")(s"${v.toInt} ${v.toLong} ${v.toShort} ${v.toByte} ${v.toChar.toInt}")
    for x <- List(0.5f, -1.5f, 3e9f, -3e9f, 1e19f, 9.223372E18f, 65536.5f, Float.NaN, Float.PositiveInfinity, Float.NegativeInfinity) do
      val v = f(x)
      p(s"${x}f to Int Long Short Byte Char")(s"${v.toInt} ${v.toLong} ${v.toShort} ${v.toByte} ${v.toChar.toInt}")

    // Integer -> floating: rounded to nearest (even)
    p("Int.MaxValue.toFloat")(i(Int.MaxValue).toFloat)
    p("Int.MaxValue.toDouble")(i(Int.MaxValue).toDouble)
    p("16777217.toFloat")(i(16777217).toFloat)
    p("16777219.toFloat")(i(16777219).toFloat)
    p("-16777217.toFloat")(i(-16777217).toFloat)
    p("Long.MaxValue.toDouble")(l(Long.MaxValue).toDouble)
    p("Long.MinValue.toDouble")(l(Long.MinValue).toDouble)
    p("Long.MaxValue.toFloat")(l(Long.MaxValue).toFloat)
    p("(1L << 53) + 1 toDouble")(l((1L << 53) + 1).toDouble)
    p("(1L << 53) + 3 toDouble")(l((1L << 53) + 3).toDouble)
    p("Long.MaxValue.toDouble.toLong")(l(Long.MaxValue).toDouble.toLong)
    p("'a'.toFloat, 'a'.toDouble")(s"${'a'.toFloat} ${'a'.toDouble}")
    p("(-128: Byte).toFloat")((-128: Byte).toFloat)
    p("Short.MinValue.toDouble")(Short.MinValue.toDouble)

    // Double <-> Float
    p("0.1.toFloat")(d(0.1).toFloat)
    p("0.1f.toDouble")(f(0.1f).toDouble)
    p("1e40.toFloat, -1e40.toFloat")(s"${d(1e40).toFloat} ${d(-1e40).toFloat}")
    p("1e-50.toFloat, -1e-50.toFloat")(s"${d(1e-50).toFloat} ${d(-1e-50).toFloat}")
    p("NaN.toFloat")(NaN.toFloat)
    p("Double.MaxValue.toFloat")(d(Double.MaxValue).toFloat)
    p("3.4028235677973366E38.toFloat (rounds to max)")(d(3.4028235677973366E38).toFloat)
    p("1.0000000596046448.toFloat (ties to even)")(d(1.0000000596046448).toFloat)
    p("1.0000001788139343.toFloat (ties to even)")(d(1.0000001788139343).toFloat)
    p("Float.MinPositiveValue.toDouble")(f(Float.MinPositiveValue).toDouble)
    p("Float.MaxValue.toDouble")(f(Float.MaxValue).toDouble)
    p("Float + Double promotes")(f(0.1f) + d(0.1))
    p("Int + Float promotes")(i(16777217) + f(0f))
    p("Long + Double promotes")(l(Long.MaxValue) + d(0.0))

    // Mixed comparisons promote too
    p("16777217 == 16777217f")(i(16777217) == f(16777217f))
    p("0.1f == 0.1")(f(0.1f) == d(0.1))
    p("0.5f == 0.5")(f(0.5f) == d(0.5))
    p("Long.MaxValue == 9.223372036854775807E18")(l(Long.MaxValue) == d(9.223372036854775807e18))
    p("1 == 1.0, 'a' == 97.0")(s"${i(1) == d(1.0)} ${'a' == d(97.0)}")

    // java.lang.Math on exact cases
    p("abs")(s"${math.abs(d(-0.0))} ${math.abs(d(-Inf))} ${math.abs(f(-1.5f))} ${math.abs(NaN)}")
    p("floor, ceil")(s"${math.floor(d(-1.5))} ${math.ceil(d(-1.5))} ${math.floor(d(-0.0))} ${math.ceil(d(-0.5))} ${math.floor(NaN)}")
    p("rint")(s"${math.rint(d(0.5))} ${math.rint(d(1.5))} ${math.rint(d(2.5))} ${math.rint(d(-2.5))} ${math.rint(d(-0.4))}")
    p("round")(s"${math.round(d(0.5))} ${math.round(d(-0.5))} ${math.round(d(2.5))} ${math.round(d(-2.5))} ${math.round(f(1.5f))} ${math.round(NaN)} ${math.round(d(1e20))} ${math.round(-Inf)}")
    p("signum")(s"${math.signum(d(-3.0))} ${math.signum(d(-0.0))} ${math.signum(NaN)} ${math.signum(f(2f))}")
    p("sqrt")(s"${math.sqrt(d(2.0))} ${math.sqrt(d(-1.0))} ${math.sqrt(d(-0.0))} ${math.sqrt(Inf)} ${math.sqrt(d(1e-320))}")
    p("Math.clamp")(s"${Math.clamp(l(5L), i(1), i(3))} ${Math.clamp(l(-5L), i(1), i(3))}")
    p("nextUp, nextDown")(s"${Math.nextUp(d(1.0))} ${Math.nextDown(d(1.0))}")
    p("hypot 3 4")(math.hypot(d(3.0), d(4.0)))
    p("pow exact cases")(s"${math.pow(d(2.0), 10)} ${math.pow(d(2.0), -1)} ${math.pow(NaN, 0)} ${math.pow(d(0.0), -1)} ${math.pow(d(-8.0), 1.0 / 3)}")
    p("toRadians 180")(math.toDegrees(math.Pi))

    // Constants
    p("Double constants")(s"${Double.MaxValue} ${Double.MinValue} ${Double.MinPositiveValue} ${JDouble.MIN_NORMAL}")
    p("Float constants")(s"${Float.MaxValue} ${Float.MinValue} ${Float.MinPositiveValue} ${JFloat.MIN_NORMAL}")
    p("Double.MAX_EXPONENT, SIZE")(s"${JDouble.MAX_EXPONENT} ${JDouble.MIN_EXPONENT} ${JDouble.SIZE} ${JFloat.BYTES}")
