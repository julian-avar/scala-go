import java.lang.{Double as JDouble, Float as JFloat, Long as JLong}

// The floating-point boxes: toString's shortest representation at the edges (subnormals, powers of
// ten where it switches to scientific notation, extremes), parsing (Java's syntax, hex floats,
// special values, errors), bits, NaN and signed zeros in comparisons and equality, and Boolean.
object Main:
  def attempt(label: String)(body: => Any): Unit =
    val r =
      try String.valueOf(body)
      catch case e: Exception => s"${e.getClass.getName}: ${e.getMessage}"
    println(s"$label: $r")

  /** Only the exception's class: some messages differ (see known-bugs-jdk). */
  def attemptType(label: String)(body: => Any): Unit =
    val r =
      try String.valueOf(body)
      catch case e: Exception => e.getClass.getName
    println(s"$label: $r")

  // Negative bit patterns are not constants here: see known-bugs-jdk-go-errors.
  def main(args: Array[String]): Unit =
    doubleToString()
    floatToString()
    parsing()
    bits()
    comparing()
    booleans()

  def doubleToString(): Unit =
    println("== Double.toString")
    val ds = Seq(0.0, -0.0, 1.0, -1.5, 0.1, 0.2 + 0.1, 1.0 / 3, 2.0 / 3, 100.0, 1e6, 9999999.0, 1e7, 1.0e7 + 0.5, 12345678.9, 1e-3,
      0.001, 9.999e-4, 1e-4, 123456789012345680.0, 1e21, 1e22, 1e23, 5e-324, 4.9e-324, JDouble.MIN_NORMAL, JDouble.MAX_VALUE,
      JDouble.MIN_VALUE, Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity, 2e-323, 1.7976931348623157e308,
      Math.PI, Math.E, 4.35, 0.3, 2.0e-3, 1.0E-5, 3.0e10, 1.23456789e-7, Long.MaxValue.toDouble, Int.MinValue.toDouble)
    for d <- ds do println(s"${JDouble.toString(d)} ${String.valueOf(d)} ${d.toString == JDouble.toString(d)}")

  def floatToString(): Unit =
    println("== Float.toString")
    val fs = Seq(0.0f, -0.0f, 1.0f, 0.1f, 0.2f + 0.1f, 1.0f / 3, 100.0f, 1e7f, 9999999.0f, 1.0e-3f, 1e-4f, 3.4028235e38f,
      1.4e-45f, JFloat.MIN_NORMAL, 16777216.0f, 16777217.0f, Float.NaN, Float.PositiveInfinity, Float.NegativeInfinity,
      3.14159265f, 1.17549435e-38f, 1.0e10f, 123.456f, 0.3f, 2.5e-5f)
    for f <- fs do println(s"${JFloat.toString(f)} ${String.valueOf(f)} ${f.toDouble}")

  def parsing(): Unit =
    println("== parseDouble")
    val inputs = Seq("1", "-1.5", "+2.5", "1e10", "1E-10", "1.", ".5", "-.5e1", "  3.25  ", "\t7\n", "1d", "1D", "2f", "3.0d",
      "NaN", "Infinity", "-Infinity", "+Infinity", "1e400", "-1e400", "1e-400", "4.9e-324", "2.4703282292062328e-324",
      "0x1p3", "0x1.8p1", "-0x.8p0", "0X10P-2", "0x1p-1074", "abc", "1e", "e5",
      "0x10", "١", "1,5", "--1", "0.1e+2", "123456789012345678901234567890")
    for s <- inputs do attempt(s"parseDouble(\"$s\")")(JDouble.parseDouble(s))
    for s <- Seq("", " ", "1.2.3") do attemptType(s"parseDouble(\"$s\")")(JDouble.parseDouble(s))
    attemptType("parseDouble(null)")(JDouble.parseDouble(null))
    println("== parseFloat")
    for s <- Seq("1.1", "3.4028235e38", "3.5e38", "1e-46", "1.4e-45", "0.1", "16777217", "-0", "NaN", "0x1.fffffep127", "x") do
      attempt(s"parseFloat(\"$s\")")(JFloat.parseFloat(s))
    attempt("Double.valueOf(\"2.5\")")(JDouble.valueOf("2.5"))
    attempt("\"1.5\".toDouble")("1.5".toDouble)
    attempt("\"1.5x\".toDouble")("1.5x".toDouble)
    attempt("\"2.5\".toFloat")("2.5".toFloat)

  def bits(): Unit =
    println("== bits")
    val ds = Seq(0.0, -0.0, 1.0, -2.0, 0.1, Double.NaN, Double.PositiveInfinity, JDouble.MIN_VALUE, JDouble.MAX_VALUE, JDouble.MIN_NORMAL)
    for d <- ds do
      val bits = JDouble.doubleToLongBits(d)
      println(s"$d: ${JLong.toHexString(bits)} raw=${if d.isNaN then "(NaN: known-bugs-jdk)" else JLong.toHexString(JDouble.doubleToRawLongBits(d))} back=${JDouble.longBitsToDouble(bits)}")
    val otherNaN = JDouble.longBitsToDouble(0x7ff0000000000001L)
    println(s"other NaN: ${otherNaN.isNaN} ${JLong.toHexString(JDouble.doubleToLongBits(otherNaN))} ${JLong.toHexString(JDouble.doubleToRawLongBits(otherNaN))}")
    val fs = Seq(0.0f, -0.0f, 1.0f, -2.0f, 0.1f, Float.NaN, Float.NegativeInfinity, JFloat.MIN_VALUE, JFloat.MAX_VALUE)
    for f <- fs do
      val bits = JFloat.floatToIntBits(f)
      println(s"$f: ${Integer.toHexString(bits)} raw=${Integer.toHexString(JFloat.floatToRawIntBits(f))} back=${JFloat.intBitsToFloat(bits)}")
    println(s"intBitsToFloat ${JFloat.intBitsToFloat(0x3f800000)} ${JFloat.intBitsToFloat(0x00000001)} ${JFloat.intBitsToFloat(Integer.parseInt("-8388608"))} ${JFloat.intBitsToFloat(0x7fc00000).isNaN}")
    println(s"longBitsToDouble ${JDouble.longBitsToDouble(0x4000000000000000L)} ${JDouble.longBitsToDouble(1L)} ${JDouble.longBitsToDouble(JLong.MIN_VALUE + Integer.parseInt("0"))}")

  def comparing(): Unit =
    println("== comparing")
    val ds = Seq(Double.NegativeInfinity, -1.0, -0.0, 0.0, JDouble.MIN_VALUE, 1.0, Double.PositiveInfinity, Double.NaN)
    println(ds.map(a => ds.map(b => JDouble.compare(a, b) match { case 0 => '='; case x if x < 0 => '<'; case _ => '>' }).mkString).mkString(" "))
    val boxed = java.util.Arrays.asList[JDouble](Double.NaN, 1.0, -0.0, 0.0, Double.NegativeInfinity, -1.0)
    java.util.Collections.sort(boxed)
    println(boxed)
    println(s"equals ${JDouble.valueOf(Double.NaN).equals(Double.NaN)} ${JDouble.valueOf(0.0).equals(-0.0)} ${(Double.NaN: Any) == Double.NaN} ${0.0 == -0.0} ${JDouble.valueOf(1.0).equals(1.0f)}")
    println(s"hashCode ${JDouble.hashCode(0.0)} ${JDouble.hashCode(-0.0)} ${JDouble.hashCode(1.5)} ${JDouble.hashCode(Double.NaN)} ${JFloat.hashCode(1.5f)} ${JFloat.hashCode(-0.0f)} ${JDouble.valueOf(2.0).hashCode}")
    println(s"isNaN ${JDouble.isNaN(0.0 / 0)} ${JFloat.isNaN(1f)} isInfinite ${JDouble.isInfinite(-1.0 / 0)} ${JFloat.isInfinite(Float.MaxValue)} isFinite ${JDouble.isFinite(Double.MaxValue)} ${JDouble.isFinite(Double.NaN)} ${JFloat.isFinite(Float.NegativeInfinity)}")
    println(s"instance ${JDouble.valueOf(Double.NaN).isNaN} ${JFloat.valueOf(Float.PositiveInfinity).isInfinite}")
    println(s"max ${JDouble.max(-0.0, 0.0)} ${JDouble.max(Double.NaN, 1)} ${JDouble.min(-0.0, 0.0)} ${JDouble.min(1, Double.NaN)} ${JFloat.max(-0.0f, 0.0f)} ${JFloat.min(1f, 2f)}")
    println(s"Math max ${Math.max(-0.0, 0.0)} ${Math.min(-0.0, 0.0)} ${Math.max(Float.NaN, 1f)} ${Math.min(0.0f, -0.0f)}")
    println(s"sum ${JDouble.sum(0.1, 0.2)} ${JFloat.sum(0.1f, 0.2f)} compare ${JFloat.compare(0.0f, -0.0f)} ${JFloat.compare(Float.NaN, Float.PositiveInfinity)}")
    val d: JDouble = 3.75
    println(s"${d.intValue} ${d.longValue} ${d.floatValue} ${d.shortValue} ${d.byteValue} ${d.compareTo(3.5)} ${d.toString} ${JFloat.valueOf(-1e10f).intValue} ${JFloat.valueOf(2.5f).longValue}")
    println(s"${JDouble.MAX_EXPONENT} ${JDouble.MIN_EXPONENT} ${JDouble.SIZE} ${JFloat.MAX_EXPONENT} ${JFloat.MIN_EXPONENT} ${JFloat.BYTES}")

  def booleans(): Unit =
    println("== Boolean")
    println(Seq("true", "TRUE", "True", "yes", "1", "", null, " true").map(s => java.lang.Boolean.parseBoolean(s)).mkString(" "))
    println(s"${java.lang.Boolean.valueOf(true)} ${java.lang.Boolean.toString(false)} ${java.lang.Boolean.TRUE} ${java.lang.Boolean.FALSE}")
    println(s"${java.lang.Boolean.compare(true, false)} ${java.lang.Boolean.compare(false, true)} ${java.lang.Boolean.compare(true, true)} ${java.lang.Boolean.TRUE.compareTo(java.lang.Boolean.FALSE)}")
    println(s"${java.lang.Boolean.hashCode(true)} ${java.lang.Boolean.hashCode(false)} ${java.lang.Boolean.TRUE.hashCode} ${java.lang.Boolean.TRUE.booleanValue}")
    println(s"${java.lang.Boolean.getBoolean("no.such.property")}")
