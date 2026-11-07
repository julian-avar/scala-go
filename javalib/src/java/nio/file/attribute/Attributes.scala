package java.nio.file.attribute

import scala.annotation.static

import java.util.concurrent.TimeUnit

trait FileAttribute[T]:
  def name(): String
  def value(): T

trait BasicFileAttributes:
  def lastModifiedTime(): FileTime
  def lastAccessTime(): FileTime
  def creationTime(): FileTime
  def isRegularFile(): Boolean
  def isDirectory(): Boolean
  def isSymbolicLink(): Boolean
  def isOther(): Boolean
  def size(): Long
  def fileKey(): Object

/** A file timestamp, with millisecond precision here. */
final class FileTime private (millis: Long) extends Object with Comparable[FileTime]:
  def toMillis(): Long = millis
  def to(unit: TimeUnit): Long = unit.convert(millis, TimeUnit.MILLISECONDS)

  def compareTo(other: FileTime): Int = java.lang.Long.compare(millis, other.toMillis())
  override def equals(obj: Any): Boolean = obj match
    case other: FileTime => millis == other.toMillis()
    case _               => false
  override def hashCode(): Int = java.lang.Long.hashCode(millis)

  /** ISO 8601 in UTC, as the JDK: `2024-01-31T12:34:56.789Z` (fraction only if not zero). */
  override def toString(): String =
    val days = Math.floorDiv(millis, 86400000L)
    val msOfDay = Math.floorMod(millis, 86400000L)
    // Civil date from days since 1970-01-01 (Howard Hinnant's algorithm).
    val z = days + 719468
    val era = Math.floorDiv(z, 146097L)
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = if mp < 10 then mp + 3 else mp - 9
    val year = yoe + era * 400 + (if month <= 2 then 1 else 0)
    def two(n: Long) = if n < 10 then "0" + n else n.toString
    val ms = msOfDay % 1000
    val fraction =
      if ms == 0 then ""
      else
        var f = (1000 + ms).toString.substring(1)
        while f.endsWith("0") do f = f.substring(0, f.length - 1)
        "." + f
    year.toString + "-" + two(month) + "-" + two(day) + "T" + two(msOfDay / 3600000) + ":" +
      two(msOfDay / 60000 % 60) + ":" + two(msOfDay / 1000 % 60) + fraction + "Z"

object FileTime:
  @static def fromMillis(value: Long): FileTime = new FileTime(value)
  @static def from(value: Long, unit: TimeUnit): FileTime = new FileTime(unit.toMillis(value))
