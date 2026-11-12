import java.math.{BigInteger, MathContext, RoundingMode, BigDecimal as JBigDecimal}

object Main {
  def jbd(s: String): JBigDecimal = new JBigDecimal(s)

  /** The result, or the exception's class and message. */
  def attempt(label: String)(f: => Any): Unit =
    val result =
      try String.valueOf(f)
      catch case e: RuntimeException => e.getClass.getName + ": " + e.getMessage
    println(s"$label: $result")

  def main(args: Array[String]): Unit = {
    parsing()
    layout()
    rounding()
    arithmetic()
    division()
    powersAndRoots()
    conversions()
    comparisons()
    mathContexts()
    scalaBigDecimal()
  }

  def parsing(): Unit = {
    println("-- parsing")
    for s <- List("0", "-0", "+0.00", "123.4500", "-1.5e3", "1E+400", "0.0000001", "-.5", "5.", "1e-2147483647",
        "12345678901234567890.123456789", "-9223372036854775808", "9223372036854775808", "0E-10", "0e+3",
        "١٢٣.٤", "1E2147483648")
    do
      val d = jbd(s)
      println(s"$s -> $d (unscaled ${d.unscaledValue}, scale ${d.scale}, precision ${d.precision})")
    for s <- List("", "abc", "1e", "1e+", "+", "-", ".", "1.2.3", "1e2147483649", "0.1e-2147483647", "1_0", " 1",
        "--1", "1e+-2", "e5", "1e999999999999", "123456789012345678x", "NaN")
    do attempt(s"parse [$s]")(jbd(s))
    attempt("chars")(new JBigDecimal("x123.5y".toCharArray, 1, 5))
    attempt("chars mc")(new JBigDecimal("123.5".toCharArray, new MathContext(2)))
    attempt("chars bad range")(new JBigDecimal("123".toCharArray, 2, 5))
    attempt("string mc")(new JBigDecimal("123.456", new MathContext(4, RoundingMode.DOWN)))
    attempt("string mc overflow")(new JBigDecimal("4.389879251802186993E+2147483665", new MathContext(3)))
    println(new JBigDecimal(0.1))
    println(new JBigDecimal(-2.5))
    println(new JBigDecimal(1e20))
    println(new JBigDecimal(Double.MinPositiveValue).precision)
    println(new JBigDecimal(0.1, MathContext.DECIMAL64))
    println(JBigDecimal.valueOf(0.1))
    println(JBigDecimal.valueOf(1e20))
    println(JBigDecimal.valueOf(-0.0))
    println(JBigDecimal.valueOf(123456789L, 4))
    println(JBigDecimal.valueOf(-5L, -3))
    println(s"${new JBigDecimal(42)} ${new JBigDecimal(-7L)} ${new JBigDecimal(Long.MaxValue, MathContext.DECIMAL32)}")
    println(new JBigDecimal(new BigInteger("123456789012345678901234567890"), 10))
    println(new JBigDecimal(new BigInteger("-98765"), -3, new MathContext(2)))
    attempt("NaN")(new JBigDecimal(Double.NaN))
    println(s"${JBigDecimal.ZERO} ${JBigDecimal.ONE} ${JBigDecimal.TWO} ${JBigDecimal.TEN}")
  }

  def layout(): Unit = {
    println("-- toString, toEngineeringString, toPlainString")
    for s <- List("0", "0.00", "0E+3", "0E-7", "0E-8", "0E+1", "0E+2", "1", "-1.5", "123.456", "0.000001", "0.0000001",
        "-0.00000012", "1E+3", "12E+3", "123E+3", "1234E+3", "1.2E-9", "12E-10", "1E+4", "-5E-7", "1E+2147483647",
        "1E-2147483647", "4.2E+2147483648", "-123456789.987654321E-30")
    do
      val d = jbd(s)
      val plain = if math.abs(d.scale.toLong) > 100 then "(long)" else d.toPlainString
      println(s"$s: $d | ${d.toEngineeringString} | $plain")
  }

