package java.nio.file.attribute

import scala.annotation.static

import java.util.concurrent.TimeUnit

final class PosixFilePermission private (name: String, ordinal: Int)
    extends scalago.javalib.lang.Enum[PosixFilePermission](name, ordinal)

object PosixFilePermission:
  @static val OWNER_READ: PosixFilePermission = new PosixFilePermission("OWNER_READ", 0)
  @static val OWNER_WRITE: PosixFilePermission = new PosixFilePermission("OWNER_WRITE", 1)
  @static val OWNER_EXECUTE: PosixFilePermission = new PosixFilePermission("OWNER_EXECUTE", 2)
  @static val GROUP_READ: PosixFilePermission = new PosixFilePermission("GROUP_READ", 3)
  @static val GROUP_WRITE: PosixFilePermission = new PosixFilePermission("GROUP_WRITE", 4)
  @static val GROUP_EXECUTE: PosixFilePermission = new PosixFilePermission("GROUP_EXECUTE", 5)
  @static val OTHERS_READ: PosixFilePermission = new PosixFilePermission("OTHERS_READ", 6)
  @static val OTHERS_WRITE: PosixFilePermission = new PosixFilePermission("OTHERS_WRITE", 7)
  @static val OTHERS_EXECUTE: PosixFilePermission = new PosixFilePermission("OTHERS_EXECUTE", 8)

  @static def values(): Array[PosixFilePermission] = Array(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE, GROUP_READ,
    GROUP_WRITE, GROUP_EXECUTE, OTHERS_READ, OTHERS_WRITE, OTHERS_EXECUTE)

  @static def valueOf(name: String): PosixFilePermission =
    values().find(_.name() == name).getOrElse(
      throw new IllegalArgumentException("No enum constant java.nio.file.attribute.PosixFilePermission." + name))

/** Permissions as sets and as `rwxr-x---` strings; values() is in the order of the mode bits,
 *  from 0400 down to 0001. Static methods through the forwarders of the object's mirror class (a
 *  companion class would make `toString` conflict).
 */
object PosixFilePermissions:
  private def bit(p: PosixFilePermission): Int = 0x100 >> p.ordinal()

  def toString(perms: java.util.Set[PosixFilePermission]): String =
    val bits = toBits(perms)
    val sb = new java.lang.StringBuilder(9)
    for p <- PosixFilePermission.values() do
      sb.append(if (bits & bit(p)) == 0 then '-' else "rwx".charAt(p.ordinal() % 3))
    sb.toString()

  def fromString(perms: String): java.util.Set[PosixFilePermission] =
    def invalid() = throw new IllegalArgumentException("Invalid mode")
    if perms.length() != 9 then invalid()
    var bits = 0
    for p <- PosixFilePermission.values() do
      val c = perms.charAt(p.ordinal())
      if c == "rwx".charAt(p.ordinal() % 3) then bits |= bit(p)
      else if c != '-' then invalid()
    fromBits(bits)

  def asFileAttribute(perms: java.util.Set[PosixFilePermission]): FileAttribute[java.util.Set[PosixFilePermission]] =
    val copy = new java.util.HashSet[PosixFilePermission](perms)
    new FileAttribute[java.util.Set[PosixFilePermission]]:
      def name(): String = "posix:permissions"
      def value(): java.util.Set[PosixFilePermission] = java.util.Collections.unmodifiableSet(copy)

  private[java] def fromBits(bits: Int): java.util.Set[PosixFilePermission] =
    val set = new java.util.HashSet[PosixFilePermission]()
    for p <- PosixFilePermission.values() do if (bits & bit(p)) != 0 then set.add(p)
    set

  private[java] def toBits(perms: java.util.Set[PosixFilePermission]): Int =
    var bits = 0
    val it = perms.iterator()
    while it.hasNext() do bits |= bit(it.next())
    bits

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
