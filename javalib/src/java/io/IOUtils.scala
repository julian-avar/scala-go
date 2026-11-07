package java.io

import java.nio.charset.{Charset, IllegalCharsetNameException, UnsupportedCharsetException}

/** Helpers shared by javalib's java.io and java.nio.file classes. */
private[java] object IOUtils:
  /** `Objects.checkFromIndexSize`, with the JDK's message. */
  def checkFromIndexSize(fromIndex: Int, size: Int, length: Int): Unit =
    if (length | fromIndex | size) < 0 || size > length - fromIndex then
      throw new IndexOutOfBoundsException(
        "Range [" + fromIndex + ", " + fromIndex + " + " + size + ") out of bounds for length " + length)

  // java.util.Arrays' copies (javalib's java.util.Arrays does not declare them).
  def copyOfRange(a: Array[Byte], from: Int, to: Int): Array[Byte] =
    val copy = new Array[Byte](to - from)
    System.arraycopy(a, from, copy, 0, Math.min(a.length, to) - from)
    copy

  def copyOf(a: Array[Char], length: Int): Array[Char] =
    val copy = new Array[Char](length)
    System.arraycopy(a, 0, copy, 0, Math.min(a.length, length))
    copy

  /** The charset of a java.io constructor taking a charset name. */
  def charsetForName(charsetName: String): Charset =
    if charsetName == null then throw new NullPointerException("charsetName")
    try Charset.forName(charsetName)
    catch
      case _: UnsupportedCharsetException | _: IllegalCharsetNameException =>
        throw new UnsupportedEncodingException(charsetName)
