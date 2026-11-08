package java.net

import java.io.UnsupportedEncodingException
import java.nio.charset.{Charset, IllegalCharsetNameException, UnsupportedCharsetException}

/** application/x-www-form-urlencoded encoding: letters, digits and " .-*_" stay (a space
 *  becomes '+'), runs of other chars become the escapes of their bytes in the charset.
 */
object URLEncoder:
  private def dontNeedEncoding(c: Char): Boolean =
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || " -_.*".indexOf(c) >= 0

  @deprecated def encode(s: String): String = encode(s, Charset.defaultCharset())

  def encode(s: String, enc: String): String =
    if enc == null then throw new NullPointerException("charsetName")
    encode(s, charsetFor(enc))

  def encode(s: String, charset: Charset): String =
    if charset == null then throw new NullPointerException("charset")
    var changed = false
    val out = new java.lang.StringBuilder(s.length)
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      if dontNeedEncoding(c) then
        if c == ' ' then
          out.append('+')
          changed = true
        else out.append(c)
        i += 1
      else
        val start = i
        while i < s.length && !dontNeedEncoding(s.charAt(i)) do i += 1
        for b <- s.substring(start, i).getBytes(charset) do
          out.append('%').append(hex.charAt((b >> 4) & 0xf)).append(hex.charAt(b & 0xf))
        changed = true
    if changed then out.toString else s

  private val hex = "0123456789ABCDEF"

  private[net] def charsetFor(enc: String): Charset =
    try Charset.forName(enc)
    catch
      case _: IllegalCharsetNameException | _: UnsupportedCharsetException => throw new UnsupportedEncodingException(enc)
