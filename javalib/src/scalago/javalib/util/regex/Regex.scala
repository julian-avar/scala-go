package scalago.javalib.util.regex

import scala.annotation.static

import scalago.runtime.Regexes

trait MatchResult:
  def start(): Int
  def start(group: Int): Int
  def end(): Int
  def end(group: Int): Int
  def group(): String
  def group(group: Int): String
  def groupCount(): Int

class PatternSyntaxException(desc: String, regex: String, index: Int)
    extends java.lang.IllegalArgumentException(desc + " near index " + index + "\n" + regex):
  def getDescription(): String = desc
  def getPattern(): String = regex
  def getIndex(): Int = index

/** java.util.regex.Pattern on Go's regexp (RE2): no lookaround, backreferences or possessive
 *  quantifiers.
 */
final class Pattern private (regex: String, patternFlags: Int) extends Object with java.io.Serializable:
  private[regex] val handle: Object = Regexes.compile(regex, patternFlags)

  def pattern(): String = regex
  def flags(): Int = patternFlags
  def matcher(input: CharSequence): Matcher = new Matcher(this, String.valueOf(input))
  def split(input: CharSequence): Array[String] = split(input, 0)
  def split(input: CharSequence, limit: Int): Array[String] = Regexes.split(handle, String.valueOf(input), limit)
  override def toString(): String = regex

object Pattern:
  @static final val UNIX_LINES = 1
  @static final val CASE_INSENSITIVE = 2
  @static final val COMMENTS = 4
  @static final val MULTILINE = 8
  @static final val LITERAL = 16
  @static final val DOTALL = 32
  @static final val UNICODE_CASE = 64
  @static final val CANON_EQ = 128
  @static final val UNICODE_CHARACTER_CLASS = 256

  @static def compile(regex: String): Pattern = new Pattern(regex, 0)
  @static def compile(regex: String, flags: Int): Pattern = new Pattern(regex, flags)
  @static def matches(regex: String, input: CharSequence): Boolean = compile(regex).matcher(input).matches()
  @static def quote(s: String): String =
    if s.indexOf("\\E") < 0 then "\\Q" + s + "\\E"
    else
      val sb = new java.lang.StringBuilder("\\Q")
      var current = 0
      var i = s.indexOf("\\E")
      while i >= 0 do
        sb.append(s.substring(current, i)).append("\\E\\\\E\\Q")
        current = i + 2
        i = s.indexOf("\\E", current)
      sb.append(s.substring(current)).append("\\E").toString()

/** java.util.regex.Matcher. Offsets are UTF-16 indices, as in Java. */
final class Matcher private[regex] (private var pat: Pattern, private var text: String)
    extends Object with MatchResult:
  private var input: Object = Regexes.newInput(pat.handle, text)
  /** The last match's group offsets (start, end pairs), or null. */
  private var groups: Array[Int] = null
  private var searchFrom = 0
  private var appendPos = 0

  def pattern(): Pattern = pat

  def reset(): Matcher =
    groups = null
    searchFrom = 0
    appendPos = 0
    this

  def reset(newInput: CharSequence): Matcher =
    text = String.valueOf(newInput)
    input = Regexes.newInput(pat.handle, text)
    reset()

  def usePattern(newPattern: Pattern): Matcher =
    pat = newPattern
    input = Regexes.newInput(pat.handle, text)
    groups = null
    this

  def matches(): Boolean = record(Regexes.matchStart(input, true))
  def lookingAt(): Boolean = record(Regexes.matchStart(input, false))

  def find(): Boolean =
    if searchFrom > text.length then
      groups = null
      false
    else record(Regexes.find(input, searchFrom))

  def find(start: Int): Boolean =
    if start < 0 || start > text.length then throw new java.lang.IndexOutOfBoundsException("Illegal start index")
    reset()
    searchFrom = start
    find()

  private def record(g: Array[Int]): Boolean =
    groups = g
    if g != null then searchFrom = if g(1) == g(0) then g(1) + 1 else g(1)
    g != null

  private def checkMatch(): Unit =
    if groups == null then throw new java.lang.IllegalStateException("No match found")

  private def checkGroup(group: Int): Unit =
    checkMatch()
    if group < 0 || group > groupCount() then throw new java.lang.IndexOutOfBoundsException("No group " + group)

  def groupCount(): Int = Regexes.groupCount(pat.handle)
  def start(): Int = start(0)
  def start(group: Int): Int = { checkGroup(group); groups(2 * group) }
  def start(name: String): Int = start(groupIndex(name))
  def end(): Int = end(0)
  def end(group: Int): Int = { checkGroup(group); groups(2 * group + 1) }
  def end(name: String): Int = end(groupIndex(name))
  def group(): String = group(0)
  def group(group: Int): String =
    checkGroup(group)
    val s = groups(2 * group)
    if s < 0 then null else text.substring(s, groups(2 * group + 1))
  def group(name: String): String = group(groupIndex(name))
  def hitEnd(): Boolean = groups == null || groups(1) == text.length

  private def groupIndex(name: String): Int =
    val i = Regexes.groupIndex(pat.handle, name)
    if i < 0 then throw new java.lang.IllegalArgumentException("No group with name <" + name + ">")
    i

  def toMatchResult(): MatchResult =
    val snapshot = new Matcher(pat, text)
    snapshot.groups = groups
    snapshot

  def replaceAll(replacement: String): String = Regexes.replace(pat.handle, text, replacement, true)
  def replaceFirst(replacement: String): String = Regexes.replace(pat.handle, text, replacement, false)

  def appendReplacement(sb: java.lang.StringBuilder, replacement: String): Matcher =
    checkMatch()
    sb.append(text, appendPos, groups(0)).append(Regexes.expand(input, replacement, groups))
    appendPos = groups(1)
    this

  def appendTail(sb: java.lang.StringBuilder): java.lang.StringBuilder = sb.append(text, appendPos, text.length)

  def appendReplacement(sb: java.lang.StringBuffer, replacement: String): Matcher =
    checkMatch()
    sb.append(text, appendPos, groups(0)).append(Regexes.expand(input, replacement, groups))
    appendPos = groups(1)
    this

  def appendTail(sb: java.lang.StringBuffer): java.lang.StringBuffer = sb.append(text, appendPos, text.length)

  override def toString(): String = "java.util.regex.Matcher[pattern=" + pat + "]"

object Matcher:
  @static def quoteReplacement(s: String): String = Regexes.quoteReplacement(s)