  def rounding(): Unit = {
    println("-- setScale with every rounding mode")
    val values = List("5.5", "2.5", "1.6", "1.1", "1.0", "-1.0", "-1.1", "-1.6", "-2.5", "-5.5", "0.05", "-0.05", "2.50", "2.51")
    for mode <- RoundingMode.values() do
      val row = values.map(v => try jbd(v).setScale(0, mode).toString catch case e: ArithmeticException => "!" + e.getMessage)
      println(s"$mode: ${row.mkString(" ")}")
    println(jbd("123.456").setScale(5))
    println(jbd("123.456").setScale(-1, RoundingMode.HALF_UP))
    println(jbd("123.456").setScale(1, JBigDecimal.ROUND_FLOOR))
    println(jbd("99.995").setScale(2, RoundingMode.HALF_EVEN))
    println(jbd("0E-5").setScale(2))
    attempt("setScale unnecessary")(jbd("1.25").setScale(1))
    attempt("setScale invalid mode")(jbd("1.25").setScale(1, 9))
    println("-- round")
    for p <- 1 to 5 do println(s"$p: ${jbd("9.9951").round(new MathContext(p))} ${jbd("-123.45").round(new MathContext(p, RoundingMode.HALF_DOWN))}")
    println(jbd("999.9").round(new MathContext(3)))
    println(jbd("123").round(MathContext.UNLIMITED))
    attempt("round unnecessary")(jbd("1.25").round(new MathContext(2, RoundingMode.UNNECESSARY)))
    println(jbd("1.20").round(new MathContext(2, RoundingMode.UNNECESSARY)))
  }

  def arithmetic(): Unit = {
    println("-- add, subtract, multiply")
    val pairs = List(("1.10", "2.205"), ("-0.1", "0.1"), ("1E+3", "1E-3"), ("9223372036854775807", "1"),
      ("-9223372036854775808", "-1"), ("123456789012345678901234567890", "0.000000001"), ("0E+5", "0.00"),
      ("5", "-5.000"))
    for (a, b) <- pairs do
      val x = jbd(a)
      val y = jbd(b)
      println(s"$a, $b: ${x.add(y)} ${x.subtract(y)} ${x.multiply(y)}")
    val mc = new MathContext(5, RoundingMode.HALF_EVEN)
    for (a, b) <- pairs do
      val x = jbd(a)
      val y = jbd(b)
      println(s"$a, $b (mc 5): ${x.add(y, mc)} ${x.subtract(y, mc)} ${x.multiply(y, mc)}")
    println(jbd("1E+10").add(jbd("1E-10"), new MathContext(3)))
    println(jbd("1").add(jbd("0E-10"), new MathContext(3)))
    println(jbd("12345").add(jbd("0"), new MathContext(3)))
    println(jbd("1000").subtract(jbd("0.0000001"), new MathContext(4, RoundingMode.DOWN)))
    println(jbd("1000").subtract(jbd("0.0000001"), new MathContext(4, RoundingMode.HALF_UP)))
    attempt("scale overflow")(jbd("1e-2147483647").multiply(jbd("1e-10")))
    attempt("scale underflow")(jbd("1e2147483647").multiply(jbd("1e10")))
    println(jbd("0e-2147483647").multiply(jbd("1e-10")).scale)
    println("-- negate, abs, plus, signum, ulp, unscaledValue, movePoint, scaleByPowerOfTen, strip")
    for s <- List("0", "-1.50", "1.50", "-9223372036854775808", "1E+3", "0.000") do
      val d = jbd(s)
      println(s"$s: ${d.negate} ${d.abs} ${d.plus} ${d.signum} ${d.ulp} ${d.unscaledValue} ${d.movePointLeft(2)} " +
        s"${d.movePointRight(2)} ${d.movePointLeft(0)} ${d.scaleByPowerOfTen(3)} ${d.stripTrailingZeros}")
    println(s"${jbd("-123.456").negate(new MathContext(2))} ${jbd("-123.456").abs(new MathContext(4))} " +
      s"${jbd("123.456").plus(new MathContext(1))}")
  }

