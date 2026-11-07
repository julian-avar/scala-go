package java.io

import java.nio.charset.{Charset, CharsetEncoder, CodingErrorAction}

/** Encodes chars with a charset's encoder into an OutputStream, buffering the bytes (see
 *  `CharsetEncoder.encode`). As in the JDK, unencodable chars are replaced ('?') unless the
 *  writer is given its own encoder.
 */
class OutputStreamWriter(out: OutputStream, encoder: CharsetEncoder) extends Writer(out):
  if encoder == null then throw new NullPointerException("charset encoder")

  def this(out: OutputStream, charset: Charset) =
    this(out, OutputStreamWriter.replacing(charset))

  def this(out: OutputStream) = this(out, Charset.defaultCharset())

  def this(out: OutputStream, charsetName: String) =
    this(out, IOUtils.charsetForName(charsetName))

  private var closed = false
  private val bytes = new Array[Byte](8192)
  private var count = 0
  /** Chars to encode: a high surrogate carried over from the previous write, then the input. */
  private var chars = new Array[Char](0)
  private var carry = false

  def getEncoding(): String = if closed then null else encoder.charset().historicalName

  private def ensureOpen(): Unit = if closed then throw new IOException("Stream closed")

  override def write(c: Int): Unit = lock.synchronized {
    ensureOpen()
    val buf = chunk(1)
    buf(0) = c.toChar
    encode(buf, 0, 1, endOfInput = false)
  }

  def write(cbuf: Array[Char], off: Int, len: Int): Unit = lock.synchronized {
    ensureOpen()
    IOUtils.checkFromIndexSize(off, len, cbuf.length)
    encode(cbuf, off, len, endOfInput = false)
  }

  override def write(str: String, off: Int, len: Int): Unit = lock.synchronized {
    ensureOpen()
    IOUtils.checkFromIndexSize(off, len, str.length())
    // In chunks, to bound the copy.
    var pos = off
    val end = off + len
    while pos < end do
      val n = Math.min(end - pos, 4096)
      val buf = chunk(n)
      str.getChars(pos, pos + n, buf, 0)
      encode(buf, 0, n, endOfInput = false)
      pos += n
  }

  private var chunkBuffer: Array[Char] = null

  private def chunk(n: Int): Array[Char] =
    if chunkBuffer == null || chunkBuffer.length < n then chunkBuffer = new Array[Char](Math.max(n, 256))
    chunkBuffer

  /** Encodes `in[off, off + len)` into the byte buffer, writing it out when full. */
  private def encode(in: Array[Char], off: Int, len: Int, endOfInput: Boolean): Unit =
    var src = in
    var srcPos = off
    var srcEnd = off + len
    if carry then
      // Prepend the high surrogate carried over from the previous write.
      if chars.length < len + 1 then chars = new Array[Char](len + 1)
      chars(0) = carriedChar
      System.arraycopy(in, off, chars, 1, len)
      src = chars
      srcPos = 0
      srcEnd = len + 1
      carry = false
    encoder.inPos = srcPos
    while {
      encoder.outPos = count
      val overflow = encoder.encode(src, srcEnd, bytes, bytes.length, endOfInput)
      count = encoder.outPos
      if overflow then flushBuffer()
      overflow
    } do ()
    if encoder.inPos < srcEnd then
      // A trailing high surrogate: keep it for the next write.
      carriedChar = src(encoder.inPos)
      carry = true

  private var carriedChar: Char = 0

  /** Writes the encoded bytes to the stream, without flushing it. */
  private[io] def flushBuffer(): Unit = lock.synchronized {
    if count > 0 then
      out.write(bytes, 0, count)
      count = 0
  }

  def flush(): Unit = lock.synchronized {
    ensureOpen()
    flushBuffer()
    out.flush()
  }

  def close(): Unit = lock.synchronized {
    if !closed then
      try
        if carry then encode(new Array[Char](0), 0, 0, endOfInput = true)
        flushBuffer()
      finally
        closed = true
        out.close()
  }

object OutputStreamWriter:
  private def replacing(charset: Charset): CharsetEncoder =
    if charset == null then throw new NullPointerException("charset")
    charset.newEncoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
