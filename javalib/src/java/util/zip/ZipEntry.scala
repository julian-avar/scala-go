package java.util.zip

import java.nio.file.attribute.FileTime
import java.time.{Instant, LocalDateTime, ZoneId}
import java.util.concurrent.TimeUnit

import ZipConstants.*

/** An entry of a zip file, as the JDK's: times are DOS times in the local time zone, with the
 *  Info-ZIP extended timestamps for times DOS cannot represent or that are set explicitly.
 */
class ZipEntry(name: String) extends Cloneable:
  if name == null then throw new NullPointerException("name")
  if name.length > 0xffff then throw new IllegalArgumentException("entry name too long")

  private[zip] var xdostime = -1L
  private[zip] var mtime: FileTime = null
  private[zip] var atime: FileTime = null
  private[zip] var ctime: FileTime = null
  private[zip] var crc = -1L
  private[zip] var size = -1L
  private[zip] var csize = -1L
  private[zip] var csizeSet = false
  private[zip] var method = -1
  private[zip] var flag = 0
  private[zip] var extra: Array[Byte] = null
  private[zip] var comment: String = null

  def this(e: ZipEntry) =
    this(java.util.Objects.requireNonNull(e, "entry").getName())
    copyFrom(e)

  private def copyFrom(e: ZipEntry): Unit =
    xdostime = e.xdostime
    mtime = e.mtime
    atime = e.atime
    ctime = e.ctime
    crc = e.crc
    size = e.size
    csize = e.csize
    csizeSet = e.csizeSet
    method = e.method
    flag = e.flag
    extra = e.extra
    comment = e.comment

  def getName(): String = name

  def setTime(time: Long): Unit =
    xdostime = ZipEntry.javaToExtendedDosTime(time)
    val year = ZipEntry.localDateTime(time).getYear
    mtime = if xdostime != ZipEntry.DosTimeBefore1980 && year <= 2099 then null else FileTime.fromMillis(time)

  def getTime(): Long =
    if mtime != null then mtime.toMillis()
    else if xdostime != -1 then ZipEntry.extendedDosToJavaTime(xdostime)
    else -1

  def setTimeLocal(time: LocalDateTime): Unit =
    val year = time.getYear - 1980
    if year < 0 then xdostime = ZipEntry.DosTimeBefore1980
    else
      xdostime = (((if year > 0x7f then 0x7f else year).toLong << 25) | (time.getMonthValue << 21) |
        (time.getDayOfMonth << 16) | (time.getHour << 11) | (time.getMinute << 5) | (time.getSecond >> 1)) +
        ((time.getSecond & 0x1).toLong * 1000L << 32) + ((time.getNano / 1000000L) << 32)
    if xdostime != ZipEntry.DosTimeBefore1980 && year <= 0x7f then mtime = null
    else mtime = FileTime.fromMillis(time.atZone(ZoneId.systemDefault).toInstant.toEpochMilli)

  def getTimeLocal(): LocalDateTime =
    if mtime != null then ZipEntry.localDateTime(mtime.toMillis())
    else
      val ms = xdostime >> 32
      ZipEntry.dosToLocalDateTime(xdostime).plusNanos(ms * 1000000L)

  def setLastModifiedTime(time: FileTime): ZipEntry =
    mtime = java.util.Objects.requireNonNull(time, "lastModifiedTime")
    xdostime = ZipEntry.javaToExtendedDosTime(time.to(TimeUnit.MILLISECONDS))
    this

  def getLastModifiedTime(): FileTime =
    if mtime != null then mtime
    else if xdostime == -1 then null
    else FileTime.fromMillis(getTime())

  def setLastAccessTime(time: FileTime): ZipEntry =
    atime = java.util.Objects.requireNonNull(time, "lastAccessTime")
    this

  def getLastAccessTime(): FileTime = atime

  def setCreationTime(time: FileTime): ZipEntry =
    ctime = java.util.Objects.requireNonNull(time, "creationTime")
    this

  def getCreationTime(): FileTime = ctime

  def setSize(size: Long): Unit =
    if size < 0 then throw new IllegalArgumentException("invalid entry size")
    this.size = size

  def getSize(): Long = size
  def getCompressedSize(): Long = csize

  def setCompressedSize(csize: Long): Unit =
    this.csize = csize
    csizeSet = true

  def setCrc(crc: Long): Unit =
    if crc < 0 || crc > 0xffffffffL then throw new IllegalArgumentException("invalid entry crc-32")
    this.crc = crc

  def getCrc(): Long = crc

  def setMethod(method: Int): Unit =
    if method != ZipEntry.STORED && method != ZipEntry.DEFLATED then
      throw new IllegalArgumentException("invalid compression method")
    this.method = method

  def getMethod(): Int = method

  def setExtra(extra: Array[Byte]): Unit = setExtra0(extra, false, true)

  /** Sets the extra data, taking the extended timestamps and (for a local header with `zip64`)
   *  the 64-bit sizes from it.
   */
  private[zip] def setExtra0(extra: Array[Byte], zip64: Boolean, isLOC: Boolean): Unit =
    if extra != null then
      if extra.length > 0xffff then throw new IllegalArgumentException("invalid extra field length")
      var off = 0
      val len = extra.length
      while off + 4 <= len do
        val tag = ZipUtils.u16(extra, off)
        val sz = ZipUtils.u16(extra, off + 2)
        off += 4
        if off + sz > len then off = len // invalid data
        else
          tag match
            case ExtIdZip64 if zip64 && isLOC =>
              // A local header's ZIP64 field holds both sizes.
              if sz >= 16 then
                size = ZipUtils.u64(extra, off)
                csize = ZipUtils.u64(extra, off + 8)
            case ExtIdExtT =>
              val flags = extra(off) & 0xff
              var pos = 1
              // The CEN's extended timestamp has the modification time only.
              if (flags & 0x1) != 0 && pos + 4 <= sz then
                mtime = FileTime.from(ZipUtils.u32(extra, off + pos).toInt.toLong, TimeUnit.SECONDS)
                pos += 4
              if isLOC then
                if (flags & 0x2) != 0 && pos + 4 <= sz then
                  atime = FileTime.from(ZipUtils.u32(extra, off + pos).toInt.toLong, TimeUnit.SECONDS)
                  pos += 4
                if (flags & 0x4) != 0 && pos + 4 <= sz then
                  ctime = FileTime.from(ZipUtils.u32(extra, off + pos).toInt.toLong, TimeUnit.SECONDS)
                  pos += 4
            case ExtIdNTFS if sz >= 32 && ZipUtils.u16(extra, off + 4) == 0x0001 && ZipUtils.u16(extra, off + 6) == 24 =>
              // Windows times (100 ns units since 1601), after 4 reserved bytes and the attribute's tag and size.
              def time(pos: Int): FileTime =
                val w = ZipUtils.u64(extra, pos)
                if w == Long.MinValue then null else FileTime.fromMillis(Math.floorDiv(w, 10000L) + WindowsEpochMillis)
              val m = time(off + 8)
              if m != null then mtime = m
              val a = time(off + 16)
              if a != null then atime = a
              val c = time(off + 24)
              if c != null then ctime = c
            case _ =>
          off += sz
    this.extra = extra

  def getExtra(): Array[Byte] = extra

  def setComment(comment: String): Unit = this.comment = comment
  def getComment(): String = comment

  def isDirectory(): Boolean = name.endsWith("/")

  override def toString(): String = getName()
  override def hashCode(): Int = name.hashCode()

  override def clone(): Object =
    val e = new ZipEntry(name)
    e.copyFrom(this)
    if extra != null then e.extra = extra.clone()
    e

