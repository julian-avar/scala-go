package java.math

import scala.annotation.static

/** java.math.MathContext: precision and rounding mode for BigDecimal arithmetic. */
final class MathContext(precision: Int, roundingMode: RoundingMode) extends Object with java.io.Serializable:
  if precision < 0 then throw new java.lang.IllegalArgumentException("Digits < 0")
  if roundingMode == null then throw new java.lang.NullPointerException("null RoundingMode")

  def this(precision: Int) = this(precision, RoundingMode.HALF_UP)

  def this(s: String) = this(MathContext.parsePrecision(s), MathContext.parseRoundingMode(s))

  def getPrecision(): Int = precision
  def getRoundingMode(): RoundingMode = roundingMode

  /** The settings, packed for BigDecimal's natives (see `Natives.scala`). */
  private[math] def goBits(): Long = (precision.toLong << 3) | roundingMode.ordinal()

  override def equals(other: Any): Boolean = other match
    case mc: MathContext => mc.getPrecision() == precision && (mc.getRoundingMode() eq roundingMode)
    case _               => false

  override def hashCode(): Int = roundingMode.hashCode() + (precision << 2)
  override def toString(): String = "precision=" + precision + " roundingMode=" + roundingMode

object MathContext:
  @static val UNLIMITED: MathContext = new MathContext(0, RoundingMode.HALF_UP)
  @static val DECIMAL32: MathContext = new MathContext(7, RoundingMode.HALF_EVEN)
  @static val DECIMAL64: MathContext = new MathContext(16, RoundingMode.HALF_EVEN)
  @static val DECIMAL128: MathContext = new MathContext(34, RoundingMode.HALF_EVEN)

  // "precision=<digits> roundingMode=<mode>", as toString writes it.
  private def badFormat(): Nothing = throw new java.lang.IllegalArgumentException("bad string format")

  private def fence(s: String): Int =
    if s == null then throw new java.lang.NullPointerException("null String")
    val fence = s.indexOf(' ')
    if !s.startsWith("precision=") || fence < 0 || !s.startsWith("roundingMode=", fence + 1) then badFormat()
    fence

  private def parsePrecision(s: String): Int =
    val digits = s.substring(10, fence(s))
    try Integer.parseInt(digits)
    catch case _: NumberFormatException => badFormat()

  private def parseRoundingMode(s: String): RoundingMode =
    try RoundingMode.valueOf(s.substring(fence(s) + 14))
    catch case _: IllegalArgumentException => badFormat()
