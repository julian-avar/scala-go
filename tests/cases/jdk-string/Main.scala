import java.nio.charset.StandardCharsets.{ISO_8859_1, US_ASCII, UTF_8}

// java.lang.String's methods (natives and the String companion): searching, comparing, case,
// trimming, splitting, replacing, joining, conversions, charsets and surrogate pairs, with the
// exceptions of out-of-range arguments.
object Main:
  def attempt(label: String)(body: => Any): Unit =
    val r =
      try String.valueOf(body)
      catch case e: Exception => s"${e.getClass.getName}: ${e.getMessage}"
    println(s"$label: $r")

  /** Only the exception's class: the descriptions of invalid regular expressions are Go's. */
  def attemptType(label: String)(body: => Any): Unit =
    val r =
      try String.valueOf(body)
      catch case e: Exception => e.getClass.getName
    println(s"$label: $r")

  def show(a: Array[String]): String = a.map(s => s"[$s]").mkString(s"${a.length}:", "", "")
  def hex(b: Array[Byte]): String = b.map(x => f"${x & 0xff}%02x").mkString(" ")
  def codes(s: String): String = s.map(_.toInt.toHexString).mkString(",")

  def main(args: Array[String]): Unit =
    basics()
    searching()
    comparing()
    casesAndTrimming()
    splitting()
    replacing()
    conversions()
    charsets()
    surrogates()

  def basics(): Unit =
    println("== basics")
    val s = "Hello, World"
    println(s"${s.length} ${s.isEmpty} ${"".isEmpty} ${s.charAt(4)} ${s.substring(7)} ${s.substring(0, 5)} [${s.substring(5, 5)}]")
    println(s"${s.subSequence(1, 4)} ${s.concat("!")} [${"".concat("")}] ${s.toCharArray.length}")
    attempt("charAt(-1)")(s.charAt(-1))
    attempt("charAt(12)")(s.charAt(12))
    attempt("substring(13)")(s.substring(13))
    attempt("substring(5, 2)")(s.substring(5, 2))
    attempt("substring(-1, 2)")(s.substring(-1, 2))
    attempt("subSequence(0, 13)")(s.subSequence(0, 13))
    println(s"repeat ${"ab".repeat(3)} [${"ab".repeat(0)}] [${"".repeat(5)}]")
    attempt("repeat(-1)")("ab".repeat(-1))
    println(s"hashCode ${"".hashCode} ${"a".hashCode} ${s.hashCode} ${"polygenelubricants".hashCode} ${"\u0000￿".hashCode}")
    // intern returns the literal for an equal string, and the first interned one otherwise.
    val interned = new String("abc".toCharArray).intern()
    val computed = new String(Array('q', 'z', 'x')) // no literal is equal
    println(s"intern ${interned == "abc"} ${interned eq "abc"} ${computed.intern() eq computed} ${new String(computed).intern() eq computed} ${new String("abc") eq "abc"} ${new StringBuilder("lit").append("eral").toString.intern() eq "literal"}")
    println(s"equals ${s.equals("Hello, World")} ${s.equals(null)} ${s.equals(42)}")
    println(s"new String ${new String()}|${new String(Array('x', 'y', 'z'))}|${new String(Array('a', 'b', 'c', 'd'), 1, 2)}|${new String("copy")}")
    attempt("new String(chars, 3, 2)")(new String(Array('a', 'b'), 3, 2))
    attempt("new String(chars, 0, -1)")(new String(Array('a', 'b'), 0, -1))
    attempt("valueOf(chars, 1, 2)")(String.valueOf(Array('a', 'b'), 1, 2))
    val dst = Array.fill(6)('.')
    "abcdef".getChars(1, 4, dst, 2)
    println(s"getChars ${dst.mkString}")
    attempt("getChars(1, 7)")("abcdef".getChars(1, 7, dst, 0))
    attempt("getChars(0, 3, dst, 4)")("abcdef".getChars(0, 3, dst, 4))
    println(s"isBlank ${"".isBlank} ${" \t\n ".isBlank} ${" x ".isBlank}")
    // Character.isWhitespace: the no-break spaces and U+0085 are not white space, U+001C to U+001F are.
    println(s"isBlank ${" ".isBlank} ${"  \u0085".isBlank} ${"\u001c\u001f　".isBlank}")
    println(s"strip [${" x ".strip}] [${"\u001cx ".strip}]")

  def searching(): Unit =
    println("== searching")
    val s = "abracadabra"
    println(s"indexOf ${s.indexOf('a')} ${s.indexOf('a', 1)} ${s.indexOf('z')} ${s.indexOf("bra")} ${s.indexOf("bra", 2)} ${s.indexOf("")} ${s.indexOf('a', -5)} ${s.indexOf('a', 99)}")
    println(s"lastIndexOf ${s.lastIndexOf('a')} ${s.lastIndexOf('a', 9)} ${s.lastIndexOf("bra")} ${s.lastIndexOf("bra", 7)} ${s.lastIndexOf("")} ${s.lastIndexOf('a', -1)} ${s.lastIndexOf("abra", 100)}")
    println(s"from past the end ${s.indexOf("", 20)} ${s.indexOf("a", 20)} ${s.lastIndexOf("", 20)} ${s.lastIndexOf("", -1)} ${s.indexOf("", -5)}")
    // Code points: supplementary ones are surrogate pairs; values that are not code points are never found.
    val pairs = "x😀y😀"
    println(s"code points ${pairs.indexOf(0x1F600)} ${pairs.indexOf(0x1F600, 2)} ${pairs.lastIndexOf(0x1F600)} ${pairs.lastIndexOf(0x1F600, 3)} ${pairs.lastIndexOf(0x1F600, 2)} ${pairs.lastIndexOf(0x1F600, 0)} ${pairs.indexOf(0xD83D)} ${pairs.lastIndexOf(0xDE00)}")
    println(s"not code points ${"�".indexOf(0x110000)} ${"�".lastIndexOf(0x110000)} ${"￿".indexOf(-1)} ${"￿".lastIndexOf(-1)}")
    println(s"contains ${s.contains("cad")} ${s.contains("")} ${s.contains("dab ")}")
    println(s"startsWith ${s.startsWith("abr")} ${s.startsWith("bra", 1)} ${s.startsWith("", 11)} ${s.startsWith("a", -1)} ${s.startsWith("a", 12)} endsWith ${s.endsWith("bra")} ${s.endsWith("")}")
    println(s"regionMatches ${s.regionMatches(0, "xabrx", 1, 3)} ${s.regionMatches(true, 7, "ABRA", 0, 4)} ${s.regionMatches(false, 7, "ABRA", 0, 4)} ${s.regionMatches(-1, "a", 0, 1)} ${s.regionMatches(0, "a", 0, 5)} ${s.regionMatches(0, "q", 0, 0)}")
    println(s"matches ${s.matches("a.*a")} ${s.matches("bra")} ${"2024-01-02".matches("\\d{4}-\\d{2}-\\d{2}")}")

  def comparing(): Unit =
    println("== comparing")
    println(s"compareTo ${"a".compareTo("b")} ${"b".compareTo("a")} ${"abc".compareTo("abc")} ${"ab".compareTo("abcd")} ${"abcd".compareTo("ab")} ${"A".compareTo("a")} ${"".compareTo("x")}")
    println(s"compareTo chars ${"￿".compareTo("\u0000")} ${"é".compareTo("e")}")
    println(s"compareToIgnoreCase ${"Apple".compareToIgnoreCase("apple")} ${"apple".compareToIgnoreCase("BANANA")} ${"Zeta".compareToIgnoreCase("alpha")} ${"ab".compareToIgnoreCase("ABC")} ${"[".compareToIgnoreCase("a")}")
    println(s"equalsIgnoreCase ${"HeLLo".equalsIgnoreCase("hello")} ${"ß".equalsIgnoreCase("SS")} ${"Ǆ".equalsIgnoreCase("ǆ")} ${"a".equalsIgnoreCase(null)} ${"Straße".equalsIgnoreCase("STRASSE")}")
    val sorted = Array("banana", "Apple", "cherry", "apple", "Banana").sortWith(_.compareTo(_) < 0)
    println(s"sorted ${sorted.mkString(",")}")
    val ci = java.util.Arrays.asList("b", "C", "a", "B")
    java.util.Collections.sort(ci, String.CASE_INSENSITIVE_ORDER)
    println(s"CASE_INSENSITIVE_ORDER $ci")

  def casesAndTrimming(): Unit =
    println("== case and trimming")
    println(s"${"Hello World 123".toUpperCase} ${"Hello World 123".toLowerCase} ${"ÀÉÎõü".toLowerCase} ${"àéîõü".toUpperCase}")
    println(s"${"straße".toUpperCase} ${"ΑΒΓ".toLowerCase} ${"ǆ".toUpperCase} ${"İ".toLowerCase.length}")
    println(s"[${"  \t padded \n ".trim}] [${"\u0000\u001fx ".trim}] [${"   ".trim}] [${" em ".trim.length}]")
    println(s"[${"  \t padded \n ".strip}] [${" em ".strip}] [${"\u0000x".strip.length}]")

  def splitting(): Unit =
    println("== split")
    println(show("a,b,,c,,".split(",")))
    println(show("a,b,,c,,".split(",", -1)))
    println(show("a,b,,c,,".split(",", 2)))
    println(show("a,b,,c,,".split(",", 0)))
    println(show("".split(",")))
    println(show(",".split(",")))
    println(show(",a".split(",")))
    println(show("abc".split("")))
    println(show("a1b22c333".split("\\d+")))
    println(show("a.b.c".split(".")))
    println(show("a.b.c".split("\\.")))
    println(show("a|b|c".split("\\|", 2)))
    println(show("  leading and  double  ".split(" ")))
    println(show("x--y--".split("-", -1)))
    println(show("one two\tthree\nfour".split("\\s")))
    println(show("😀a😀".split("a")))
    // Empty matches right after a match count, as in Java (Go's regexp skips them).
    println(show("aaa".split("a*")))
    println(show("aaa".split("a*", -1)))
    println(show("abc".split("", -1)))
    println(show("baaac".split("a*", -1)))
    println(show("a1b".split("\\d*", -1)))
    attemptType("split(\"(\")")("abc".split("("))

  def replacing(): Unit =
    println("== replace")
    println(s"${"banana".replace('a', 'o')} ${"banana".replace("an", "AN")} ${"aaa".replace("aa", "b")} ${"abc".replace("", "-")} ${"abc".replace('x', 'y')}")
    println(s"${"a1b2c3".replaceAll("\\d", "#")} ${"a1b2c3".replaceFirst("\\d", "#")} ${"John Smith".replaceAll("(\\w+) (\\w+)", "$2, $1")} ${"x".replaceAll("x", "\\$")}")
    println(s"${"".replaceAll("", "E")} ${"a.b".replace(".", "$1")}")
    // Empty matches right after a match are replaced too, as in Java.
    println(s"${"aaa".replaceAll("a*", "X")} ${"baaac".replaceAll("a*", "-")} ${"aaa".replaceAll("(a*)", "<$1>")} ${"abc".replaceAll("x*", "-")} ${"a b".replaceAll("\\b", "|")}")
    // Group references: digits while the number is a group, named groups, escapes.
    println(s"${"abc".replaceAll("(b)", "$12")} ${"abc".replaceAll("(?<x>b)", "[${x}]")} ${"abc".replaceAll("(b)|(z)", "[$2]")} ${"abc".replaceAll("b", "\\$\\\\")} ${"abc".replaceFirst("(.)", "$1$1")}")
    println(s"no match ${"abc".replaceAll("x", "$9")} ${"abc".replaceFirst("x", "\\")}")
    for (pattern, replacement) <- Seq("b" -> "$2", "(b)" -> "$2", "b" -> "$", "b" -> "\\", "b" -> "$a", "(b)" -> "${x}", "b" -> "${}", "b" -> "${x", "b" -> "${1x}")
    do attempt(s"replaceAll(\"$pattern\", \"$replacement\")")("abc".replaceAll(pattern, replacement))
    attempt("replaceFirst(\"b\", \"$2\")")("abc".replaceFirst("b", "$2"))
    // Invalid patterns.
    for pattern <- Seq("(", ")", "[", "*a", "[z-a]", "\\", "a)|(b") do attemptType(s"replaceAll(\"$pattern\")")("abc".replaceAll(pattern, "x"))
    attemptType("matches(\"a)|(b\")")("abc".matches("a)|(b"))

  def conversions(): Unit =
    println("== conversions")
    println(s"${String.valueOf(true)} ${String.valueOf('c')} ${String.valueOf(42)} ${String.valueOf(-7L)} ${String.valueOf(1.5f)} ${String.valueOf(2.0)} ${String.valueOf(null: Object)}")
    println(s"${String.valueOf(Array('a', 'b', 'c'))} ${String.valueOf(Array('a', 'b', 'c', 'd'), 1, 2)} ${String.copyValueOf(Array('x', 'y'))}")
    println(s"${String.valueOf(1e21)} ${String.valueOf(1e-5)} ${String.valueOf(100.0f)} ${String.valueOf(Long.MinValue)} ${String.valueOf(0.1 + 0.2)}")
    println(s"${String.join("-", "a", "b", "c")} [${String.join(",")}] ${String.join("/", java.util.List.of("x", "y"))} ${String.join(", ", java.util.Set.of("only"))}")
    println(s"chars ${"héllo".chars().toArray.mkString(",")} sum ${"abc".chars().sum()} count ${"".chars().count()}")
    println(s"codePoints ${"a😀b".codePoints().toArray.mkString(",")} ${"a😀b".chars().count()} ${"a😀b".codePoints().count()}")
    // String.format (jdk-formatter has the rest).
    println(String.format("%d|%5d|%-5d|%05d|%+d|%x|%X|%o|%s|%10s|%-6s|%S|%c|%b|%b|%%|%.3f|%10.2f|%-8.1f|%e|%.2e|%s", Integer.valueOf(42),
      Integer.valueOf(-7), Integer.valueOf(3), Integer.valueOf(-42), Integer.valueOf(5), Integer.valueOf(255), Integer.valueOf(48879),
      Integer.valueOf(8), "str", "right", "left", "up", Character.valueOf('z'), java.lang.Boolean.TRUE, null, java.lang.Double.valueOf(Math.PI),
      java.lang.Double.valueOf(-2.5), java.lang.Double.valueOf(1.25), java.lang.Double.valueOf(12345.678), java.lang.Double.valueOf(0.000123), null))
    println(String.format("%d %d %s %s %.1f", java.lang.Long.valueOf(Long.MinValue), java.lang.Short.valueOf(7.toShort), List(1, 2), Array(1).length, java.lang.Float.valueOf(2.25f)))
    println(String.format("line%nbreak [%3s] [%-3s] [%.2s]", "a", "b", "xyz"))
    println(s"${"%s-%s".format("a", "b")} ${"%08.3f".format(-3.14159)} ${f"${3}%03d ${2.5}%.0f ${0.125}%.2f ${1e-5}%.3e"}")
    println(s"lines ${"a\nb\r\nc\rd".lines().toArray.mkString("|")} ${"trailing\n".lines().count()} ${"\n".lines().count()}")

  def charsets(): Unit =
    println("== charsets")
    val text = "Aé€😀"
    println(s"UTF-8 ${hex(text.getBytes(UTF_8))} | ${hex(text.getBytes("UTF-8"))} | ${hex(text.getBytes())}")
    println(s"ISO-8859-1 ${hex(text.getBytes(ISO_8859_1))} | ${hex(text.getBytes("latin1"))}")
    println(s"US-ASCII ${hex(text.getBytes(US_ASCII))} | ${hex(text.getBytes("ASCII"))}")
    val latin = Array[Byte](0x41, 0xe9.toByte, 0xff.toByte, 0x80.toByte)
    println(s"decode ${new String(latin, ISO_8859_1)} ${new String(latin, "ISO-8859-1").length} ${codes(new String(latin, US_ASCII))}")
    println(s"decode UTF-8 ${new String(Array[Byte](0x68, 0xc3.toByte, 0xa9.toByte), UTF_8)} ${codes(new String(Array[Byte](0x41, 0xc3.toByte, 0x28, 0xf0.toByte, 0x9f.toByte), UTF_8))}")
    println(s"range ${new String("xhéllo".getBytes(UTF_8), 1, 3, UTF_8)} ${new String("abcdef".getBytes, 2, 3)} ${new String("abcdef".getBytes, 1, 2, "US-ASCII")}")
    println(s"round trip ${new String(text.getBytes(UTF_8), UTF_8) == text}")
    println(s"lone surrogate ${hex("a\ud800b".getBytes(UTF_8))} ${hex("a\udc00".getBytes(ISO_8859_1))}")
    // An encoded surrogate (ED A0..BF xx) is one malformed sequence, whole or truncated.
    def decode(bytes: Int*) = codes(new String(bytes.map(_.toByte).toArray, UTF_8))
    println(s"encoded surrogates ${decode(0xed, 0xa0, 0x80)} ${decode(0xed, 0xbf, 0xbf, 0x41)} ${decode(0xed, 0xa0, 0x41)} ${decode(0xed, 0xa0)} ${decode(0xed, 0x9f, 0xbf)}")
    // Only the three charsets are supported here; unknown names throw as in the JDK.
    attempt("new String(bytes, \"no-such-charset\")")(new String(Array[Byte](65), "no-such-charset"))
    attempt("new String(bytes, 0, 1, \"bad name\")")(new String(Array[Byte](65), 0, 1, "bad name"))
    attempt("getBytes(\"no-such-charset\")")("A".getBytes("no-such-charset"))
    attempt("getBytes(null: String)")("A".getBytes(null: String))

  def surrogates(): Unit =
    println("== surrogates")
    val s = "a😀b𝄞"
    println(s"${s.length} ${s.codePointAt(1)} ${s.codePointAt(2)} ${s.codePointAt(4)} ${s.charAt(1).toInt.toHexString} ${s.indexOf(0x1F600)} ${s.indexOf(0x1D11E)} ${s.indexOf("b")}")
    println(s"${s.substring(1, 3)} ${s.toUpperCase} ${s.reverse.codePoints().count()} ${new java.lang.StringBuilder(s).reverse()}")
    println(s"${"𐐨".toUpperCase} ${"𐐀".toLowerCase} ${"𐐨".toUpperCase.codePointAt(0).toHexString}")
    attempt("codePointAt(9)")(s.codePointAt(9))
