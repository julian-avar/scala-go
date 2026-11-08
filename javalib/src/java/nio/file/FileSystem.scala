package java.nio.file

import java.util.regex.{Pattern, PatternSyntaxException}

/** java.nio.file.FileSystem. Only the default file system exists ([[FileSystems.getDefault]]). */
abstract class FileSystem protected () extends java.io.Closeable:
  def close(): Unit
  def isOpen(): Boolean
  def isReadOnly(): Boolean
  def getSeparator(): String
  def getRootDirectories(): java.lang.Iterable[Path]
  def supportedFileAttributeViews(): java.util.Set[String]
  def getPath(first: String, more: Array[String]): Path
  def getPathMatcher(syntaxAndPattern: String): PathMatcher
  def newWatchService(): WatchService

final class FileSystems private ()

object FileSystems:
  def getDefault(): FileSystem = UnixFileSystem

trait PathMatcher:
  def matches(path: Path): Boolean

/** Watch services are not implemented. */
trait WatchService extends java.io.Closeable

/** The default file system: Unix paths (see [[UnixPath]]). */
private[java] object UnixFileSystem extends FileSystem:
  def close(): Unit = throw new UnsupportedOperationException()
  def isOpen(): Boolean = true
  def isReadOnly(): Boolean = false
  def getSeparator(): String = "/"

  def getRootDirectories(): java.lang.Iterable[Path] =
    java.util.Collections.singletonList[Path](new UnixPath("/"))

  def supportedFileAttributeViews(): java.util.Set[String] =
    java.util.Collections.singleton("basic")

  def getPath(first: String, more: Array[String]): Path = Path.of(first, more)

  /** `glob:` and `regex:` patterns, matched against the whole path, as on the JDK. */
  def getPathMatcher(syntaxAndPattern: String): PathMatcher =
    val colon = syntaxAndPattern.indexOf(':')
    if colon <= 0 then throw new IllegalArgumentException()
    val syntax = syntaxAndPattern.substring(0, colon)
    val input = syntaxAndPattern.substring(colon + 1)
    val regex =
      if syntax.equalsIgnoreCase("glob") then Globs.toRegex(input)
      else if syntax.equalsIgnoreCase("regex") then input
      else throw new UnsupportedOperationException("Syntax '" + syntax + "' not recognized")
    val pattern = Pattern.compile(regex)
    path => pattern.matcher(path.toString()).matches()

  def newWatchService(): WatchService =
    throw new UnsupportedOperationException("Watch services are not supported by scala-go")

  override def toString(): String = "sun.nio.fs.LinuxFileSystem"

/** Globs as regular expressions, as the JDK's `sun.nio.fs.Globs`, but with character classes
 *  that Go's regular expressions support (no intersections).
 */
private object Globs:
  private val regexMetaChars = ".^$+{[]|()"
  private val globMetaChars = "\\*?[{"

  def toRegex(glob: String): String =
    def next(i: Int): Char = if i < glob.length() then glob.charAt(i) else 0
    var inGroup = false
    val regex = new java.lang.StringBuilder("^")
    var i = 0
    while i < glob.length() do
      var c = glob.charAt(i)
      i += 1
      c match
        case '\\' =>
          if i == glob.length() then throw new PatternSyntaxException("No character to escape", glob, i - 1)
          val escaped = glob.charAt(i)
          if globMetaChars.indexOf(escaped) >= 0 || regexMetaChars.indexOf(escaped) >= 0 then regex.append('\\')
          regex.append(escaped)
          i += 1
        case '[' =>
          // Classes never match the name separator.
          regex.append('[')
          if next(i) == '^' then
            regex.append("\\^")
            i += 1
          else
            if next(i) == '!' then
              regex.append("^/")
              i += 1
            if next(i) == '-' then
              regex.append('-')
              i += 1
          var hasRangeStart = false
          var last: Char = 0
          var closed = false
          while !closed && i < glob.length() do
            c = glob.charAt(i)
            i += 1
            if c == ']' then closed = true
            else
              if c == '/' then throw new PatternSyntaxException("Explicit 'name separator' in class", glob, i - 1)
              if c == '\\' || c == '[' then regex.append('\\')
              regex.append(c)
              if c == '-' then
                if !hasRangeStart then throw new PatternSyntaxException("Invalid range", glob, i - 1)
                c = next(i)
                i += 1
                if c == 0 || c == ']' then closed = c == ']'
                else
                  if c < last then throw new PatternSyntaxException("Invalid range", glob, i - 3)
                  regex.append(c)
                  hasRangeStart = false
              else
                hasRangeStart = true
                last = c
          if !closed then throw new PatternSyntaxException("Missing ']", glob, i - 1)
          regex.append(']')
        case '{' =>
          if inGroup then throw new PatternSyntaxException("Cannot nest groups", glob, i - 1)
          regex.append("(?:(?:")
          inGroup = true
        case '}' =>
          if inGroup then
            regex.append("))")
            inGroup = false
          else regex.append('}')
        case ',' =>
          if inGroup then regex.append(")|(?:") else regex.append(',')
        case '*' =>
          if next(i) == '*' then
            regex.append(".*")
            i += 1
          else regex.append("[^/]*")
        case '?' =>
          regex.append("[^/]")
        case _ =>
          if regexMetaChars.indexOf(c) >= 0 then regex.append('\\')
          regex.append(c)
    if inGroup then throw new PatternSyntaxException("Missing '}", glob, i - 1)
    regex.append('$').toString()
