import java.math.{MathContext, RoundingMode}
import scala.math.BigDecimal.RoundingMode as SRoundingMode

/** scala.math: BigInt, BigDecimal with MathContexts, and the functions of the math package. */
object Main:
  def section(name: String): Unit = println(s"-- $name")

  def main(args: Array[String]): Unit =
    bigInts()
    bigDecimals()
    mathFunctions()

  def factorial(n: Int): BigInt = (1 to n).foldLeft(BigInt(1))(_ * _)

  def bigInts(): Unit =
    section("BigInt")
    val a = BigInt("123456789012345678901234567890")
    val b = BigInt(987654321)
    println(s"${a + b} ${a - b} ${a * b}")
    println(s"${a / b} ${a % b} ${a /% b} ${-a / b} ${-a % b} ${(-a).mod(b)}")
    println(s"${b.pow(5)} ${BigInt(2).pow(100)} ${BigInt(-3).pow(3)}")
    println(factorial(30))
    println(s"${factorial(100).toString.length} ${factorial(25).bitLength} ${factorial(20).toLong}")
    println(s"${BigInt(48).gcd(BigInt(180))} ${BigInt(-7).abs} ${BigInt(-7).signum} ${BigInt(0).signum} ${-BigInt(5)}")
    println(s"${BigInt(4).modPow(BigInt(13), BigInt(497))} ${BigInt(3).modInverse(BigInt(11))}")
    println(s"${BigInt(255).toString(2)} ${BigInt(255).toString(16)} ${BigInt("-zz", 36)} ${BigInt("ff", 16)}")
    println(s"${BigInt(1) << 70} ${BigInt(-1) << 3} ${(BigInt(1) << 70) >> 68} ${BigInt(-17) >> 2}")
    println(s"${BigInt(12) & BigInt(10)} ${BigInt(12) | BigInt(3)} ${BigInt(12) ^ BigInt(10)} ${~BigInt(5)} ${BigInt(-12) &~ BigInt(5)}")
    println(s"${BigInt(10).testBit(1)} ${BigInt(10).setBit(0)} ${BigInt(10).clearBit(1)} ${BigInt(10).flipBit(3)} ${BigInt(40).lowestSetBit} ${BigInt(255).bitCount}")
    println(s"${BigInt(97).isProbablePrime(20)} ${BigInt(91).isProbablePrime(20)} ${BigInt(2).pow(61) - 1 isProbablePrime 30}")
    println(s"${a.isValidLong} ${b.isValidInt} ${BigInt(Long.MaxValue).isValidLong} ${(BigInt(Long.MaxValue) + 1).isValidLong}")
    println(s"${(BigInt(Long.MaxValue) + 1).toLong} ${BigInt(300).toByte} ${BigInt(70000).toInt} ${a.toDouble} ${a.toFloat}")
    println(s"${a.min(b)} ${a.max(b)} ${a.compare(b)} ${a > b} ${BigInt(5) == 5} ${BigInt(5) == BigInt("5")} ${BigInt(5).equals(5)}")
    println(s"${BigInt(7).hashCode} ${BigInt(Long.MaxValue).hashCode == Long.MaxValue.##} ${a.hashCode == BigInt(a.toString).hashCode}")
    println(s"${BigInt(Array[Byte](1, 0))} ${BigInt(256).toByteArray.toList} ${BigInt(-1).toByteArray.toList}")
    println(s"${BigInt(3).bigInteger.getClass.getName} ${BigInt(java.math.BigInteger.valueOf(-9))}")
    println(s"${BigInt(5).isWhole} ${BigInt(3).until(BigInt(6)).toList} ${List(BigInt(3), BigInt(-1), BigInt(2)).sorted}")
    println(s"${BigInt(java.math.BigInteger.TEN)} ${BigInt(Long.MinValue) - 1}")
    println(try BigInt("12x") catch case e: NumberFormatException => s"NumberFormatException ${e.getMessage}")
    println(try BigInt(1) / 0 catch case e: ArithmeticException => s"ArithmeticException ${e.getMessage}")
    var fibA = BigInt(0)
    var fibB = BigInt(1)
    for _ <- 1 to 300 do
      val t = fibA + fibB
      fibA = fibB
      fibB = t
    println(fibA)

  def bigDecimals(): Unit =
    section("BigDecimal")
    val x = BigDecimal("10.25")
    val y = BigDecimal("3")
    println(s"${x + y} ${x - y} ${x * y} ${x / y} ${x % y} ${x.quot(y)} ${x /% y} ${x.pow(3)}")
    println(s"${x.setScale(1, SRoundingMode.HALF_UP)} ${x.setScale(1, SRoundingMode.HALF_EVEN)} ${x.setScale(0, SRoundingMode.FLOOR)} ${x.setScale(4)}")
    for mode <- SRoundingMode.values.toList if mode != SRoundingMode.UNNECESSARY do
      println(s"$mode: ${List("2.5", "-2.5", "1.15", "-1.15").map(s => BigDecimal(s).setScale(1, mode).toString + "/" + BigDecimal(s).setScale(0, mode)).mkString(" ")}")
    println(s"${x.scale} ${x.precision} ${x.underlying.unscaledValue} ${x.signum} ${(-x).abs} ${x.ulp}")
    println(s"${x.round(MathContext(2))} ${x.round(MathContext(3, RoundingMode.DOWN))} ${BigDecimal("123456.789").round(MathContext(4))}")
    println(s"${BigDecimal(1, MathContext(5)) / 7} ${BigDecimal(1, MathContext.DECIMAL32) / 3} ${(BigDecimal(2) / 3).mc}")
    val small = BigDecimal(1).apply(MathContext(3))
    println(s"${small / 3} ${BigDecimal(22).apply(MathContext(4)) / 7} ${BigDecimal(1) / 7}")
    println(s"${BigDecimal("1.50").underlying.stripTrailingZeros} ${BigDecimal("1E+3").toBigInt} ${BigDecimal("1E+3").bigDecimal.toPlainString}")
    println(s"${BigDecimal(0.1)} ${BigDecimal.decimal(0.1)} ${BigDecimal.exact(0.1)} ${BigDecimal(1.5f)} ${BigDecimal(42L)}")
    // From a Double that is not a constant: scala.math.BigDecimal(Double) and BigDecimal == Double use a MathContext too.
    val d: Double = "1.5".toDouble
    println(s"${BigDecimal(d)} ${BigDecimal("1.5") == d} ${BigDecimal(d) == BigDecimal("1.5")} ${BigDecimal(d, MathContext(1))} ${BigDecimal("1.50") == d}")
    println(s"${BigDecimal("1e-3")} ${BigDecimal("12345678901234567890.1234567890")} ${BigDecimal(BigInt(12345), 2)}")
    println(s"${x.toInt} ${x.toLong} ${x.toDouble} ${x.toFloat} ${x.toBigIntExact} ${BigDecimal(7).toBigIntExact} ${BigDecimal(7).toIntExact}")
    println(try x.toIntExact catch case e: ArithmeticException => s"toIntExact: ${e.getMessage}")
    println(s"${x.isValidInt} ${BigDecimal(7).isValidInt} ${x.isWhole} ${BigDecimal("2.0").isWhole} ${x.isDecimalDouble} ${BigDecimal("0.1").isExactDouble}")
    println(s"${x.compare(y)} ${x > y} ${x.min(y)} ${x.max(y)} ${BigDecimal("2.0") == BigDecimal("2.00")} ${BigDecimal("2.0").equals(BigDecimal(2))}")
    println(s"${List(BigDecimal("1.5"), BigDecimal("-2"), BigDecimal("0.25")).sum} ${List(BigDecimal("1.5"), BigDecimal("4")).product}")
    println(s"${BigDecimal(1) / BigDecimal("0.00001")} ${BigDecimal(10).pow(-2)} ${BigDecimal("0.000")} ${BigDecimal("-0.0").signum}")
    println(s"${BigDecimal("3.14159").rounded} ${BigDecimal("1234.5").remainder(BigDecimal(100))} ${BigDecimal(5).until(BigDecimal(7)).by(BigDecimal("0.5")).toList}")
    println(try BigDecimal("1.5").toBigIntExact catch case e: ArithmeticException => "not exact")
    println(try BigDecimal(1) / 0 catch case e: ArithmeticException => s"ArithmeticException ${e.getMessage}")
    println(try BigDecimal("x1") catch case e: NumberFormatException => "NumberFormatException")
    val money = List("19.99", "5.01", "0.10").map(BigDecimal(_))
    println(s"${money.sum} ${(money.sum * BigDecimal("1.08")).setScale(2, SRoundingMode.HALF_UP)}")
    println(MathContext.DECIMAL64)
    println(s"${MathContext(7, RoundingMode.HALF_DOWN)} ${MathContext.DECIMAL128.getPrecision} ${MathContext.UNLIMITED}")

  def mathFunctions(): Unit =
    section("scala.math")
    import scala.math.*
    println(s"${sqrt(2)} ${cbrt(27)} ${pow(2, 10)} ${pow(2, 0.5)} ${exp(1)} ${log(E)} ${log10(1000)} ${log1p(0)}")
    println(s"${sin(Pi / 2)} ${cos(0)} ${tan(0)} ${atan2(1, 1)} ${asin(1)} ${acos(1)} ${atan(1)}")
    println(s"${sinh(0)} ${cosh(0)} ${tanh(0)} ${hypot(3, 4)} ${expm1(0)}")
    println(s"${abs(-3)} ${abs(-3.5)} ${abs(Int.MinValue)} ${max(1, 2)} ${min(1L, 2L)} ${max(1.5, -1.5)}")
    println(s"${signum(-2.5)} ${signum(0.0)} ${signum(7)} ${signum(-7L)}")
    println(s"${floor(-1.5)} ${ceil(-1.5)} ${rint(2.5)} ${rint(3.5)} ${round(2.5)} ${round(-2.5)} ${round(2.4f)}")
    println(s"${floorDiv(-7, 2)} ${floorMod(-7, 2)} ${floorDiv(7L, -2L)} ${floorMod(7L, -2L)} ${-7 / 2} ${-7 % 2}")
    println(s"${toRadians(180)} ${toDegrees(Pi)}")
    println(s"${Pi} ${E} ${sqrt(-1).isNaN} ${(1.0 / 0.0).isPosInfinity} ${Double.NegativeInfinity}")
    println(s"${addExact(1, 2)} ${try addExact(Int.MaxValue, 1) catch case e: ArithmeticException => e.getMessage}")
    println(s"${multiplyExact(3L, 4L)} ${try multiplyExact(Long.MaxValue, 2L) catch case e: ArithmeticException => e.getMessage}")
    println(s"${negateExact(5)} ${subtractExact(10, 3)} ${incrementExact(1)} ${decrementExact(1L)} ${try toIntExact(1L << 40) catch case e: ArithmeticException => e.getMessage}")
    println(s"${IEEEremainderSafe(10.0, 3.0)} ${0.1 + 0.2} ${0.1f + 0.2f} ${1.0 / 3} ${2.0 / 3}")
    println(s"${100.0 / 7} ${1e-7} ${123456789.0 * 1000} ${1e21} ${1e-5} ${0.001} ${(0.1 * 3).toString}")
    println(s"${Double.MinValue} ${-0.0} ${0.0 == -0.0} ${(-0.0).equals(0.0)} ${Double.NaN == Double.NaN}")
    println(s"${java.lang.Double.compare(0.0, -0.0)} ${(0.0 / 0.0).isNaN} ${Float.NaN.isNaN} ${(1 / 3f)}")
    println(s"${BigInt(2).pow(64).toDouble} ${math.pow(10, 20).toLong} ${math.pow(2, 31).toInt} ${(-1.5).toInt}")
    println(s"${Equiv[Int].equiv(1, 1)} ${Ordering[Int].tryCompare(1, 2)} ${Ordering[Double].compare(-0.0, 0.0)}")

  // Math.IEEEremainder is missing (a documented limitation): compute it with floor division here.
  def IEEEremainderSafe(x: Double, y: Double): Double = x - y * math.rint(x / y)
