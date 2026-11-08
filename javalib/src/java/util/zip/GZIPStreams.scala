package java.util.zip

import java.io.{ByteArrayInputStream, EOFException, IOException, InputStream, OutputStream}

/** Reads the GZIP format, including several members one after the other, as the JDK's. */
class GZIPInputStream(_in: InputStream, size: Int)
    extends InflaterInputStream(_in, if _in == null then null else new Inflater(true), size):
  usesDefaultInflater = true

  protected var crc: CRC32 = new CRC32()
  protected var eos: Boolean = false
  private var closed = false
  private val skipBuffer = new Array[Byte](128)

  readHeader(in)

  def this(in: InputStream) = this(in, 512)

  override def read(buf: Array[Byte], off: Int, len: Int): Int =
    ensureOpen()
    if eos then -1
    else
      val n = super.read(buf, off, len)
      if n == -1 then
        if readTrailer() then
          eos = true
          -1
        else read(buf, off, len)
      else
        crc.update(buf, off, n)
        n

  override def close(): Unit =
    if !closed then
      super.close()
      eos = true
      closed = true

  /** Reads a member's header and returns its length. */
  private def readHeader(thisIn: InputStream): Int =
    val in = new CheckedInputStream(thisIn, crc)
    crc.reset()
    if readUShort(in) != GZIPInputStream.GZIP_MAGIC then throw new ZipException("Not in GZIP format")
    if readUByte(in) != 8 then throw new ZipException("Unsupported compression method")
    val flags = readUByte(in)
    skipBytes(in, 6)
    var n = 10
    if (flags & GZIPInputStream.FEXTRA) == GZIPInputStream.FEXTRA then
      val m = readUShort(in)
      skipBytes(in, m)
      n += m + 2
    if (flags & GZIPInputStream.FNAME) == GZIPInputStream.FNAME then
      n += 1
      while readUByte(in) != 0 do n += 1
    if (flags & GZIPInputStream.FCOMMENT) == GZIPInputStream.FCOMMENT then
      n += 1
      while readUByte(in) != 0 do n += 1
    if (flags & GZIPInputStream.FHCRC) == GZIPInputStream.FHCRC then
      val v = crc.getValue().toInt & 0xffff
      if readUShort(in) != v then throw new ZipException("Corrupt GZIP header")
      n += 2
    crc.reset()
    n

  /** Checks a member's trailer: true at the end of the input, false if another member follows
   *  (whose header it has read).
   */
  private def readTrailer(): Boolean =
    val remaining = inf.getRemaining()
    // The trailer, and what follows it, start with the input the inflater did not consume.
    val in: InputStream =
      if remaining > 0 then new GZIPInputStream.Concatenated(new ByteArrayInputStream(buf, len - remaining, remaining), this.in)
      else this.in
    if readUInt(in) != crc.getValue() || readUInt(in) != (inf.getBytesWritten() & 0xffffffffL) then
      throw new ZipException("Corrupt GZIP trailer")
    if this.in.available() > 0 || remaining > 26 then
      val m =
        try 8 + readHeader(in)
        catch case _: IOException => return true // not a member: ignored
      inf.reset()
      if remaining > m then inf.setInput(buf, len - remaining + m, remaining - m)
      false
    else true

  private def readUInt(in: InputStream): Long =
    val s = readUShort(in).toLong
    (readUShort(in).toLong << 16) | s

  private def readUShort(in: InputStream): Int =
    val b = readUByte(in)
    (readUByte(in) << 8) | b

  private def readUByte(in: InputStream): Int =
    val b = in.read()
    if b == -1 then throw new EOFException()
    if b < -1 || b > 255 then
      throw new IOException(s"${this.in.getClass.getName}.read() returned value out of range -1..255: $b")
    b

  private def skipBytes(in: InputStream, n: Int): Unit =
    var left = n
    while left > 0 do
      val len = in.read(skipBuffer, 0, Math.min(left, skipBuffer.length))
      if len == -1 then throw new EOFException()
      left -= len

object GZIPInputStream:
  final val GZIP_MAGIC = 0x8b1f
  private final val FHCRC = 2
  private final val FEXTRA = 4
  private final val FNAME = 8
  private final val FCOMMENT = 16

  /** Reads `first`, then `second` (which it does not close). */
  private final class Concatenated(first: InputStream, second: InputStream) extends InputStream:
    private var current = first
    override def read(): Int =
      var b = current.read()
      if b == -1 && (current eq first) then
        current = second
        b = current.read()
      b
    override def read(b: Array[Byte], off: Int, len: Int): Int =
      var n = current.read(b, off, len)
      if n == -1 && (current eq first) then
        current = second
        n = current.read(b, off, len)
      n
    override def available(): Int = current.available()

/** Writes the GZIP format, as the JDK's (the header has no time stamp and an unknown OS). */
class GZIPOutputStream(_out: OutputStream, size: Int, syncFlush: Boolean)
    extends DeflaterOutputStream(_out, if _out == null then null else new Deflater(Deflater.DEFAULT_COMPRESSION, true),
      size, syncFlush):
  usesDefaultDeflater = true

  protected var crc: CRC32 = new CRC32()

  out.write(Array[Byte](0x1f, 0x8b.toByte, Deflater.DEFLATED.toByte, 0, 0, 0, 0, 0, 0, 0xff.toByte))

  def this(out: OutputStream, size: Int) = this(out, size, false)
  def this(out: OutputStream, syncFlush: Boolean) = this(out, 512, syncFlush)
  def this(out: OutputStream) = this(out, 512, false)

  override def write(buf: Array[Byte], off: Int, len: Int): Unit =
    super.write(buf, off, len)
    crc.update(buf, off, len)

  override def finish(): Unit =
    if !`def`.finished() then
      `def`.finish()
      while !`def`.finished() do
        var n = `def`.deflate(buf, 0, buf.length)
        if `def`.finished() && n <= buf.length - 8 then
          writeTrailer(buf, n)
          n += 8
          out.write(buf, 0, n)
          return
        if n > 0 then out.write(buf, 0, n)
      val trailer = new Array[Byte](8)
      writeTrailer(trailer, 0)
      out.write(trailer)

  private def writeTrailer(b: Array[Byte], off: Int): Unit =
    ZipUtils.put32(b, off, crc.getValue())
    ZipUtils.put32(b, off + 4, `def`.getTotalIn().toLong)
