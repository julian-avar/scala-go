// java.lang.Math and StrictMath: exact operations and their ArithmeticExceptions, floor division,
// rounding, special values (NaN, infinities, signed zeros), and the transcendental functions
// (printed to 12 places: the JDK allows Math's results to differ in the last bit; StrictMath's are
// printed in full, as they must equal fdlibm's).
object Main:
  def attempt(label: String)(body: => Any): Unit =
    val r =
      try String.valueOf(body)
      catch case e: Exception => s"${e.getClass.getName}: ${e.getMessage}"
    println(s"$label: $r")

  def approx(d: Double): String =
    if d.isNaN || d.isInfinite then d.toString else java.math.BigDecimal(d).setScale(12, java.math.RoundingMode.HALF_EVEN).stripTrailingZeros.toPlainString

  val specials: Seq[Double] = Seq(0.0, -0.0, 1.0, -1.0, 2.5, -2.5, 0.5, -0.5, 1.5, 1e300, Double.MinPositiveValue,
    Double.PositiveInfinity, Double.NegativeInfinity, Double.NaN)

  def main(args: Array[String]): Unit =
    exact()
    division()
    rounding()
    special()
    transcendental()
    strict()

  def exact(): Unit =
    println("== exact")
    attempt("addExact(MaxValue, 1)")(Math.addExact(Int.MaxValue, 1))
    attempt("addExact(MinValue, -1)")(Math.addExact(Int.MinValue, -1))
    attempt("addExact(1, 2)")(Math.addExact(1, 2))
    attempt("addExact(Long.MaxValue, 1L)")(Math.addExact(Long.MaxValue, 1L))
    attempt("subtractExact(MinValue, 1)")(Math.subtractExact(Int.MinValue, 1))
    attempt("subtractExact(0, MinValue)")(Math.subtractExact(0, Int.MinValue))
    attempt("subtractExact(Long.MinValue, 1L)")(Math.subtractExact(Long.MinValue, 1L))
    attempt("subtractExact(5L, 7L)")(Math.subtractExact(5L, 7L))
    attempt("multiplyExact(65536, 65536)")(Math.multiplyExact(65536, 65536))
    attempt("multiplyExact(-46341, 46341)")(Math.multiplyExact(-46341, 46341))
    attempt("multiplyExact(46340, 46340)")(Math.multiplyExact(46340, 46340))
    attempt("multiplyExact(MinValue, -1)")(Math.multiplyExact(Int.MinValue, -1))
    attempt("multiplyExact(1L << 32, 1L << 31)")(Math.multiplyExact(1L << 32, 1L << 31))
    attempt("multiplyExact(Long.MinValue, -1L)")(Math.multiplyExact(Long.MinValue, -1L))
    attempt("multiplyExact(3037000499L, 3037000499L)")(Math.multiplyExact(3037000499L, 3037000499L))
    attempt("multiplyExact(Long.MaxValue, 2)")(Math.multiplyExact(Long.MaxValue, 2))
    attempt("multiplyExact(1L << 40, -3)")(Math.multiplyExact(1L << 40, -3))
    attempt("negateExact(MinValue)")(Math.negateExact(Int.MinValue))
    attempt("negateExact(Long.MinValue)")(Math.negateExact(Long.MinValue))
    attempt("negateExact(5)")(Math.negateExact(5))
    attempt("incrementExact(MaxValue)")(Math.incrementExact(Int.MaxValue))
    attempt("incrementExact(Long.MaxValue)")(Math.incrementExact(Long.MaxValue))
    attempt("decrementExact(MinValue)")(Math.decrementExact(Int.MinValue))
    attempt("decrementExact(Long.MinValue)")(Math.decrementExact(Long.MinValue))
    attempt("decrementExact(0L)")(Math.decrementExact(0L))
    attempt("toIntExact(1L << 31)")(Math.toIntExact(1L << 31))
    attempt("toIntExact(-(1L << 31))")(Math.toIntExact(-(1L << 31)))
    attempt("toIntExact(-(1L << 31) - 1)")(Math.toIntExact(-(1L << 31) - 1))
    attempt("clamp(5L, 1, 3)")(Math.clamp(5L, 1, 3))
    attempt("clamp(Long.MinValue, -2, 3)")(Math.clamp(Long.MinValue, -2, 3))
    attempt("clamp(7L, 2L, 9L)")(Math.clamp(7L, 2L, 9L))
    attempt("clamp(1L, 3, 2)")(Math.clamp(1L, 3, 2))

  def division(): Unit =
    println("== floorDiv and floorMod")
    for (a, b) <- Seq((7, 2), (-7, 2), (7, -2), (-7, -2), (6, 3), (-6, 3), (0, -5), (Int.MinValue, -1), (Int.MinValue, 7), (1, Int.MinValue)) do
      println(s"$a,$b: ${Math.floorDiv(a, b)} ${Math.floorMod(a, b)} | ${a / b} ${a % b}")
    for (a, b) <- Seq((7L, 2L), (-7L, 2L), (7L, -2L), (Long.MinValue, -1L), (Long.MinValue, 3L), (-1L, Long.MaxValue)) do
      println(s"$a,$b: ${Math.floorDiv(a, b)} ${Math.floorMod(a, b)}")
    attempt("floorDiv(1, 0)")(Math.floorDiv(1, 0))
    attempt("floorMod(1L, 0L)")(Math.floorMod(1L, 0L))

  def rounding(): Unit =
    println("== rounding")
    val ds = Seq(0.0, -0.0, 0.4, 0.5, 0.6, 1.5, 2.5, -0.4, -0.5, -0.6, -1.5, -2.5, 1e15 + 0.5, 1e300, -1e300,
      Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity, Double.MinPositiveValue, -Double.MinPositiveValue,
      0.49999999999999994, -0.49999999999999994, 4503599627370497.0, 4503599627370495.5, 9007199254740991.0, -4503599627370497.0)
    for d <- ds do
      println(s"$d: floor=${Math.floor(d)} ceil=${Math.ceil(d)} rint=${Math.rint(d)} round=${Math.round(d)} roundF=${Math.round(d.toFloat)}")
    println(s"round(Float) ${Math.round(2.5f)} ${Math.round(-2.5f)} ${Math.round(1e20f)} ${Math.round(-1e20f)} ${Math.round(Float.NaN)} ${Math.round(0.49999997f)} ${Math.round(8388609f)} ${Math.round(-0.5f)}")
    println(s"RichDouble ${2.5.round} ${(-2.5).round} ${2.7.floor} ${2.2.ceil}")

  def special(): Unit =
    println("== special values")
    for d <- specials do
      println(s"$d: abs=${Math.abs(d)} signum=${Math.signum(d)} sqrt=${Math.sqrt(d)} cbrt=${approx(Math.cbrt(d))} nextUp=${Math.nextUp(d)} nextDown=${Math.nextDown(d)}")
    println(s"abs ${Math.abs(Int.MinValue)} ${Math.abs(Long.MinValue)} ${Math.abs(-0.0f)} ${Math.abs(-7)} ${Math.abs(-7L)} ${Math.abs(Float.NegativeInfinity)}")
    println(s"signum(F) ${Math.signum(-3f)} ${Math.signum(-0.0f)} ${Math.signum(Float.NaN)} ${Math.signum(0.1f)}")
    println(s"max/min ${Math.max(Int.MinValue, -1)} ${Math.min(Long.MaxValue, 0L)} ${Math.max(Double.NaN, 1.0)} ${Math.min(Double.NaN, 1.0)} ${Math.max(1.0f, 2.0f)} ${Math.min(-0.0, 0.0)}")
    println(s"exact ${Math.sqrt(2.25)} ${Math.sqrt(1e-300)} ${Math.cbrt(-27.0)} ${Math.cbrt(1e-300)} ${Math.hypot(3, 4)} ${Math.hypot(Double.NaN, Double.PositiveInfinity)} ${Math.hypot(-5e200, 12e200)}")
    println(s"pow ${Math.pow(2, 10)} ${Math.pow(-2, 3)} ${Math.pow(2, -2)} ${Math.pow(0, 0)} ${Math.pow(Double.NaN, 0)} ${Math.pow(-8, 1.0 / 3)} ${Math.pow(-0.0, -1)} ${Math.pow(-0.0, -2)} ${Math.pow(10, 22)} ${Math.pow(2, 1023)} ${Math.pow(10, 309)}")
    println(s"pow special ${Math.pow(1, Double.NaN)} ${Math.pow(-1, Double.PositiveInfinity)} ${Math.pow(-1, Double.NegativeInfinity)} ${Math.pow(1, Double.PositiveInfinity)} ${Math.pow(Double.NaN, 1)} ${Math.pow(2, Double.NaN)} ${Math.pow(0.5, Double.PositiveInfinity)} ${Math.pow(2, Double.NegativeInfinity)} ${Math.pow(-0.0, 3)} ${Math.pow(-8, 3)} ${Math.pow(Double.NegativeInfinity, 3)} ${Math.pow(Double.NegativeInfinity, -3)}")
    println(s"pow accuracy ${Math.pow(10, 308)} ${Math.pow(10, -300)} ${Math.pow(1.0001, 10000)} ${Math.pow(3, 100)} ${Math.pow(7, -50)} ${Math.pow(Math.E, 700)}")
    println(s"log ${Math.log(0)} ${Math.log(-1)} ${Math.log(1)} ${Math.log10(1000)} ${Math.log10(0.001)} ${Math.log10(-0.0)} ${Math.log1p(-1)} ${Math.log1p(0)} ${Math.exp(0)} ${Math.exp(Double.NegativeInfinity)} ${Math.expm1(0)} ${Math.exp(1000)}")
    println(s"trig ${Math.sin(0)} ${Math.sin(-0.0)} ${Math.cos(0)} ${Math.tan(-0.0)} ${Math.asin(2)} ${Math.acos(1)} ${Math.atan(Double.PositiveInfinity) == Math.PI / 2} ${Math.sin(Double.PositiveInfinity)}")
    println(s"atan2 ${Math.atan2(0.0, -0.0) == Math.PI} ${Math.atan2(-0.0, -0.0) == -Math.PI} ${Math.atan2(0.0, 0.0)} ${Math.atan2(-0.0, 0.0)} ${Math.atan2(1, 0) == Math.PI / 2} ${Math.atan2(Double.NaN, 1)}")
    println(s"hyperbolic ${Math.sinh(0)} ${Math.sinh(-0.0)} ${Math.cosh(0)} ${Math.tanh(Double.PositiveInfinity)} ${Math.tanh(-1000)} ${Math.sinh(Double.NegativeInfinity)} ${Math.cosh(Double.NegativeInfinity)}")
    println(s"degrees ${Math.toDegrees(Math.PI)} ${Math.toRadians(180)} ${Math.toDegrees(0)} ${Math.toRadians(-0.0)}")
    println(s"constants ${Math.PI} ${Math.E} ${StrictMath.PI}")
    val r = Math.random()
    println(s"random in [0, 1) ${r >= 0 && r < 1}")

  def transcendental(): Unit =
    println("== transcendental (12 places)")
    for x <- Seq(0.1, 0.5, 1.0, 2.0, 3.0, 10.0, -0.75) do
      println(s"$x: sin=${approx(Math.sin(x))} cos=${approx(Math.cos(x))} tan=${approx(Math.tan(x))} atan=${approx(Math.atan(x))} " +
        s"exp=${approx(Math.exp(x))} sinh=${approx(Math.sinh(x))} cosh=${approx(Math.cosh(x))} tanh=${approx(Math.tanh(x))} expm1=${approx(Math.expm1(x))}")
    for x <- Seq(0.001, 0.5, 2.0, 10.0, 12345.678) do
      println(s"$x: log=${approx(Math.log(x))} log10=${approx(Math.log10(x))} log1p=${approx(Math.log1p(x))} sqrt=${approx(Math.sqrt(x))} cbrt=${approx(Math.cbrt(x))}")
    for x <- Seq(-1.0, -0.5, 0.0, 0.3, 1.0) do
      println(s"$x: asin=${approx(Math.asin(x))} acos=${approx(Math.acos(x))}")
    println(s"atan2 ${approx(Math.atan2(1, 2))} ${approx(Math.atan2(-3, -4))} pow ${approx(Math.pow(2, 0.5))} hypot ${approx(Math.hypot(1, 1))}")

  def strict(): Unit =
    println("== StrictMath")
    println(s"${StrictMath.sqrt(16)} ${StrictMath.abs(-3)} ${StrictMath.max(2, 9)} ${StrictMath.floor(-1.5)} ${StrictMath.ceil(-1.5)} ${StrictMath.round(2.5)} ${StrictMath.floorMod(-7, 3)} ${StrictMath.floorDiv(-7, 3)}")
    println(s"${approx(StrictMath.sin(1))} ${approx(StrictMath.exp(1))} ${approx(StrictMath.log(10))} ${StrictMath.pow(3, 4)} ${StrictMath.hypot(5, 12)} ${StrictMath.cbrt(64)} ${StrictMath.signum(-9.0)} ${StrictMath.rint(3.5)}")
    // fdlibm's results, bit for bit.
    val xs = Seq(0.5, 1.0, 1e-5, 0.1, 2.0, 3.0, 10.0, -0.75, 100.5, 12345.678, 1e22, 1e-300, 709.7, 0.3, 1.5707963267948966)
    for x <- xs do
      println(s"$x: sin=${StrictMath.sin(x)} cos=${StrictMath.cos(x)} tan=${StrictMath.tan(x)} atan=${StrictMath.atan(x)} exp=${StrictMath.exp(x)} " +
        s"expm1=${StrictMath.expm1(x)} sinh=${StrictMath.sinh(x)} cosh=${StrictMath.cosh(x)} tanh=${StrictMath.tanh(x)} cbrt=${StrictMath.cbrt(x)}")
      println(s"$x: log=${StrictMath.log(x)} log10=${StrictMath.log10(x)} log1p=${StrictMath.log1p(x)} asin=${StrictMath.asin(x / 100)} acos=${StrictMath.acos(x / 100)} " +
        s"pow=${StrictMath.pow(x, 2.7)} pow2=${StrictMath.pow(1.0001, x)} atan2=${StrictMath.atan2(x, 0.3)} hypot=${StrictMath.hypot(x, 7)}")
    println(s"StrictMath.cbrt ${StrictMath.cbrt(0.5)} ${StrictMath.cbrt(-3.0)} ${StrictMath.cbrt(1e-310)} ${StrictMath.pow(10, 308)} ${StrictMath.pow(1, Double.NaN)} ${StrictMath.pow(-1, Double.PositiveInfinity)}")
    attempt("StrictMath.addExact")(StrictMath.addExact(Int.MaxValue, 1))
    attempt("StrictMath.toIntExact")(StrictMath.toIntExact(Long.MaxValue))
