package java.util

import java.time.{Instant, ZoneId, ZoneOffset}

import scala.annotation.static

/** java.util.TimeZone, on java.time's zones (so on Go's tz database).
 *
 *  There is no locale data: display names are the tz database's abbreviations ("EST", "CEST"),
 *  for the long style too, and `GMT+03:00` where the abbreviation is numeric ("+03"), as the JDK
 *  writes zones it has no name for.
 */
abstract class TimeZone extends Object with java.io.Serializable with Cloneable:
  private var id: String = null

  def getID(): String = id
  def setID(id: String): Unit =
    if id == null then throw new NullPointerException()
    this.id = id

  def getOffset(era: Int, year: Int, month: Int, day: Int, dayOfWeek: Int, milliseconds: Int): Int
  def getOffset(date: Long): Int = if inDaylightTime(new Date(date)) then getRawOffset() + getDSTSavings() else getRawOffset()
  def getRawOffset(): Int
  def setRawOffset(offsetMillis: Int): Unit
  def useDaylightTime(): Boolean
  def observesDaylightTime(): Boolean = useDaylightTime() || inDaylightTime(new Date())
  def inDaylightTime(date: Date): Boolean
  def getDSTSavings(): Int = if useDaylightTime() then 3600000 else 0

  final def getDisplayName(): String = getDisplayName(false, TimeZone.LONG, Locale.getDefault())
  final def getDisplayName(locale: Locale): String = getDisplayName(false, TimeZone.LONG, locale)
  final def getDisplayName(daylight: Boolean, style: Int): String = getDisplayName(daylight, style, Locale.getDefault())
  def getDisplayName(daylight: Boolean, style: Int, locale: Locale): String =
    if style != TimeZone.SHORT && style != TimeZone.LONG then
      throw new IllegalArgumentException("Illegal style: " + style)
    TimeZone.gmtName((getRawOffset() + (if daylight then getDSTSavings() else 0)) / 1000)

  def hasSameRules(other: TimeZone): Boolean =
    other != null && getRawOffset() == other.getRawOffset() && useDaylightTime() == other.useDaylightTime()

  def toZoneId(): ZoneId = ZoneId.of(getID(), ZoneId.SHORT_IDS)

  override def clone(): Object = super.clone()

object TimeZone:
  @static final val SHORT = 0
  @static final val LONG = 1

  @volatile private var default: TimeZone = null

  /** A copy of the default time zone: the system's (see scalago.runtime.Time.systemZoneId),
   *  unless the program set another one.
   */
  @static def getDefault(): TimeZone =
    var zone = default
    if zone == null then
      zone = getTimeZone(scalago.runtime.Time.systemZoneId())
      default = zone
    zone.clone().asInstanceOf[TimeZone]

  @static def setDefault(zone: TimeZone): Unit =
    default = if zone == null then null else zone.clone().asInstanceOf[TimeZone]

  /** The zone with the given ID (a region, a JDK short ID such as "PST", or "GMT+hh:mm"); the
   *  GMT zone if there is no such zone, as on the JDK.
   */
  @static def getTimeZone(id: String): TimeZone =
    if id == null then throw new NullPointerException()
    id match
      case CustomID(sign, hours, minutes) =>
        val h = hours.toInt
        val m = if minutes == null then 0 else minutes.toInt
        if h > 23 || m > 59 then gmt
        else
          val seconds = (h * 3600 + m * 60) * (if sign == "-" then -1 else 1)
          if seconds == 0 then new ZoneTimeZone("GMT", ZoneOffset.UTC)
          else new ZoneTimeZone(gmtName(seconds), ZoneOffset.ofTotalSeconds(seconds))
      case _ if id.isEmpty || id.charAt(0) == '+' || id.charAt(0) == '-' || id == "Z" => gmt
      case _ =>
        try new ZoneTimeZone(id, ZoneId.of(id, ZoneId.SHORT_IDS))
        catch case _: java.time.DateTimeException => gmt

  @static def getTimeZone(zoneId: ZoneId): TimeZone =
    val id = zoneId.getId
    if id == "Z" then getTimeZone("UTC")
    else if id.charAt(0) == '+' || id.charAt(0) == '-' then getTimeZone("GMT" + id)
    else getTimeZone(id)

  @static def getAvailableIDs(): Array[String] =
    val ids = ZoneId.getAvailableZoneIds.toArray(new Array[String](0))
    Arrays.sort(ids.asInstanceOf[Array[Object]])
    ids

  @static def getAvailableIDs(rawOffset: Int): Array[String] =
    getAvailableIDs().filter(getTimeZone(_).getRawOffset() == rawOffset)

  private val CustomID = """GMT([+-])(\d{1,2})(?::?(\d{2}))?""".r

  private def gmt: TimeZone = new ZoneTimeZone("GMT", ZoneOffset.UTC)

  /** "GMT+05:30" ("GMT" for 0), as the JDK names zones it has no name for. */
  private[util] def gmtName(offsetSeconds: Int): String =
    if offsetSeconds == 0 then "GMT"
    else
      val abs = Math.abs(offsetSeconds) / 60
      f"GMT${if offsetSeconds < 0 then '-' else '+'}${abs / 60}%02d:${abs % 60}%02d"