  def division(): Unit = {
    println("-- divide")
    for (a, b) <- List(("1", "8"), ("10", "4"), ("1.00", "4"), ("100", "4"), ("6", "2"), ("1E+1", "1"), ("1", "32"),
        ("-7", "0.125"), ("12345678901234567890", "0.0005"), ("0", "7"), ("0.000", "0.5"))
    do println(s"$a / $b = ${jbd(a).divide(jbd(b))}")
    for (a, b) <- List(("1", "3"), ("1", "0"), ("0", "0"), ("10", "6")) do attempt(s"$a / $b")(jbd(a).divide(jbd(b)))
    for (a, b) <- List(("1", "3"), ("2", "3"), ("-2", "3"), ("1000", "1"), ("1", "7"), ("22", "7"), ("1E+5", "3E-5"),
        ("0.00", "3"), ("9.99", "1"), ("2", "2"), ("1", "1E+6"))
    do
      println(s"$a / $b: ${jbd(a).divide(jbd(b), MathContext.DECIMAL32)} ${jbd(a).divide(jbd(b), new MathContext(2, RoundingMode.UP))} " +
        s"${jbd(a).divide(jbd(b), MathContext.DECIMAL128)}")
    for mode <- RoundingMode.values() do
      println(s"$mode: ${List("7", "-7", "5", "-5", "1").map(a =>
        try jbd(a).divide(jbd("2"), 0, mode).toString catch case e: ArithmeticException => "!" + e.getMessage).mkString(" ")}")
    println(s"${jbd("10").divide(jbd("3"), 5, RoundingMode.HALF_UP)} ${jbd("10").divide(jbd("3"), -1, RoundingMode.UP)} " +
      s"${jbd("10.00").divide(jbd("3"), RoundingMode.DOWN)} ${jbd("10").divide(jbd("4"), JBigDecimal.ROUND_HALF_EVEN)}")
    for (a, b) <- List(("1", "0"), ("0", "0"), ("1", "0E-30"), ("1000000000000000000000000000000", "0"))
    do
      attempt(s"$a / $b (scale)")(jbd(a).divide(jbd(b), 2, RoundingMode.HALF_UP))
      attempt(s"$a / $b (mc)")(jbd(a).divide(jbd(b), MathContext.DECIMAL32))
    attempt("invalid rounding mode")(jbd("1").divide(jbd("3"), 2, 42))
    println("-- divideToIntegralValue, remainder, divideAndRemainder")
    for (a, b) <- List(("7", "2"), ("-7", "2"), ("7.5", "2"), ("1E+3", "3"), ("12345", "0.1"), ("1", "3"), ("0.5", "0.2"),
        ("-10.75", "0.5"), ("100", "1E+1"))
    do
      val x = jbd(a)
      val y = jbd(b)
      val qr = x.divideAndRemainder(y)
      println(s"$a, $b: ${x.divideToIntegralValue(y)} ${x.remainder(y)} [${qr(0)}, ${qr(1)}] " +
        s"${x.divideToIntegralValue(y, new MathContext(7))} ${x.remainder(y, MathContext.DECIMAL32)}")
    attempt("division impossible")(jbd("12345").divideToIntegralValue(jbd("0.1"), new MathContext(3)))
    attempt("remainder by zero")(jbd("1").remainder(JBigDecimal.ZERO))
    attempt("remainder zero by zero")(JBigDecimal.ZERO.remainder(JBigDecimal.ZERO))
  }

  def powersAndRoots(): Unit = {
    println("-- pow, sqrt")
    println(s"${jbd("1.1").pow(3)} ${jbd("-2").pow(63)} ${jbd("10").pow(0)} ${jbd("0.5").pow(10)} ${jbd("123456789").pow(5)}")
    println(s"${jbd("1.1").pow(-3, MathContext.DECIMAL32)} ${jbd("1.1").pow(30, new MathContext(5))} " +
      s"${jbd("3").pow(0, MathContext.DECIMAL32)} ${jbd("2").pow(100, MathContext.DECIMAL64)}")
    attempt("pow -1")(jbd("2").pow(-1))
    attempt("pow too big for mc")(jbd("1.1").pow(12345678, new MathContext(3)))
    for (s, mc) <- List(("2", MathContext.DECIMAL32), ("2", new MathContext(3, RoundingMode.UP)), ("4.00", MathContext.UNLIMITED),
        ("0.0400", MathContext.DECIMAL32), ("100", MathContext.DECIMAL32), ("1E+2", MathContext.DECIMAL32),
        ("1E+3", MathContext.DECIMAL32), ("0E+3", MathContext.DECIMAL32), ("0E-3", MathContext.DECIMAL32),
        ("1.21", new MathContext(1)), ("1.2100", new MathContext(5)), ("12100", MathContext.UNLIMITED),
        ("1.44E+4", new MathContext(5)), ("2", MathContext.DECIMAL128), ("0.000000000001", MathContext.DECIMAL64))
    do attempt(s"sqrt($s, ${mc.getPrecision})")(jbd(s).sqrt(mc))
    attempt("sqrt negative")(jbd("-1").sqrt(MathContext.DECIMAL32))
    attempt("sqrt inexact")(jbd("2").sqrt(MathContext.UNLIMITED))
    attempt("sqrt unnecessary")(jbd("2").sqrt(new MathContext(5, RoundingMode.UNNECESSARY)))
  }

  def conversions(): Unit = {
    println("-- conversions")
    for s <- List("0", "1.99", "-1.99", "123456789012.75", "9223372036854775807", "9223372036854775808",
        "-9223372036854775809", "18446744073709551617.5", "1E+20", "1E+100", "0.0001", "2147483648", "-129")
    do
      val d = jbd(s)
      println(s"$s: ${d.intValue} ${d.longValue} ${d.shortValue} ${d.byteValue} ${d.doubleValue} ${d.floatValue} ${d.toBigInteger}")
    for s <- List("123", "1.5", "1E+19", "0.5", "1E+10", "-2147483648", "200", "-128", "1.000", "9223372036854775807.0") do
      attempt(s"exact $s")(s"${jbd(s).longValueExact} ${jbd(s).toBigIntegerExact}")
      attempt(s"intValueExact $s")(jbd(s).intValueExact)
      attempt(s"shortValueExact $s")(jbd(s).shortValueExact)
      attempt(s"byteValueExact $s")(jbd(s).byteValueExact)
    // doubleValue is correctly rounded, also far from the fast paths.
    for s <- List("0.1", "1.00000017881393432617187499", "2.2250738585072011e-308", "1e-400", "-1e400", "123456789012345678901234567890",
        "9007199254740993", "4.9e-324", "1.7976931348623157e308", "0.30000000000000001665")
    do println(s"$s: ${jbd(s).doubleValue} ${jbd(s).floatValue}")
  }

  def comparisons(): Unit = {
    println("-- compareTo, equals, hashCode, min, max")
    val ds = List("2.0", "2.00", "2", "-2", "0", "0.000", "1E+1", "10", "9223372036854775808", "-9223372036854775808", "1E-2147483647")
      .map(jbd)
    for x <- ds do println(s"$x: ${ds.map(y => x.compareTo(y)).mkString(" ")} | ${ds.map(y => if x.equals(y) then 1 else 0).mkString}" +
      s" | ${x.hashCode} | ${x.min(jbd("2"))} ${x.max(jbd("2"))}")
    val any: Any = jbd("1.0")
    println(any == jbd("1.0"))
    println(any == jbd("1.00"))
    println(any.hashCode == jbd("1.0").hashCode)
    println(java.util.Objects.equals(jbd("3"), jbd("3.0")))
    val numbers: List[Number] = List(jbd("2.5"), BigInteger.TEN, Integer.valueOf(3))
    println(numbers.map(_.doubleValue).sum)
    println(List(jbd("3"), jbd("-1.5"), jbd("2.25")).sortWith(_.compareTo(_) < 0))
    val comparable: Comparable[JBigDecimal] = jbd("1")
    println(comparable.compareTo(jbd("0.5")))
    println(jbd("1").getClass.getName)
  }

  def mathContexts(): Unit = {
    println("-- MathContext, RoundingMode")
    println(s"${MathContext.DECIMAL32} | ${MathContext.DECIMAL64} | ${MathContext.DECIMAL128} | ${MathContext.UNLIMITED}")
    println(s"${new MathContext(5)} | ${new MathContext("precision=7 roundingMode=HALF_DOWN")}")
    println(new MathContext(5) == new MathContext(5, RoundingMode.HALF_UP))
    attempt("negative precision")(new MathContext(-1))
    attempt("bad string")(new MathContext("precision=7 roundingMode=FOO"))
    attempt("bad string 2")(new MathContext("x"))
    println(s"${RoundingMode.values().mkString(" ")} ${RoundingMode.valueOf("HALF_EVEN").ordinal} ${RoundingMode.valueOf(2)}")
    attempt("valueOf(9)")(RoundingMode.valueOf(9))
    attempt("valueOf(FOO)")(RoundingMode.valueOf("FOO"))
    println(RoundingMode.HALF_UP.compareTo(RoundingMode.DOWN) > 0)
  }

  def scalaBigDecimal(): Unit = {
    println("-- scala.math.BigDecimal")
    val a = BigDecimal("1.10")
    val b = BigDecimal("2.205")
    println(s"${a + b} ${a - b} ${a * b} ${a / b} ${b / a} ${-a} ${a.abs} ${a.signum}")
    println(BigDecimal(1) / 3)
    println(BigDecimal(2) / BigDecimal("0.0000001"))
    println(BigDecimal(10) % 3)
    println(BigDecimal("7.5") /% BigDecimal(2))
    println(BigDecimal(1.1) + BigDecimal(2.2))
    println(BigDecimal(0.1) == 0.1)
    println(BigDecimal("1.0") == BigDecimal("1.00"))
    println(BigDecimal("1.0").hashCode == BigDecimal("1.00").hashCode)
    println(BigDecimal(3) == 3)
    println(BigDecimal("1E+3").isWhole)
    println(BigDecimal("1.5").isWhole)
    println(BigDecimal("12345678901234567890.5").toBigInt)
    println(BigDecimal("1e400").toBigInt.bitLength)
    println(s"${BigDecimal("3.7").toInt} ${BigDecimal("-3.7").toLong} ${BigDecimal("3.7").toDouble}")
    println(BigDecimal("2").pow(100))
    println(BigDecimal.decimal(1.0 / 3))
    println(BigDecimal.binary(0.1))
    println(BigDecimal.exact(0.1))
    println(s"${BigDecimal("123.456").precision} ${BigDecimal("123.456").scale} ${BigDecimal("123.456").ulp}")
    println(BigDecimal("123.456").round(new MathContext(4)))
    println(BigDecimal("123.456")(new MathContext(2)))
    println(List(BigDecimal("2.5"), BigDecimal("-1"), BigDecimal("10")).sorted)
    println(List(BigDecimal("2.5"), BigDecimal("-1"), BigDecimal("10")).sum)
    println(List(BigDecimal("2.5"), BigDecimal("-1"), BigDecimal("10")).max)
    println(s"${BigDecimal("0.1").isDecimalDouble} ${BigDecimal("1e1000").isDecimalDouble} ${BigDecimal(42).isValidInt}")
    println(BigDecimal("1e1000").toDouble)
    var money = BigDecimal(0)
    for _ <- 1 to 1000 do money += BigDecimal("0.01")
    println(money)
    println(BigDecimal("19.99") * 3)
    println(BigDecimal(1, new MathContext(3)) / 7)
  }
}
