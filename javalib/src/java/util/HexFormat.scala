package java.util

import java.io.{IOException, UncheckedIOException}

/** JDK 17's formatter and parser of hexadecimal strings. */
final class HexFormat private (delim: String, pre: String, suf: String, upper: Boolean):
  private def digits: String = if upper then HexFormat.Upper else HexFormat.Lower

  def withDelimiter(delimiter: String): HexFormat = new HexFormat(Objects.requireNonNull(delimiter, "delimiter"), pre, suf, upper)
  def withPrefix(prefix: String): HexFormat = new HexFormat(delim, Objects.requireNonNull(prefix, "prefix"), suf, upper)
  def withSuffix(suffix: String): HexFormat = new HexFormat(delim, pre, Objects.requireNonNull(suffix, "suffix"), upper)
  def withUpperCase(): HexFormat = if upper then this else new HexFormat(delim, pre, suf, true)
  def withLowerCase(): HexFormat = if !upper then this else new HexFormat(delim, pre, suf, false)

  def delimiter(): String = delim
  def prefix(): String = pre
  def suffix(): String = suf
  def isUpperCase(): Boolean = upper

  def formatHex(bytes: Array[Byte]): String = formatHex(bytes, 0, bytes.length)

  def formatHex(bytes: Array[Byte], fromIndex: Int, toIndex: Int): String =
    Objects.requireNonNull(bytes, "bytes")
    Objects.checkFromToIndex(fromIndex, toIndex, bytes.length)
    val sb = new java.lang.StringBuilder((toIndex - fromIndex) * (2 + pre.length + suf.length + delim.length))
    appendHex(sb, bytes, fromIndex, toIndex)
    sb.toString

  def formatHex[A <: Appendable](out: A, bytes: Array[Byte]): A = formatHex(out, bytes, 0, bytes.length)

  def formatHex[A <: Appendable](out: A, bytes: Array[Byte], fromIndex: Int, toIndex: Int): A =
    Objects.requireNonNull(out, "out")
    Objects.requireNonNull(bytes, "bytes")
    Objects.checkFromToIndex(fromIndex, toIndex, bytes.length)
    try appendHex(out, bytes, fromIndex, toIndex)
    catch case e: IOException => throw new UncheckedIOException(e.getMessage, e)
    out

  private def appendHex(out: Appendable, bytes: Array[Byte], fromIndex: Int, toIndex: Int): Unit =
    var i = fromIndex
    while i < toIndex do
      if i > fromIndex then out.append(delim)
      out.append(pre)
      out.append(toHighHexDigit(bytes(i)))
      out.append(toLowHexDigit(bytes(i)))
      out.append(suf)
      i += 1

  def parseHex(string: CharSequence): Array[Byte] = parseHex(string, 0, string.length)

  def parseHex(string: CharSequence, fromIndex: Int, toIndex: Int): Array[Byte] =
    Objects.requireNonNull(string, "string")
    Objects.checkFromToIndex(fromIndex, toIndex, string.length)
    val s = string.subSequence(fromIndex, toIndex)
    val length = s.length
    if length == 0 then new Array[Byte](0)
    else if delim.isEmpty && pre.isEmpty && suf.isEmpty then
      if (length & 1) != 0 then throw new IllegalArgumentException(s"string length not even: $length")
      val bytes = new Array[Byte](length / 2)
      var i = 0
      while i < bytes.length do
        bytes(i) = HexFormat.fromHexDigits(s, 2 * i).toByte
        i += 1
      bytes
    else
      val valueChars = pre.length + 2L + suf.length
      val stride = valueChars + delim.length
      if (length - valueChars) % stride != 0 then
        throw new IllegalArgumentException(
          "extra or missing delimiters or values consisting of prefix, two hexadecimal digits, and suffix")
      checkLiteral(s, 0, pre)
      checkLiteral(s, length - suf.length, suf)
      val between = suf + delim + pre
      val count = ((length - valueChars) / stride + 1).toInt
      val bytes = new Array[Byte](count)
      var offset = pre.length
      var i = 0
      while i < count do
        bytes(i) = HexFormat.fromHexDigits(s, offset).toByte
        if i < count - 1 then checkLiteral(s, offset + 2, between)
        offset += 2 + between.length
        i += 1
      bytes

  def parseHex(chars: Array[Char], fromIndex: Int, toIndex: Int): Array[Byte] =
    Objects.requireNonNull(chars, "chars")
    Objects.checkFromToIndex(fromIndex, toIndex, chars.length)
    parseHex(java.nio.CharBuffer.wrap(chars, fromIndex, toIndex - fromIndex))

  private def checkLiteral(s: CharSequence, index: Int, literal: String): Unit =
    var i = 0
    while i < literal.length do
      if s.charAt(index + i) != literal.charAt(i) then
        throw new IllegalArgumentException(HexFormat.escapeNL(
          s"found: \"${s.subSequence(index, index + literal.length)}\", expected: \"$literal\", index: $index ch: ${s.charAt(index + i).toInt}"))
      i += 1

  def toLowHexDigit(value: Int): Char = digits.charAt(value & 0xf)
  def toHighHexDigit(value: Int): Char = digits.charAt((value >> 4) & 0xf)

  def toHexDigits[A <: Appendable](out: A, value: Byte): A =
    Objects.requireNonNull(out, "out")
    try
      out.append(toHighHexDigit(value))
      out.append(toLowHexDigit(value))
    catch case e: IOException => throw new UncheckedIOException(e.getMessage, e)
    out

  def toHexDigits(value: Byte): String = new String(Array(toHighHexDigit(value), toLowHexDigit(value)))
  def toHexDigits(value: Char): String = toHexDigits(value.toLong, 4)
  def toHexDigits(value: Short): String = toHexDigits(value.toLong, 4)
  def toHexDigits(value: Int): String = toHexDigits(value.toLong, 8)
  def toHexDigits(value: Long): String = toHexDigits(value, 16)

  def toHexDigits(value: Long, digits: Int): String =
    if digits < 0 || digits > 16 then throw new IllegalArgumentException(s"number of digits: $digits")
    val chars = new Array[Char](digits)
    var v = value
    var i = digits - 1
    while i >= 0 do
      chars(i) = toLowHexDigit(v.toInt)
      v >>>= 4
      i -= 1
    new String(chars)

  override def equals(o: Any): Boolean = o match
    case that: HexFormat =>
      upper == that.isUpperCase() && delim == that.delimiter() && pre == that.prefix()
      && suf == that.suffix()
    case _ => false

  override def hashCode(): Int =
    val result = Arrays.hashCode(Array[AnyRef](delim, pre, suf))
    31 * result + java.lang.Boolean.hashCode(upper)

  override def toString(): String =
    HexFormat.escapeNL(s"uppercase: $upper, delimiter: \"$delim\", prefix: \"$pre\", suffix: \"$suf\"")