object ZipEntry:
  final val STORED = 0
  final val DEFLATED = 8

  // The JDK's ZipConstants, which ZipEntry and the streams implement.
  final val LOCSIG = 0x04034b50L
  final val EXTSIG = 0x08074b50L
  final val CENSIG = 0x02014b50L
  final val ENDSIG = 0x06054b50L
  final val LOCHDR = 30
  final val EXTHDR = 16
  final val CENHDR = 46
  final val ENDHDR = 22

  /** The DOS time of 1980-01-01 00:00, which stands for earlier times. */
  private[zip] final val DosTimeBefore1980 = (1L << 21) | (1L << 16)

  private[zip] def localDateTime(millis: Long): LocalDateTime =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault)

  /** The DOS time (low 32 bits) and the milliseconds it drops (high bits). */
  private[zip] def javaToExtendedDosTime(time: Long): Long =
    val ldt = localDateTime(time)
    if ldt.getYear >= 1980 then
      val year = Math.min(ldt.getYear - 1980, 0x7f)
      ((year.toLong << 25) | (ldt.getMonthValue << 21) | (ldt.getDayOfMonth << 16) | (ldt.getHour << 11) |
        (ldt.getMinute << 5) | (ldt.getSecond >> 1)) + ((time % 2000) << 32)
    else DosTimeBefore1980

  private[zip] def dosToLocalDateTime(dtime: Long): LocalDateTime =
    val year = (((dtime >> 25) & 0x7f) + 1980).toInt
    val month = ((dtime >> 21) & 0x0f).toInt
    val day = ((dtime >> 16) & 0x1f).toInt
    val hour = ((dtime >> 11) & 0x1f).toInt
    val minute = ((dtime >> 5) & 0x3f).toInt
    val second = ((dtime << 1) & 0x3e).toInt
    if month > 0 && month < 13 && day > 0 && hour < 24 && minute < 60 && second < 60 then
      try return LocalDateTime.of(year, month, day, hour, minute, second)
      catch case _: java.time.DateTimeException => ()
    // Out-of-range fields roll over, as java.util.Date's do.
    LocalDateTime.of(year, 1, 1, 0, 0, 0).plusMonths(month - 1L).plusDays(day - 1L).plusHours(hour)
      .plusMinutes(minute).plusSeconds(second)

  private[zip] def dosToJavaTime(dtime: Long): Long =
    val ldt = dosToLocalDateTime(dtime)
    ldt.toEpochSecond(ZoneId.systemDefault.getRules.getOffset(ldt)) * 1000L

  private[zip] def extendedDosToJavaTime(xdostime: Long): Long = dosToJavaTime(xdostime) + (xdostime >> 32)

private[zip] object ZipConstants:
  final val ExtIdZip64 = 0x0001
  final val ExtIdExtT = 0x5455
  final val ExtIdNTFS = 0x000a
  /** 1601-01-01, the Windows epoch, in Java's milliseconds. */
  final val WindowsEpochMillis = -11644473600000L
  final val Zip64MagicVal = 0xffffffffL
  /** The general purpose flag bit for UTF-8 names and comments. */
  final val UseUTF8 = 0x800
