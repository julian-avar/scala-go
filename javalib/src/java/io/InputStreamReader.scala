package java.io

import java.nio.charset.{Charset, CharsetDecoder, Coding, CodingErrorAction}

/** Decodes an InputStream with a charset's decoder, on arrays (see `CharsetDecoder.decode`). As
 *  in the JDK, malformed input is replaced unless the reader is given its own decoder.
 */
class InputStreamReader(in: InputStream, decoder: CharsetDecoder) extends Reader(in):
  if decoder == null then throw new NullPointerException("charset decoder")

  def this(in: InputStream, charset: Charset) =
    this(in, InputStreamReader.replacing(charset))

  def this(in: InputStream) = this(in, Charset.defaultCharset())

  def this(in: InputStream, charsetName: String) =
    this(in, IOUtils.charsetForName(charsetName))

  private var closed = false
  private val bytes = new Array[Byte](8192)
  private var start = 0
  private var end = 0
  private var eof = false
  /** A char decoded but not yet returned (the low surrogate of a pair read char by char), or -1. */
  private var pending = -1
  /** A small output for reads of fewer than two chars, which may not fit a surrogate pair. */
  private val small = new Array[Char](2)

  def getEncoding(): String = if closed then null else decoder.charset().historicalName

  override def close(): Unit = lock.synchronized {
    if !closed then
      closed = true
      in.close()
  }

  private def ensureOpen(): Unit = if closed then throw new IOException("Stream closed")

  override def read(): Int = lock.synchronized {
    ensureOpen()
    if pending >= 0 then
      val c = pending
      pending = -1
      c
    else
      val n = fill(small, 0, 2)
      if n == -1 then -1
      else
        if n == 2 then pending = small(1)
        small(0)
  }

  def read(cbuf: Array[Char], off: Int, len: Int): Int = lock.synchronized {
    ensureOpen()
    IOUtils.checkFromIndexSize(off, len, cbuf.length)
    if len == 0 then 0
    else if pending >= 0 then
      cbuf(off) = pending.toChar
      pending = -1
      1
    else if len == 1 then
      val c = read()
      if c == -1 then -1
      else
        cbuf(off) = c.toChar
        1
    else fill(cbuf, off, off + len)
  }

  /** Decodes into `out[off, end)` (at least two chars), reading the stream as needed: the number
   *  of chars decoded, or -1 at the end of the input.
   */
  private def fill(out: Array[Char], off: Int, outEnd: Int): Int =
    var n = 0
    while n == 0 do
      decoder.inPos = start
      decoder.outPos = off
      val status = decoder.decodeArray(bytes, end, out, outEnd, eof)
      start = decoder.inPos
      n = decoder.outPos - off
      // Malformed input is reported once the chars before it have been read.
      if status == Coding.Error && n == 0 then Coding.result(status, decoder.errorLength, decoder.errorUnmappable).throwException()
      if n == 0 then
        if eof then return -1
        refill()
    n

  private def refill(): Unit =
    if start > 0 then
      System.arraycopy(bytes, start, bytes, 0, end - start)
      end -= start
      start = 0
    val count = in.read(bytes, end, bytes.length - end)
    if count == -1 then eof = true else end += count

  override def ready(): Boolean = lock.synchronized {
    ensureOpen()
    pending >= 0 || start < end || in.available() > 0
  }

object InputStreamReader:
  private def replacing(charset: Charset): CharsetDecoder =
    if charset == null then throw new NullPointerException("charset")
    charset.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
