package java.nio.charset

import scala.annotation.static

final class StandardCharsets private ()

object StandardCharsets:
  @static val UTF_8: Charset = new UTF8Charset
  @static val ISO_8859_1: Charset = new Latin1Charset(0xff, "ISO-8859-1", "ISO8859_1", Array(
    "iso-ir-100", "ISO_8859-1", "latin1", "l1", "IBM819", "cp819", "csISOLatin1", "819", "IBM-819",
    "ISO8859_1", "ISO_8859-1:1987", "ISO_8859_1", "8859_1", "ISO8859-1"))
  @static val US_ASCII: Charset = new Latin1Charset(0x7f, "US-ASCII", "ASCII", Array(
    "iso-ir-6", "ANSI_X3.4-1986", "ISO_646.irv:1991", "ASCII", "iso_646.irv:1983", "ANSI_X3.4-1968",
    "ISO646-US", "default", "cp367", "csASCII", "646", "us", "IBM367"))

private[charset] final class UTF8Charset extends Charset("UTF-8", Array("UTF8", "unicode-1-1-utf-8")):
  def contains(cs: Charset): Boolean = true
  def newDecoder(): CharsetDecoder = new UTF8Decoder(this)
  def newEncoder(): CharsetEncoder = new UTF8Encoder(this)
  private[java] def historicalName: String = "UTF8"

/** ISO-8859-1 (`max` 0xff) and US-ASCII (`max` 0x7f): one byte per char. */
private[charset] final class Latin1Charset(val max: Int, name: String, val historical: String, aliases: Array[String])
    extends Charset(name, aliases):
  def contains(cs: Charset): Boolean = cs match
    case cs: Latin1Charset => cs.max <= max
    case _                 => false
  def newDecoder(): CharsetDecoder = new Latin1Decoder(this, max)
  def newEncoder(): CharsetEncoder = new Latin1Encoder(this, max)
  private[java] def historicalName: String = historical
