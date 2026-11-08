package java.nio.channels

import java.io.{FileInputStream, FileOutputStream, IOException, InputStream, OutputStream, Reader, Writer}
import java.nio.ByteBuffer
import java.nio.charset.{Charset, CharsetDecoder, CharsetEncoder}

/** java.nio.channels.Channels: streams on channels and channels on streams, as the JDK's. */
final class Channels private ()

object Channels:
  def newInputStream(ch: ReadableByteChannel): InputStream =
    if ch == null then throw new NullPointerException("ch")
    new ChannelInputStream(ch)

  def newOutputStream(ch: WritableByteChannel): OutputStream =
    if ch == null then throw new NullPointerException("ch")
    new ChannelOutputStream(ch)

  /** The stream's own channel for file streams, otherwise a channel reading the stream. */
  def newChannel(in: InputStream): ReadableByteChannel =
    if in == null then throw new NullPointerException("in")
    in match
      case f: FileInputStream if f.getClass() eq classOf[FileInputStream] => f.getChannel()
      case _                                                                => new InputStreamChannel(in)

  def newChannel(out: OutputStream): WritableByteChannel =
    if out == null then throw new NullPointerException("out")
    out match
      case f: FileOutputStream if f.getClass() eq classOf[FileOutputStream] => f.getChannel()
      case _                                                                  => new OutputStreamChannel(out)

  def newReader(ch: ReadableByteChannel, dec: CharsetDecoder, minBufferCap: Int): Reader =
    if dec == null then throw new NullPointerException("dec")
    new java.io.InputStreamReader(newInputStream(ch), dec.reset())

  def newReader(ch: ReadableByteChannel, csName: String): Reader =
    if csName == null then throw new NullPointerException("csName")
    newReader(ch, Charset.forName(csName).newDecoder(), -1)

  def newReader(ch: ReadableByteChannel, charset: Charset): Reader =
    if charset == null then throw new NullPointerException("charset")
    newReader(ch, charset.newDecoder(), -1)

  def newWriter(ch: WritableByteChannel, enc: CharsetEncoder, minBufferCap: Int): Writer =
    if enc == null then throw new NullPointerException("enc")
    new java.io.OutputStreamWriter(newOutputStream(ch), enc.reset())

  def newWriter(ch: WritableByteChannel, csName: String): Writer =
    if csName == null then throw new NullPointerException("csName")
    newWriter(ch, Charset.forName(csName).newEncoder(), -1)

  def newWriter(ch: WritableByteChannel, charset: Charset): Writer =
    if charset == null then throw new NullPointerException("charset")
    newWriter(ch, charset.newEncoder(), -1)

  private final class ChannelInputStream(ch: ReadableByteChannel) extends InputStream:
    private var one: Array[Byte] = null

    override def read(): Int =
      if one == null then one = new Array[Byte](1)
      val n = read(one, 0, 1)
      if n == 1 then one(0) & 0xff else -1

    override def read(b: Array[Byte], off: Int, len: Int): Int =
      java.util.Objects.checkFromIndexSize(off, len, b.length)
      if len == 0 then 0
      else
        val buffer = ByteBuffer.wrap(b, off, len)
        var n = 0
        while n == 0 do n = ch.read(buffer) // a non-blocking channel may read nothing
        n

    override def available(): Int = ch match
      case s: SeekableByteChannel => Math.max(0L, Math.min(s.size() - s.position(), Int.MaxValue.toLong)).toInt
      case _                      => 0

    override def skip(n: Long): Long = ch match
      case s: SeekableByteChannel =>
        val position = s.position()
        val size = s.size()
        val skipped = if n <= 0 || position >= size then 0L else Math.min(n, size - position)
        s.position(position + skipped)
        skipped
      case _ => super.skip(n)

    override def close(): Unit = ch.close()

  private final class ChannelOutputStream(ch: WritableByteChannel) extends OutputStream:
    private var one: Array[Byte] = null

    override def write(b: Int): Unit =
      if one == null then one = new Array[Byte](1)
      one(0) = b.toByte
      write(one, 0, 1)

    override def write(b: Array[Byte], off: Int, len: Int): Unit =
      java.util.Objects.checkFromIndexSize(off, len, b.length)
      val buffer = ByteBuffer.wrap(b, off, len)
      while buffer.hasRemaining() do ch.write(buffer)

    override def close(): Unit = ch.close()

  /** As the JDK's `ReadableByteChannelImpl`: a read blocks at most once. */
  private final class InputStreamChannel(in: InputStream) extends spi.AbstractInterruptibleChannel
      with ReadableByteChannel:
    private var buffer = new Array[Byte](0)

    def read(dst: ByteBuffer): Int =
      if !isOpen() then throw new ClosedChannelException()
      val len = dst.remaining()
      var total = 0
      var n = 0
      var done = false
      while !done && total < len do
        val wanted = Math.min(len - total, 8192)
        if buffer.length < wanted then buffer = new Array[Byte](wanted)
        if total > 0 && in.available() <= 0 then done = true
        else
          n = in.read(buffer, 0, wanted)
          if n < 0 then done = true
          else
            total += n
            dst.put(buffer, 0, n)
      if n < 0 && total == 0 then -1 else total

    protected def implCloseChannel(): Unit = in.close()

  private final class OutputStreamChannel(out: OutputStream) extends spi.AbstractInterruptibleChannel
      with WritableByteChannel:
    def write(src: ByteBuffer): Int =
      if !isOpen() then throw new ClosedChannelException()
      val n = src.remaining()
      out.write(src.hb, src.offset + src.pos, n)
      src.pos += n
      n

    protected def implCloseChannel(): Unit = out.close()
