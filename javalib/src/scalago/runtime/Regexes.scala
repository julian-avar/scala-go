package scalago.runtime

/** The native side of java.util.regex (see runtime/rt/regex.go). */
object Regexes:
  private def native: Nothing = throw new UnsupportedOperationException("scala-go native")

  def compile(regex: String, flags: Int): Object = native
  def groupCount(regex: Object): Int = native
  def groupIndex(regex: Object, name: String): Int = native
  def newInput(regex: Object, input: String): Object = native
  /** Group offsets of the first match at or after `from` (null if none). */
  def find(input: Object, from: Int): Array[Int] = native
  /** Group offsets of a match at the start (or of the whole input), or null. */
  def matchStart(input: Object, whole: Boolean): Array[Int] = native
  def split(regex: Object, input: String, limit: Int): Array[String] = native
  def replace(regex: Object, input: String, replacement: String, all: Boolean): String = native
  def expand(input: Object, replacement: String, groups: Array[Int]): String = native
  def quoteReplacement(s: String): String = native