/** A TimeZone on a java.time zone (the JDK's equivalent is `sun.util.calendar.ZoneInfo`). */
private[util] final class ZoneTimeZone(id: String, private var zone: ZoneId) extends TimeZone:
  setID(id)

  private def rules = zone.getRules
  private def offsetMillis(instant: Instant): Int = rules.getOffset(instant).getTotalSeconds * 1000

  def getOffset(era: Int, year: Int, month: Int, day: Int, dayOfWeek: Int, milliseconds: Int): Int =
    val y = if era == 0 then 1 - year else year // GregorianCalendar.BC is 0
    val local = java.time.LocalDate.of(y, month + 1, day).atStartOfDay.plusNanos(milliseconds * 1000000L)
    rules.getOffset(local).getTotalSeconds * 1000

  override def getOffset(date: Long): Int = offsetMillis(Instant.ofEpochMilli(date))

  def getRawOffset(): Int = rules.getStandardOffset(Instant.now).getTotalSeconds * 1000

  def setRawOffset(offsetMillis: Int): Unit = zone = ZoneOffset.ofTotalSeconds(offsetMillis / 1000)

  def useDaylightTime(): Boolean =
    val now = Instant.now
    rules.isDaylightSavings(now) || {
      val next = rules.nextTransition(now)
      next != null && rules.isDaylightSavings(next.getInstant)
    }

  def inDaylightTime(date: Date): Boolean = rules.isDaylightSavings(date.toInstant())

  override def getDSTSavings(): Int =
    if !useDaylightTime() then 0
    else
      val now = Instant.now
      val dst = if rules.isDaylightSavings(now) then now else rules.nextTransition(now).getInstant
      rules.getDaylightSavings(dst).toMillis.toInt

  /** The tz database's abbreviation, now, or at the next transition if daylight saving time is
   *  not in effect now as asked.
   */
  override def getDisplayName(daylight: Boolean, style: Int, locale: Locale): String =
    if style != TimeZone.SHORT && style != TimeZone.LONG then
      throw new IllegalArgumentException("Illegal style: " + style)
    val now = Instant.now
    val next = if rules.isDaylightSavings(now) == daylight then null else rules.nextTransition(now)
    displayName(if next == null then now else next.getInstant)

  /** The abbreviation in effect at `instant`. */
  private[util] def displayName(instant: Instant): String =
    zone match
      case offset: ZoneOffset => TimeZone.gmtName(offset.getTotalSeconds)
      case region =>
        val goZone = scalago.runtime.Time.zone(region.getId)
        if goZone == null then TimeZone.gmtName(rules.getOffset(instant).getTotalSeconds)
        else displayName(goZone, instant)

  private def displayName(goZone: Object, instant: Instant): String =
    val name = scalago.runtime.Time.abbreviation(goZone, instant.getEpochSecond)
    if name.isEmpty || name.charAt(0) == '+' || name.charAt(0) == '-' then
      TimeZone.gmtName(rules.getOffset(instant).getTotalSeconds)
    else name

  override def hasSameRules(other: TimeZone): Boolean = other match
    case that: ZoneTimeZone => rules == that.rules
    case _                  => super.hasSameRules(other)

  override def equals(other: Any): Boolean = other match
    case that: ZoneTimeZone => getID() == that.getID() && rules == that.rules
    case _                  => false

  override def hashCode(): Int = getID().hashCode()

  override def toString(): String =
    s"${getClass.getName}[id=\"${getID()}\",offset=${getRawOffset()},dstSavings=${getDSTSavings()},useDaylight=${useDaylightTime()}]"
