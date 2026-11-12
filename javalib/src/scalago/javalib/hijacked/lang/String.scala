package scalago.javalib.hijacked.lang

import java.util.Comparator

/** The members of `java.lang.String` written in Scala. A String is a Go value (`*rt.String`)
 *  whose methods are natives; this object, which the plugin emits as `java.lang.String$` (in its
 *  own package, so that its name does not shadow `String` in javalib's `java.lang`), holds the
 *  others. The linker uses its members for String's static fields and methods, and (receiver
 *  first) for its instance methods, that have no native (see `Program.staticFieldViaCompanion`,
 *  `staticMethodViaCompanion` and `hijackedCompanionMethod`).
 */
object String:
  /** Compares as `compareToIgnoreCase` does. */
  val CASE_INSENSITIVE_ORDER: Comparator[java.lang.String] = new Comparator[java.lang.String] with java.io.Serializable:
    def compare(a: java.lang.String, b: java.lang.String): Int = a.compareToIgnoreCase(b)

  def join(delimiter: CharSequence, elements: java.lang.Iterable[? <: CharSequence]): java.lang.String =
    if delimiter == null || elements == null then throw new NullPointerException()
    val joiner = new java.util.StringJoiner(delimiter)
    elements.forEach(e => joiner.add(e))
    joiner.toString

  def format(format: java.lang.String, args: Array[Object]): java.lang.String =
    new java.util.Formatter().format(format, args).toString

  def format(l: java.util.Locale, format: java.lang.String, args: Array[Object]): java.lang.String =
    new java.util.Formatter(l).format(format, args).toString

  def formatted(s: java.lang.String, args: Array[Object]): java.lang.String =
    new java.util.Formatter().format(s, args).toString

  /** The lines of `s`, split at `\n`, `\r` and `\r\n`; a final terminator ends the last line. */
  def lines(s: java.lang.String): java.util.stream.Stream[java.lang.String] =
    val result = new java.util.ArrayList[java.lang.String]()
    var start = 0
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      if c == '\n' || c == '\r' then
        result.add(s.substring(start, i))
        if c == '\r' && i + 1 < s.length && s.charAt(i + 1) == '\n' then i += 1
        start = i + 1
      i += 1
    if start < s.length then result.add(s.substring(start))
    result.stream()
