package java.math

import scala.annotation.static

/** java.math.RoundingMode. The ordinals are BigDecimal's legacy `ROUND_*` constants. */
final class RoundingMode private (name: String, ordinal: Int)
    extends scalago.javalib.lang.Enum[RoundingMode](name, ordinal)

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

  @static def valueOf(name: String): RoundingMode =
    if name == null then throw new java.lang.NullPointerException("Name is null")
    values().find(_.name() == name).getOrElse(
      throw new java.lang.IllegalArgumentException("No enum constant java.math.RoundingMode." + name))

  /** The mode of a legacy `BigDecimal.ROUND_*` constant. */
  @static def valueOf(rm: Int): RoundingMode =
    if rm < 0 || rm > 7 then throw new java.lang.IllegalArgumentException("argument out of range")
    values()(rm)
