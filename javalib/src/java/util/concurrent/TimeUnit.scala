package java.util.concurrent

import scala.annotation.static

import java.time.Duration
import java.time.temporal.ChronoUnit

/** java.util.concurrent.TimeUnit. Conversions saturate, as in the JDK. */
final class TimeUnit private (name: String, ordinal: Int, private val nanosPerUnit: Long, private val chronoName: String)
    extends scalago.javalib.lang.Enum[TimeUnit](name, ordinal):
  def convert(duration: Long, unit: TimeUnit): Long = TimeUnit.scale(duration, unit.nanosPerUnit, nanosPerUnit)
  def toNanos(duration: Long): Long = TimeUnit.scale(duration, nanosPerUnit, 1L)
  def toMicros(duration: Long): Long = TimeUnit.scale(duration, nanosPerUnit, 1000L)
  def toMillis(duration: Long): Long = TimeUnit.scale(duration, nanosPerUnit, 1000000L)
  def toSeconds(duration: Long): Long = TimeUnit.scale(duration, nanosPerUnit, 1000000000L)
  def toMinutes(duration: Long): Long = TimeUnit.scale(duration, nanosPerUnit, 60000000000L)
  def toHours(duration: Long): Long = TimeUnit.scale(duration, nanosPerUnit, 3600000000000L)
  def toDays(duration: Long): Long = TimeUnit.scale(duration, nanosPerUnit, 86400000000000L)

  /** `duration` in this unit, saturating. */
  def convert(duration: Duration): Long =
    val SecondScale = 1000000000L
    var secs = duration.getSeconds
    var nano = duration.getNano.toLong
    if secs < 0 && nano > 0 then
      // A representation compatible with integer division.
      secs += 1
      nano -= SecondScale
    val secRatio = if nanosPerUnit >= SecondScale then nanosPerUnit / SecondScale else SecondScale / nanosPerUnit
    if nanosPerUnit >= SecondScale then secs / secRatio
    else
      val maxSecs = Long.MaxValue / secRatio
      val value = secs * secRatio + nano / nanosPerUnit
      if (secs < maxSecs && secs > -maxSecs) || (secs == maxSecs && value > 0) || (secs == -maxSecs && value < 0) then value
      else if secs > 0 then Long.MaxValue
      else Long.MinValue

  def sleep(timeout: Long): Unit =
    if timeout > 0 then
      val millis = toMillis(timeout)
      Thread.sleep(millis, TimeUnit.excessNanos(this, timeout, millis))

  def timedJoin(thread: Thread, timeout: Long): Unit =
    if timeout > 0 then
      val millis = toMillis(timeout)
      thread.join(millis, TimeUnit.excessNanos(this, timeout, millis))

  def timedWait(obj: Object, timeout: Long): Unit =
    if timeout > 0 then
      val millis = toMillis(timeout)
      obj.wait(millis, TimeUnit.excessNanos(this, timeout, millis))

  def toChronoUnit(): ChronoUnit = ChronoUnit.valueOf(chronoName)

object TimeUnit:
  @static val NANOSECONDS: TimeUnit = new TimeUnit("NANOSECONDS", 0, 1L, "NANOS")
  @static val MICROSECONDS: TimeUnit = new TimeUnit("MICROSECONDS", 1, 1000L, "MICROS")
  @static val MILLISECONDS: TimeUnit = new TimeUnit("MILLISECONDS", 2, 1000000L, "MILLIS")
  @static val SECONDS: TimeUnit = new TimeUnit("SECONDS", 3, 1000000000L, "SECONDS")
  @static val MINUTES: TimeUnit = new TimeUnit("MINUTES", 4, 60000000000L, "MINUTES")
  @static val HOURS: TimeUnit = new TimeUnit("HOURS", 5, 3600000000000L, "HOURS")
  @static val DAYS: TimeUnit = new TimeUnit("DAYS", 6, 86400000000000L, "DAYS")

  @static def values(): Array[TimeUnit] = Array(NANOSECONDS, MICROSECONDS, MILLISECONDS, SECONDS, MINUTES, HOURS, DAYS)

  @static def valueOf(name: String): TimeUnit =
    values().find(_.name() == name)
      .getOrElse(throw new java.lang.IllegalArgumentException("No enum constant java.util.concurrent.TimeUnit." + name))

  @static def of(chronoUnit: ChronoUnit): TimeUnit =
    val name = java.util.Objects.requireNonNull(chronoUnit, "chronoUnit").name()
    values().find(_.chronoName == name)
      .getOrElse(throw new java.lang.IllegalArgumentException("No TimeUnit equivalent for " + chronoUnit))

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

  /** The nanoseconds of `timeout` (in `unit`) beyond `millis`, for the `(millis, nanos)` waits. */
  private def excessNanos(unit: TimeUnit, timeout: Long, millis: Long): Int =
    if unit eq NANOSECONDS then (timeout - millis * 1000000L).toInt
    else if unit eq MICROSECONDS then ((timeout * 1000L) - millis * 1000000L).toInt
    else 0
