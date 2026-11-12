import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}

// Known bugs in javalib and the JDK natives, found by the jdk-* cases: each line prints what
// scala-go does today; the comment says what the JVM (JDK 25) prints. Fixing a bug makes this case
// fail: move the check to the jdk-* case it was taken from, and update the roadmap.
object Main:
  def attempt(what: String)(body: => Any): Unit =
    val result =
      try String.valueOf(body)
      catch case e: Throwable => s"throws ${e.getClass.getName}${Option(e.getMessage).fold("")(": " + _)}"
    println(s"$what: $result")

  def codes(s: CharSequence): String = s.toString.map(c => Integer.toHexString(c)).mkString(",")

  def main(args: Array[String]): Unit =
    strings()
    builders()

  def strings(): Unit =
    // Index errors have JDK 8-style messages. JVM: "Index 5 out of bounds for length 3",
    // "Range [4, 3) out of bounds for length 3", "Range [3, 3 + 2) out of bounds for length 2"
    attempt("\"abc\".charAt(5)")("abc".charAt(5))
    attempt("\"abc\".substring(4)")("abc".substring(4))
    attempt("new String(chars, 3, 2)")(new String(Array('a', 'b'), 3, 2))
    // JVM: true (intern returns the literal's instance)
    attempt("intern eq literal")(new String("abc".toCharArray).intern() eq "abc")
    // JVM: false (isBlank uses Character.isWhitespace, and U+00A0 is not whitespace)
    attempt("\"\\u00a0\".isBlank")("\u00a0".isBlank)
    // JVM: 3 (a fromIndex past the end finds the empty string at the end)
    attempt("\"abc\".indexOf(\"\", 20)")("abc".indexOf("", 20))
    // JVM: XX (Java's regex also replaces the empty match at the end)
    attempt("\"aaa\".replaceAll(\"a*\", \"X\")")("aaa".replaceAll("a*", "X"))
    // JVM: throws java.lang.IndexOutOfBoundsException: No group 2
    attempt("replaceAll with $2")("abc".replaceAll("b", "$2"))
    // JVM: throws java.util.regex.PatternSyntaxException (an invalid pattern, not an unsupported one)
    attempt("replaceAll(\"(\")")("abc".replaceAll("(", "x").length)
    // JVM: fffd (one replacement for an encoded surrogate, ED A0 80)
    val surrogate = Array[Byte](0xed.toByte, 0xa0.toByte, 0x80.toByte)
    attempt("new String(ED A0 80)")(codes(new String(surrogate, StandardCharsets.UTF_8)))
    attempt("decoder(ED A0 80)")(codes(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).decode(ByteBuffer.wrap(surrogate))))

  def builders(): Unit =
    val sb = new java.lang.StringBuilder("abc")
    // JVM: throws java.lang.StringIndexOutOfBoundsException: Range [4, 3) out of bounds for length 3
    // (StringBuilder's other index errors have JDK 8-style messages too)
    attempt("insert(4)")(sb.insert(4, "x"))
    // JVM: throws java.lang.IndexOutOfBoundsException: Range [2, 1) out of bounds for length 3
    attempt("append(cs, 2, 1)")(sb.append("xyz": CharSequence, 2, 1))
    // JVM: throws java.lang.IllegalArgumentException: Not a valid Unicode code point: 0xFFFFFFFF
    attempt("appendCodePoint(-1)")(codes(new java.lang.StringBuilder().appendCodePoint(-1)))
    // JVM: throws java.lang.IndexOutOfBoundsException: Range [1, 6) out of bounds for length 2
    attempt("append(chars, 1, 5)")(new java.lang.StringBuilder().append(Array('a', 'b'), 1, 5))
