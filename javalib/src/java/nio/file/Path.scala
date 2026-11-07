package java.nio.file

import scala.annotation.static

import java.io.File

import scalago.runtime.OS

/** java.nio.file.Path, for the default (Unix) file system. URIs, watch services and other file
 *  systems are not supported.
 */
trait Path extends Comparable[Path] with java.lang.Iterable[Path]:
  def getFileSystem(): FileSystem
  def compareTo(other: Path): Int
  def getFileName(): Path
  def getParent(): Path
  def getRoot(): Path
  def getNameCount(): Int
  def getName(index: Int): Path
  def subpath(beginIndex: Int, endIndex: Int): Path
  def isAbsolute(): Boolean
  def startsWith(other: Path): Boolean
  def startsWith(other: String): Boolean = startsWith(new UnixPath(other))
  def endsWith(other: Path): Boolean
  def endsWith(other: String): Boolean = endsWith(new UnixPath(other))
  def normalize(): Path
  def resolve(other: Path): Path
  def resolve(other: String): Path = resolve(new UnixPath(other))
  def resolveSibling(other: Path): Path =
    val parent = getParent()
    if parent == null then other else parent.resolve(other)
  def resolveSibling(other: String): Path = resolveSibling(new UnixPath(other))
  def relativize(other: Path): Path
  def toAbsolutePath(): Path
  def toRealPath(options: Array[LinkOption]): Path
  def toFile(): File
  def iterator(): java.util.Iterator[Path]

object Path:
  /** Joins the non-empty parts with `/`, as the JDK's Unix file system. */
  @static def of(first: String, more: Array[String]): Path =
    if more.length == 0 then new UnixPath(first)
    else
      val sb = new java.lang.StringBuilder(first)
      for segment <- more do
        if !segment.isEmpty() then
          if sb.length() > 0 then sb.append('/')
          sb.append(segment)
      new UnixPath(sb.toString())

final class Paths private ()

object Paths:
  @static def get(first: String, more: Array[String]): Path = Path.of(first, more)

/** A path of the default file system: its string, normalized as by the JDK (no repeated or
 *  trailing slashes), and its name elements.
 */