object HexFormat:
  private final val Lower = "0123456789abcdef"
  private final val Upper = "0123456789ABCDEF"
  private val Hex = new HexFormat("", "", "", false)

  def of(): HexFormat = Hex
  def ofDelimiter(delimiter: String): HexFormat = new HexFormat(Objects.requireNonNull(delimiter, "delimiter"), "", "", false)

  private def escapeNL(s: String): String = s.replace("\n", "\\n").replace("\r", "\\r")

  def isHexDigit(ch: Int): Boolean =
    (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F')

  def fromHexDigit(ch: Int): Int =
    if ch >= '0' && ch <= '9' then ch - '0'
    else if ch >= 'a' && ch <= 'f' then ch - 'a' + 10
    else if ch >= 'A' && ch <= 'F' then ch - 'A' + 10
    else throw new NumberFormatException(s"not a hexadecimal digit: \"${ch.toChar}\" = $ch")

  /** Two digits at `index`. */
  private def fromHexDigits(s: CharSequence, index: Int): Int =
    (fromHexDigit(s.charAt(index)) << 4) | fromHexDigit(s.charAt(index + 1))

  def fromHexDigits(string: CharSequence): Int = fromHexDigits(string, 0, string.length)

  def fromHexDigits(string: CharSequence, fromIndex: Int, toIndex: Int): Int =
    Objects.requireNonNull(string, "string")
    Objects.checkFromToIndex(fromIndex, toIndex, string.length)
    checkDigitCount(fromIndex, toIndex, 8)
    var value = 0
    var i = fromIndex
    while i < toIndex do
      value = (value << 4) + fromHexDigit(string.charAt(i))
      i += 1
    value

  def fromHexDigitsToLong(string: CharSequence): Long = fromHexDigitsToLong(string, 0, string.length)

  def fromHexDigitsToLong(string: CharSequence, fromIndex: Int, toIndex: Int): Long =
    Objects.requireNonNull(string, "string")
    Objects.checkFromToIndex(fromIndex, toIndex, string.length)
    checkDigitCount(fromIndex, toIndex, 16)
    var value = 0L
    var i = fromIndex
    while i < toIndex do
      value = (value << 4) + fromHexDigit(string.charAt(i))
      i += 1
    value

  private def checkDigitCount(fromIndex: Int, toIndex: Int, limit: Int): Unit =
    val length = toIndex - fromIndex
    if length > limit then throw new IllegalArgumentException(s"string length greater than $limit: $length")
