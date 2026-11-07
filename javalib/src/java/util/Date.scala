package java.util

import java.time.{Instant, LocalDateTime}

import scala.annotation.static

/** java.util.Date: an instant in milliseconds. The deprecated calendar methods (`getYear`,
 *  `getMonth`, ...) and parsing are not implemented.
 */
class Date(private var time: Long) extends Object with java.io.Serializable with Cloneable with Comparable[Date]:
  def this() = this(System.currentTimeMillis())

  def getTime(): Long = time
  def setTime(time: Long): Unit = this.time = time

  def before(when: Date): Boolean = time < when.getTime()
  def after(when: Date): Boolean = time > when.getTime()
  def compareTo(other: Date): Int = java.lang.Long.compare(time, other.getTime())

  def toInstant(): Instant = Instant.ofEpochMilli(time)

  override def equals(obj: Any): Boolean = obj match
    case that: Date => time == that.getTime()
    case _          => false

  override def hashCode(): Int = (time ^ (time >>> 32)).toInt

  override def clone(): Object = super.clone()

  /** `EEE MMM dd HH:mm:ss zzz yyyy` in the default time zone, as the JDK writes it. */
  override def toString(): String =
    val zone = TimeZone.getDefault()
    val instant = toInstant()
    val local = LocalDateTime.ofInstant(instant, zone.toZoneId())
    val name = zone match
      case z: ZoneTimeZone => z.displayName(instant)
      case z               => z.getDisplayName(z.inDaylightTime(this), TimeZone.SHORT)
    val sb = new java.lang.StringBuilder(28)
    sb.append(Date.Days(local.getDayOfWeek.ordinal)).append(' ')
    sb.append(Date.Months(local.getMonthValue - 1)).append(' ')
    Date.pad2(sb, local.getDayOfMonth).append(' ')
    Date.pad2(sb, local.getHour).append(':')
    Date.pad2(sb, local.getMinute).append(':')
    Date.pad2(sb, local.getSecond).append(' ')
    sb.append(name).append(' ').append(local.getYear)
    sb.toString

object Date:
  @static def from(instant: Instant): Date =
    try new Date(instant.toEpochMilli)
    catch case e: ArithmeticException => throw new IllegalArgumentException(e)

  private val Days = Array("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
  private val Months = Array("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

  private def pad2(sb: java.lang.StringBuilder, n: Int): java.lang.StringBuilder =
    if n < 10 then sb.append('0')
    sb.append(n)
