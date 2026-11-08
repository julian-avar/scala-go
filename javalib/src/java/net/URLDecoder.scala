package java.net

import java.io.UnsupportedEncodingException
import java.nio.charset.Charset

/** Decodes application/x-www-form-urlencoded strings: '+' is a space, and runs of escapes are
 *  the bytes of chars in the charset.
 */
object URLDecoder:
  @deprecated def decode(s: String): String = decode(s, Charset.defaultCharset())

  def decode(s: String, enc: String): String =
    if enc.isEmpty then throw new UnsupportedEncodingException("URLDecoder: empty string enc parameter")
    decode(s, URLEncoder.charsetFor(enc))

  def decode(s: String, charset: Charset): String =
    java.util.Objects.requireNonNull(charset, "Charset")
    var changed = false
    val n = s.length
    val sb = new java.lang.StringBuilder(if n > 500 then n / 2 else n)
    var bytes: Array[Byte] = null
    var i = 0
    while i < n do
      s.charAt(i) match
        case '+' =>
          sb.append(' ')
          i += 1
          changed = true
        case '%' =>
          if bytes == null then bytes = new Array[Byte]((n - i) / 3)
          var pos = 0
          while i + 2 < n && s.charAt(i) == '%' do
            val v = hexPair(s, i + 1)
            if v < 0 then
              throw new IllegalArgumentException("URLDecoder: Illegal hex characters in escape (%) pattern - negative value")
            bytes(pos) = v.toByte
            pos += 1
            i += 3
          if i < n && s.charAt(i) == '%' then
            throw new IllegalArgumentException("URLDecoder: Incomplete trailing escape (%) pattern")
          sb.append(new String(bytes, 0, pos, charset))
          changed = true
        case c =>
          sb.append(c)
          i += 1
    if changed then sb.toString else s

  /** The value of the two chars at `i` as Integer.parseInt(s, i, i + 2, 16) has it. */
  private def hexPair(s: String, i: Int): Int =
    val first = s.charAt(i)
    val signed = first == '-' || first == '+'
    var v = 0
    for k <- (if signed then 1 else 0) until 2 do
      val d = Character.digit(s.charAt(i + k), 16)
      if d < 0 then
        throw new IllegalArgumentException(
          "URLDecoder: Illegal hex characters in escape (%) pattern - Error at index " + k + " in: \"" +
            s.substring(i, i + 2) + "\"")
      v = v * 16 + d
    if first == '-' then -v else v
