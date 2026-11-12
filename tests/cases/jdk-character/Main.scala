// java.lang.Character's statics: classification of ASCII, Latin-1 and other Unicode characters
// (char and code point overloads), case mapping, digits in radixes, identifiers, surrogates and
// the boxed Character's members.
object Main:
  def attempt(label: String)(body: => Any): Unit =
    val r =
      try String.valueOf(body)
      catch case e: Exception => s"${e.getClass.getName}: ${e.getMessage}"
    println(s"$label: $r")

  def flags(cs: Seq[Int])(p: Int => Boolean): String = cs.map(c => if p(c) then '1' else '0').mkString
  def hex(c: Int): String = Integer.toHexString(c)

  // ASCII controls, punctuation, digits, letters; Latin-1; other scripts; supplementary code points.
  val samples: Seq[Int] = Seq(0x00, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x1c, 0x1f, 0x20, '!', '0', '9', 'A', 'Z', '_', '$', 'a', 'z',
    0x7f, 0x85, 0x9f, 0xa0, 0xad, 0xb5, 0xc0, 0xdf, 0xe9, 0xf7, 0xff, 0x3a3, 0x3c3, 0x416, 0x5d0, 0x660, 0x669, 0x1680,
    0x2007, 0x2028, 0x2029, 0x202f, 0x2003, 0x200b, 0x3000, 0x4e2d, 0xfeff, 0xff10, 0xff21, 0x1d7ce, 0x10400, 0x10428,
    0x1f600, 0x20bb7)

  def main(args: Array[String]): Unit =
    classification()
    caseMapping()
    digits()
    identifiers()
    surrogates()
    boxes()

  def classification(): Unit =
    println("== classification (code points)")
    println(s"samples      ${samples.map(hex).mkString(" ")}")
    println(s"isDigit      ${flags(samples)(Character.isDigit)}")
    println(s"isLetter     ${flags(samples)(Character.isLetter)}")
    println(s"isLetterOrD  ${flags(samples)(Character.isLetterOrDigit)}")
    println(s"isWhitespace ${flags(samples)(Character.isWhitespace)}")
    println(s"isSpaceChar  ${flags(samples)(Character.isSpaceChar)}")
    println(s"isUpperCase  ${flags(samples)(Character.isUpperCase)}")
    println(s"isLowerCase  ${flags(samples)(Character.isLowerCase)}")
    println(s"isISOControl ${flags(samples)(Character.isISOControl)}")
    println("== classification (chars)")
    val bmp = samples.filter(_ < 0x10000)
    println(s"isDigit      ${flags(bmp)(c => Character.isDigit(c.toChar))}")
    println(s"isLetter     ${flags(bmp)(c => Character.isLetter(c.toChar))}")
    println(s"isLetterOrD  ${flags(bmp)(c => Character.isLetterOrDigit(c.toChar))}")
    println(s"isWhitespace ${flags(bmp)(c => Character.isWhitespace(c.toChar))}")
    println(s"isSpaceChar  ${flags(bmp)(c => Character.isSpaceChar(c.toChar))}")
    println(s"isUpperCase  ${flags(bmp)(c => Character.isUpperCase(c.toChar))}")
    println(s"isLowerCase  ${flags(bmp)(c => Character.isLowerCase(c.toChar))}")
    println(s"isISOControl ${flags(bmp)(c => Character.isISOControl(c.toChar))}")
    println(s"negative code point ${Character.isISOControl(Integer.parseInt("-1"))} ${Character.isLetter(-1)} ${Character.isDigit(0x110000)}")
    // Scala's RichChar forwards to Character.
    println(s"RichChar ${'x'.isLetter} ${'7'.isDigit} ${' '.isWhitespace} ${'Q'.isUpper} ${'q'.isLower} ${'q'.toUpper} ${'Q'.toLower} ${'7'.asDigit}")

  def caseMapping(): Unit =
    println("== case mapping")
    val cs = Seq('a', 'z', 'A', 'Z', '1', 'é', 'É', 'ß', 'ÿ', 'µ', 'σ', 'ς', 'Σ', 'ж', 'Ж', 'ǅ', 'ǆ', 'ı', 'İ', 'ſ', 'ａ', 'Ａ')
    println(s"toUpperCase(char) ${cs.map(c => Character.toUpperCase(c)).mkString}")
    println(s"toLowerCase(char) ${cs.map(c => Character.toLowerCase(c)).mkString}")
    println(s"toUpperCase(int) ${cs.map(c => hex(Character.toUpperCase(c.toInt))).mkString(" ")}")
    println(s"toLowerCase(int) ${cs.map(c => hex(Character.toLowerCase(c.toInt))).mkString(" ")}")
    println(s"supplementary ${hex(Character.toUpperCase(0x10428))} ${hex(Character.toLowerCase(0x10400))} ${hex(Character.toUpperCase(0x1f600))}")

  def digits(): Unit =
    println("== digits")
    val ds = Seq('0', '5', '9', 'a', 'f', 'g', 'z', 'A', 'F', 'Z', '-', ' ')
    for radix <- Seq(2, 8, 10, 16, 36) do
      println(s"digit radix $radix: ${ds.map(c => Character.digit(c, radix)).mkString(" ")}")
    println(s"digit(int) ${Character.digit('7'.toInt, 10)} ${Character.digit('z'.toInt, 36)} ${Character.digit(0x1f600, 10)}")
    // Any Unicode decimal digit (Arabic-Indic, fullwidth, mathematical), and the fullwidth letters; no radix out of range.
    println(s"non-ASCII digits ${Seq(0x663, 0xff13, 0xff21, 0xff5a, 0x1d7d9, 0x1d7e3, 0x2160, 0xb2).map(Character.digit(_, 36)).mkString(" ")}")
    println(s"digit in radix 10 ${Seq(0x663, 0xff21, 0xff3a).map(Character.digit(_, 10)).mkString(" ")}")
    println(s"radix out of range ${Character.digit('0', 1)} ${Character.digit('5', 37)} ${Character.digit('0', 0)} ${Character.digit('0', -1)} ${Character.digit(0x663, 37)}")
    // Other_Uppercase and Other_Lowercase (Roman numerals, circled letters, ª, modifier letters); Other_Alphabetic.
    println(s"Other_Uppercase ${Seq(0x2160, 0x24b6, 0x1f130).map(Character.isUpperCase(_)).mkString(" ")}")
    println(s"Other_Lowercase ${Seq(0x2170, 0x24d0, 0xaa, 0xba, 0x2b0, 0x345).map(Character.isLowerCase(_)).mkString(" ")}")
    println(s"isAlphabetic ${Seq('a'.toInt, '1'.toInt, 0x2160, 0x345, 0x24b6, 0x5d0, 0x20, 0x1f600, 0x1d7d9).map(Character.isAlphabetic(_)).mkString(" ")}")
    println(s"forDigit ${(0 to 15).map(d => Character.forDigit(d, 16)).mkString} ${Character.forDigit(35, 36)} ${Character.forDigit(10, 10).toInt} ${Character.forDigit(-1, 10).toInt} ${Character.forDigit(1, 1).toInt} ${Character.forDigit(1, 37).toInt}")

  def identifiers(): Unit =
    println("== identifiers")
    val ids = Seq[Int]('a', 'Z', '_', '$', '0', '-', ' ', 0x00, 0x7f, 0xad, 0x200b, 0x2160, 0x0301, 0x00a2, 0x203f, 0x4e2d)
    println(s"samples                  ${ids.map(hex).mkString(" ")}")
    println(s"isJavaIdentifierStart    ${flags(ids)(Character.isJavaIdentifierStart)}")
    println(s"isJavaIdentifierPart     ${flags(ids)(Character.isJavaIdentifierPart)}")
    println(s"isUnicodeIdentifierStart ${flags(ids)(Character.isUnicodeIdentifierStart)}")
    println(s"isUnicodeIdentifierPart  ${flags(ids)(Character.isUnicodeIdentifierPart)}")
    println(s"isIdentifierIgnorable    ${flags(ids)(Character.isIdentifierIgnorable)}")
    println(s"chars ${Character.isJavaIdentifierStart('x')} ${Character.isJavaIdentifierPart('1')} ${Character.isUnicodeIdentifierStart('_')} ${Character.isUnicodeIdentifierPart('_')} ${Character.isIdentifierIgnorable('\u0000')}")

  def surrogates(): Unit =
    println("== surrogates")
    val cs = Seq('a', '퟿', '\ud800', '\udbff', '\udc00', '\udfff', '')
    println(s"isHighSurrogate ${cs.map(c => if Character.isHighSurrogate(c) then '1' else '0').mkString}")
    println(s"isLowSurrogate  ${cs.map(c => if Character.isLowSurrogate(c) then '1' else '0').mkString}")
    println(s"isSurrogate     ${cs.map(c => if Character.isSurrogate(c) then '1' else '0').mkString}")
    println(s"charCount ${Character.charCount('a')} ${Character.charCount(0xffff)} ${Character.charCount(0x10000)} ${Character.charCount(0x10ffff)}")
    println(s"toCodePoint ${hex(Character.toCodePoint('\ud83d', '\ude00'))} ${hex(Character.toCodePoint('\ud800', '\udc00'))} ${hex(Character.toCodePoint('\udbff', '\udfff'))}")
    val s = "😀"
    println(s"pair of ${hex(s.codePointAt(0))}: ${hex(s.charAt(0))} ${hex(s.charAt(1))}")

  def boxes(): Unit =
    println("== Character boxes")
    val a: Character = 'a'
    val b: Character = Character.valueOf('b')
    println(s"${a.charValue} ${a.compareTo(b)} ${b.compareTo(a)} ${Character.compare('a', 'z')} ${Character.compare('z', 'a')} ${a.equals('a')} ${a.equals("a")} ${a.hashCode} ${Character.hashCode('Z')}")
    println(s"${Character.toString('q')} ${a.toString} ${Character.MIN_RADIX} ${Character.MAX_RADIX} ${Character.MIN_VALUE.toInt} ${Character.MAX_VALUE.toInt} ${Character.MIN_SUPPLEMENTARY_CODE_POINT} ${Character.MAX_CODE_POINT}")
    println(s"${hex(Character.MIN_HIGH_SURROGATE)} ${hex(Character.MAX_LOW_SURROGATE)} ${Character.SIZE} ${Character.BYTES}")
    val sorted = java.util.Arrays.asList[Character]('c', 'A', 'b', 'a')
    java.util.Collections.sort(sorted)
    println(sorted)
