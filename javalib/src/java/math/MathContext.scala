package java.math

import scala.annotation.static

/** java.math.RoundingMode (a Java enum). */
final class RoundingMode private (private val modeName: String, private val modeOrdinal: Int)
    extends Object
    with java.io.Serializable:
  def ordinal(): Int = modeOrdinal
  def name(): String = modeName
  override def toString(): String = modeName

object RoundingMode:
  @static val UP: RoundingMode = new RoundingMode("UP", 0)
  @static val DOWN: RoundingMode = new RoundingMode("DOWN", 1)
  @static val CEILING: RoundingMode = new RoundingMode("CEILING", 2)
  @static val FLOOR: RoundingMode = new RoundingMode("FLOOR", 3)
  @static val HALF_UP: RoundingMode = new RoundingMode("HALF_UP", 4)
  @static val HALF_DOWN: RoundingMode = new RoundingMode("HALF_DOWN", 5)
  @static val HALF_EVEN: RoundingMode = new RoundingMode("HALF_EVEN", 6)
  @static val UNNECESSARY: RoundingMode = new RoundingMode("UNNECESSARY", 7)

  @static def values(): Array[RoundingMode] =
    Array(UP, DOWN, CEILING, FLOOR, HALF_UP, HALF_DOWN, HALF_EVEN, UNNECESSARY)

/** java.math.MathContext: precision and rounding mode for decimal arithmetic. */
final class MathContext(private val precision: Int, private val roundingMode: RoundingMode)
    extends Object
    with java.io.Serializable:
  def this(precision: Int) = this(precision, RoundingMode.HALF_UP)

  def getPrecision(): Int = precision
  def getRoundingMode(): RoundingMode = roundingMode

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
