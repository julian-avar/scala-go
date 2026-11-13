// Strings (docs/_docs/spec/data.md, "Strings"): String is UTF-16 code units. length, charAt,
// indices, substring, compareTo and hashCode work on code units, with surrogate pairs and unpaired
// surrogates as on the JVM; conversions to UTF-8 replace unpaired surrogates and invalid input.
// Unpaired surrogates are never printed (the console shows them as `?`); their code units are.
object Main:
  def p(what: String)(body: => Any): Unit = println(s"$what: ${String.valueOf(body)}")
  def units(s: String): String = s.map(c => f"${c.toInt}%04x").mkString(" ")
  def id(s: String): String = s

  val smile = "\uD83D\uDE00"          // U+1F600, one code point, two code units
  val mixed = "a" + smile + "b\u00e9c" // a, surrogate pair, b, e-acute, c
  val loneHigh = "x\uD800y"
  val loneLow = "\uDC00z"

  def main(args: Array[String]): Unit =
    // Code units
    p("a literal that looks like a Go package member")("math.abs")
    p("lengths")(s"${"".length} ${"abc".length} ${smile.length} ${mixed.length} ${loneHigh.length}")
    p("code units of mixed")(units(mixed))
    p("charAt")(s"${mixed.charAt(0)} ${mixed.charAt(1).toInt.toHexString} ${mixed.charAt(2).toInt.toHexString} ${mixed.charAt(4)}")
    p("isSurrogate")(mixed.map(c => if c.isHighSurrogate then 'H' else if c.isLowSurrogate then 'L' else '.'))
    p("codePointAt")(s"${mixed.codePointAt(1).toHexString} ${mixed.codePointAt(2).toHexString} ${loneHigh.codePointAt(1).toHexString}")
    p("codePoints")(mixed.codePoints.toArray.map(_.toHexString).mkString(","))
    p("codePoints of lone surrogates")((loneHigh + loneLow).codePoints.toArray.map(_.toHexString).mkString(","))
    p("chars")(id("h\u00e9").chars.toArray.mkString(","))
    p("Character.charCount")(s"${Character.charCount(0x1F600)} ${Character.charCount('a')}")
    p("toCodePoint")(Character.toCodePoint('\uD83D', '\uDE00').toHexString)

    // Indices and substrings on code units
    p("indexOf")(s"${mixed.indexOf('b')} ${mixed.indexOf(smile)} ${mixed.indexOf(0x1F600)} ${mixed.indexOf('\uDE00')} ${mixed.indexOf("c", 5)} ${mixed.indexOf("zz")}")
    p("lastIndexOf")(s"${"abcabc".lastIndexOf('b')} ${"abcabc".lastIndexOf("bc", 3)} ${mixed.lastIndexOf('\uD83D')}")
    p("substring splitting a pair")(units(mixed.substring(0, 2)) + " | " + units(mixed.substring(2)))
    p("substring whole pair")(mixed.substring(1, 3) == smile)
    p("substring edges")(s"[${"abc".substring(3)}] [${"abc".substring(0, 0)}] [${"abc".substring(1, 2)}]")
    p("subSequence")(id("hello").subSequence(1, 4))
    p("contains, startsWith, endsWith")(s"${mixed.contains(smile)} ${mixed.startsWith("a\uD83D")} ${mixed.endsWith("\u00e9c")} ${"abc".startsWith("c", 2)}")
    p("regionMatches")(s"${"Hello".regionMatches(true, 1, "XELL", 1, 3)} ${"Hello".regionMatches(1, "ell", 0, 3)}")

    // Comparison by code units: U+FFFF sorts after a surrogate pair's high unit
    p("compareTo")(s"${"a".compareTo("b")} ${"b".compareTo("a")} ${"abc".compareTo("ab")} ${"".compareTo("a")} ${"A".compareTo("a")}")
    p("compareTo across planes")(s"${"\uffff".compareTo(smile)} ${smile.compareTo("\uffff")} ${"\ue000".compareTo(smile)}")
    p("sorted by code units")(List("\uffff", smile, "z", "\u00e9", "Z", "").sorted.map(units))
    p("compareToIgnoreCase")(s"${"ABC".compareToIgnoreCase("abd")} ${"a".compareToIgnoreCase("A")}")
    p("equalsIgnoreCase")(s"${"Stra\u00dfe".equalsIgnoreCase("STRA\u00dfE")} ${"\u00e9".equalsIgnoreCase("\u00c9")} ${"a".equalsIgnoreCase(null)}")

    // hashCode over code units
    p("hashCode")(s"${"".hashCode} ${smile.hashCode} ${mixed.hashCode} ${loneHigh.hashCode} ${("\u0000" * 3).hashCode}")
    p("hashCode of a built string")((new StringBuilder).append("a").append(smile).append("b\u00e9c").toString.hashCode == mixed.hashCode)

    // Case mapping, beyond ASCII
    p("toUpperCase")(s"${"hello \u00e9\u00e8 \u03b1\u03b2".toUpperCase} ${units("stra\u00dfe".toUpperCase)}")
    p("toLowerCase")(s"${"HELLO \u00c9 \u0391\u0392".toLowerCase}")
    p("Character case")(s"${'a'.toUpper} ${'\u00e9'.toUpper} ${'\u00df'.toUpper} ${Character.toUpperCase(0x1F600) == 0x1F600} ${Character.toLowerCase(0x10400).toHexString}")
    p("Unicode 16 case mapping")(s"${Character.toLowerCase(0xA7CB).toHexString} ${"\uA7CB".toLowerCase == "\u0264"}")
    p("character classes")(s"${'\u00e9'.isLetter} ${'\u0663'.isDigit} ${'\u2003'.isWhitespace} ${Character.isLetter(0x1F600)} ${Character.isLetter(0x10400)} ${'_'.isLetterOrDigit}")

    // UTF-8 conversion
    p("getBytes UTF-8 lengths")(List("a", "\u00e9", "\u20ac", smile, loneHigh, loneLow).map(_.getBytes("UTF-8").length))
    p("lone surrogate becomes ?")(loneHigh.getBytes("UTF-8").map(b => b & 0xff).mkString(","))
    p("bytes of mixed")(mixed.getBytes(java.nio.charset.StandardCharsets.UTF_8).map(b => b & 0xff).mkString(","))
    p("decode valid UTF-8")(units(new String(Array(0xf0, 0x9f, 0x98, 0x80, 0x41).map(_.toByte), "UTF-8")))
    p("decode invalid UTF-8")(units(new String(Array(0x41, 0xff, 0xc3, 0x42, 0xe2, 0x82).map(_.toByte), "UTF-8")))
    p("ISO-8859-1 round trip")(units(new String("\u00e9\u00ff".getBytes("ISO-8859-1"), "ISO-8859-1")))
    p("US-ASCII of non-ASCII")("\u00e9a".getBytes("US-ASCII").mkString(","))
    p("Character.toString, valueOf")(s"${Character.toString('z')} ${String.valueOf('\u00e9')}")

    // Construction and transformation keep code units
    p("new String(chars)")(new String(Array('h', 'i', '\uD83D', '\uDE00')) == "hi" + smile)
    p("new String(chars, offset, count)")(new String(Array('a', 'b', 'c', 'd'), 1, 2))
    p("toCharArray")(mixed.toCharArray.length)
    p("String.valueOf(char array)")(String.valueOf(Array('x', 'y')))
    p("StringBuilder reverse keeps pairs")(units(new java.lang.StringBuilder(mixed).reverse.toString))
    p("Scala reverse")(units(mixed.reverse))
    p("StringBuilder appendCodePoint")(units(new java.lang.StringBuilder().appendCodePoint(0x1F601).append('!').toString))
    p("StringBuilder setCharAt, insert, delete")({ val sb = new java.lang.StringBuilder("abcdef"); sb.setCharAt(0, 'A'); sb.insert(2, smile); sb.deleteCharAt(sb.length - 1); sb.delete(0, 1); units(sb.toString) })
    p("replace chars")("a.b.c".replace('.', '/'))
    p("replace strings")(mixed.replace(smile, "<smile>"))
    p("repeat, concat, join")(s"${"ab" * 3} ${"x".concat("y")} ${String.join("-", "a", "b", "c")} ${"ab".repeat(0).isEmpty}")
    p("trim vs strip")(s"[${" \t a \u2003".trim}] [${" \t a \u2003".strip}] [${"\u0000x\u0000".trim}]")
    p("isEmpty, isBlank")(s"${"".isEmpty} ${" ".isEmpty} ${" \t".isBlank} ${"\u2003".isBlank}")
    p("split")(s"${"a,b,,c,,".split(",").toList} ${"a1b22c".split("\\d+").toList} ${"".split(",").length}")
    p("lines")("a\nb\r\nc\rd".linesIterator.toList)
    p("chars of empty")("".toCharArray.length)

    // Literals, identity, immutability
    val lit1 = "literal"
    val lit2 = "literal"
    p("equal literals are one object")(lit1 eq lit2)
    p("strings are immutable")({ val s = "abc"; s.toUpperCase; s.replace('a', 'z'); s })
    p("string switch over non-ASCII")(List("\u00e9t\u00e9", smile, "x").map {
      case "\u00e9t\u00e9" => "summer"
      case "\uD83D\uDE00"  => "smile"
      case _               => "other"
    })
    p("unicode escapes")(units("\u0000\u0001\u007f\u0080\uffff"))
