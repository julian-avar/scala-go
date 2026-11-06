package scalago.javalib.util.concurrent

import scala.annotation.static

/** java.util.concurrent.TimeUnit. */
final class TimeUnit private (name: String, ordinal: Int, private val nanosPerUnit: Long)
    extends scalago.javalib.lang.Enum[TimeUnit](name, ordinal):
  def convert(duration: Long, unit: TimeUnit): Long = TimeUnit.scale(duration, unit.nanosPerUnit, nanosPerUnit)
  def toNanos(duration: Long): Long = TimeUnit.scale(duration, nanosPerUnit, 1L)
  def toMicros(duration: Long): Long = TimeUnit.scale(duration, nanosPerUnit, 1000L)
  def toMillis(duration: Long): Long = TimeUnit.scale(duration, nanosPerUnit, 1000000L)
  def toSeconds(duration: Long): Long = TimeUnit.scale(duration, nanosPerUnit, 1000000000L)
  def toMinutes(duration: Long): Long = TimeUnit.scale(duration, nanosPerUnit, 60000000000L)
  def toHours(duration: Long): Long = TimeUnit.scale(duration, nanosPerUnit, 3600000000000L)
  def toDays(duration: Long): Long = TimeUnit.scale(duration, nanosPerUnit, 86400000000000L)
  def sleep(timeout: Long): Unit = if timeout > 0 then java.lang.Thread.sleep(toMillis(timeout))

object TimeUnit:
  @static val NANOSECONDS: TimeUnit = new TimeUnit("NANOSECONDS", 0, 1L)
  @static val MICROSECONDS: TimeUnit = new TimeUnit("MICROSECONDS", 1, 1000L)
  @static val MILLISECONDS: TimeUnit = new TimeUnit("MILLISECONDS", 2, 1000000L)
  @static val SECONDS: TimeUnit = new TimeUnit("SECONDS", 3, 1000000000L)
  @static val MINUTES: TimeUnit = new TimeUnit("MINUTES", 4, 60000000000L)
  @static val HOURS: TimeUnit = new TimeUnit("HOURS", 5, 3600000000000L)
  @static val DAYS: TimeUnit = new TimeUnit("DAYS", 6, 86400000000000L)

  @static def values(): Array[TimeUnit] = Array(NANOSECONDS, MICROSECONDS, MILLISECONDS, SECONDS, MINUTES, HOURS, DAYS)

  @static def valueOf(name: String): TimeUnit =
    values().find(_.name() == name).getOrElse(throw new java.lang.IllegalArgumentException("No enum constant " + name))

  /** `duration` from units of `src` nanoseconds to units of `dst`, saturating like the JDK. */
  private def scale(duration: Long, src: Long, dst: Long): Long =
    if src == dst then duration
    else if src < dst then duration / (dst / src)
    else
      val ratio = src / dst
      val max = Long.MaxValue / ratio
      if duration > max then Long.MaxValue
      else if duration < -max then Long.MinValue
      else duration * ratio