private[java] final class UnixPath(input: String) extends Path:
  private val path: String = UnixPath.normalize(input)

  def getFileSystem(): FileSystem = UnixFileSystem

  /** Start offsets of the name elements. */
  private lazy val offsets: Array[Int] =
    if path.isEmpty() then Array(0)
    else
      val starts = Array.newBuilder[Int]
      var i = 0
      while i < path.length() do
        if path.charAt(i) != '/' && (i == 0 || path.charAt(i - 1) == '/') then starts += i
        i += 1
      starts.result()

  private def element(i: Int): String =
    val start = offsets(i)
    val end = if i + 1 < offsets.length then offsets(i + 1) - 1 else path.length()
    path.substring(start, end)

  def isAbsolute(): Boolean = path.startsWith("/")
  def getRoot(): Path = if isAbsolute() then new UnixPath("/") else null
  def getNameCount(): Int = offsets.length

  def getFileName(): Path =
    val n = getNameCount()
    if n == 0 then null
    else if n == 1 && !isAbsolute() then this
    else new UnixPath(element(n - 1))

  def getParent(): Path =
    val n = getNameCount()
    if n == 0 then null
    else if n == 1 then getRoot()
    else new UnixPath(path.substring(0, offsets(n - 1) - 1))

  def getName(index: Int): Path =
    if index < 0 || index >= getNameCount() then throw new IllegalArgumentException()
    new UnixPath(element(index))

  def subpath(beginIndex: Int, endIndex: Int): Path =
    if beginIndex < 0 || beginIndex >= getNameCount() || endIndex > getNameCount() || beginIndex >= endIndex then
      throw new IllegalArgumentException()
    val end = if endIndex < offsets.length then offsets(endIndex) - 1 else path.length()
    new UnixPath(path.substring(offsets(beginIndex), end))


  def startsWith(other: Path): Boolean =
    val that = UnixPath.of(other)
    if that.isAbsolute() != isAbsolute() || that.getNameCount() > getNameCount() then false
    else if that.path.isEmpty() then path.isEmpty()
    else sameNames(0, that, 0, that.getNameCount())

  def endsWith(other: Path): Boolean =
    val that = UnixPath.of(other)
    val n = getNameCount()
    val m = that.getNameCount()
    if that.isAbsolute() then this == that
    else if m > n then false
    else if that.path.isEmpty() then path.isEmpty()
    else sameNames(n - m, that, 0, m)

  /** Whether `count` names from `from` equal those of `that` from `thatFrom`. */
  private def sameNames(from: Int, that: UnixPath, thatFrom: Int, count: Int): Boolean =
    var i = 0
    while i < count && element(from + i) == that.element(thatFrom + i) do i += 1
    i == count

  def normalize(): Path =
    val out = new Array[String](getNameCount())
    var n = 0
    var i = 0
    while i < getNameCount() do
      val name = element(i)
      if name == "." then ()
      else if name == ".." then
        if n > 0 && out(n - 1) != ".." then n -= 1
        else if !isAbsolute() then
          out(n) = name
          n += 1
      else
        out(n) = name
        n += 1
      i += 1
    val sb = new java.lang.StringBuilder()
    if isAbsolute() then sb.append('/')
    i = 0
    while i < n do
      if i > 0 then sb.append('/')
      sb.append(out(i))
      i += 1
    new UnixPath(sb.toString())

  def resolve(other: Path): Path =
    val that = UnixPath.of(other)
    if that.isAbsolute() || path.isEmpty() then that
    else if that.path.isEmpty() then this
    else if path == "/" then new UnixPath("/" + that.path)
    else new UnixPath(path + "/" + that.path)

  def relativize(other: Path): Path =
    val that = UnixPath.of(other)
    if that.isAbsolute() != isAbsolute() then throw new IllegalArgumentException("'other' is different type of Path")
    if this == that then new UnixPath("")
    else
      val mine = if path.isEmpty() then 0 else getNameCount()
      val theirs = if that.path.isEmpty() then 0 else that.getNameCount()
      var common = 0
      while common < mine && common < theirs && element(common) == that.element(common) do common += 1
      val sb = new java.lang.StringBuilder()
      var i = common
      while i < mine do
        if sb.length() > 0 then sb.append('/')
        sb.append("..")
        i += 1
      i = common
      while i < theirs do
        if sb.length() > 0 then sb.append('/')
        sb.append(that.element(i))
        i += 1
      new UnixPath(sb.toString())

  def toAbsolutePath(): Path =
    if isAbsolute() then this else new UnixPath(System.getProperty("user.dir")).resolve(this)

  def toRealPath(options: Array[LinkOption]): Path =
    val absolute = toAbsolutePath()
    if options.contains(LinkOption.NOFOLLOW_LINKS) then
      val r = OS.stat(absolute.toString, false)
      if OS.isError(r) then throw FileSystemErrors.translate(r, toString, null)
      absolute.normalize()
    else
      val r = OS.realPath(absolute.toString)
      if OS.isError(r) then throw FileSystemErrors.translate(r, toString, null)
      new UnixPath(r.asInstanceOf[String])

  def toFile(): File = new File(path)

  def iterator(): java.util.Iterator[Path] =
    val n = getNameCount()
    new java.util.Iterator[Path]:
      private var i = 0
      def hasNext(): Boolean = i < n
      def next(): Path =
        if i >= n then throw new java.util.NoSuchElementException()
        i += 1
        getName(i - 1)

  def compareTo(other: Path): Int = path.compareTo(UnixPath.of(other).path)
  override def equals(obj: Any): Boolean = obj match
    case that: UnixPath => path == that.path
    case _              => false
  override def hashCode(): Int = path.hashCode()
  override def toString(): String = path

private[java] object UnixPath:
  def of(p: Path): UnixPath = p match
    case p: UnixPath => p
    case null        => throw new NullPointerException()
    case _           => throw new ProviderMismatchException()

  /** Removes repeated and trailing slashes; rejects NUL characters, as the JDK. */
  private def normalize(input: String): String =
    if input == null then throw new NullPointerException()
    val n = input.length()
    val sb = new java.lang.StringBuilder(n)
    var prev = 0.toChar
    var i = 0
    while i < n do
      val c = input.charAt(i)
      if c == '\u0000' then throw new InvalidPathException(input, "Nul character not allowed")
      if !(c == '/' && prev == '/') then sb.append(c)
      prev = c
      i += 1
    if sb.length() > 1 && sb.charAt(sb.length() - 1) == '/' then sb.setLength(sb.length() - 1)
    if sb.length() == n then input else sb.toString()

class ProviderMismatchException(message: String) extends IllegalArgumentException(message):
  def this() = this(null)
