package java.util.zip

import java.nio.ByteBuffer

import scalago.runtime.Zip

trait Checksum:
  def update(b: Int): Unit
  def update(b: Array[Byte]): Unit = update(b, 0, b.length)
  def update(b: Array[Byte], off: Int, len: Int): Unit
  def getValue(): Long
  def reset(): Unit

  def update(buffer: ByteBuffer): Unit =
    val pos = buffer.position()
    val rem = buffer.limit() - pos
    if rem > 0 then
      if buffer.hasArray() then update(buffer.array(), buffer.arrayOffset() + pos, rem)
      else
        val b = new Array[Byte](Math.min(rem, 4096))
        while buffer.hasRemaining() do
          val n = Math.min(buffer.remaining(), b.length)
          buffer.get(b, 0, n)
          update(b, 0, n)
      buffer.position(pos + rem)

private[zip] object ZipUtils:
  /** The JDK's range check in java.util.zip. */
  def checkRange(off: Int, len: Int, length: Int): Unit =
    if (length | off | len) < 0 || len > length - off then
      throw new ArrayIndexOutOfBoundsException(s"Range [$off, $off + $len) out of bounds for length $length")

  def u16(b: Array[Byte], off: Int): Int = (b(off) & 0xff) | ((b(off + 1) & 0xff) << 8)
  def u32(b: Array[Byte], off: Int): Long = (u16(b, off) | (u16(b, off + 2).toLong << 16)) & 0xffffffffL
  def u64(b: Array[Byte], off: Int): Long = u32(b, off) | (u32(b, off + 4) << 32)

  def put16(b: Array[Byte], off: Int, v: Int): Unit =
    b(off) = v.toByte
    b(off + 1) = (v >> 8).toByte

  def put32(b: Array[Byte], off: Int, v: Long): Unit =
    put16(b, off, v.toInt)
    put16(b, off + 2, (v >> 16).toInt)

  def put64(b: Array[Byte], off: Int, v: Long): Unit =
    put32(b, off, v)
    put32(b, off + 4, v >>> 32)

class CRC32 extends Checksum:
  private var crc = 0

  def update(b: Int): Unit = update(Array(b.toByte), 0, 1)
  def update(b: Array[Byte], off: Int, len: Int): Unit =
    ZipUtils.checkRange(off, len, b.length)
    crc = Zip.crc32(crc, b, off, len)
  override def update(b: Array[Byte]): Unit = crc = Zip.crc32(crc, b, 0, b.length)
  def reset(): Unit = crc = 0
  def getValue(): Long = crc & 0xffffffffL

final class CRC32C extends Checksum:
  private var crc = 0

  def update(b: Int): Unit = update(Array(b.toByte), 0, 1)
  def update(b: Array[Byte], off: Int, len: Int): Unit =
    ZipUtils.checkRange(off, len, b.length)
    crc = Zip.crc32c(crc, b, off, len)
  override def update(b: Array[Byte]): Unit = crc = Zip.crc32c(crc, b, 0, b.length)
  def reset(): Unit = crc = 0
  def getValue(): Long = crc & 0xffffffffL

class Adler32 extends Checksum:
  private var adler = 1

  def update(b: Int): Unit = update(Array(b.toByte), 0, 1)
  def update(b: Array[Byte], off: Int, len: Int): Unit =
    ZipUtils.checkRange(off, len, b.length)
    adler = Zip.adler32(adler, b, off, len)
  override def update(b: Array[Byte]): Unit = adler = Zip.adler32(adler, b, 0, b.length)
  def reset(): Unit = adler = 1
  def getValue(): Long = adler & 0xffffffffL

class CheckedInputStream(in: java.io.InputStream, cksum: Checksum) extends java.io.FilterInputStream(in):
  override def read(): Int =
    val b = in.read()
    if b != -1 then cksum.update(b)
    b

  override def read(buf: Array[Byte], off: Int, len: Int): Int =
    val n = in.read(buf, off, len)
    if n != -1 then cksum.update(buf, off, n)
    n

  override def skip(n: Long): Long =
    val buf = new Array[Byte](512)
    var total = 0L
    var done = false
    while !done && total < n do
      val r = read(buf, 0, Math.min(n - total, buf.length.toLong).toInt)
      if r <= 0 then done = true else total += r
    total

  def getChecksum(): Checksum = cksum

class CheckedOutputStream(out: java.io.OutputStream, cksum: Checksum) extends java.io.FilterOutputStream(out):
  override def write(b: Int): Unit =
    out.write(b)
    cksum.update(b)

  override def write(b: Array[Byte], off: Int, len: Int): Unit =
    out.write(b, off, len)
    cksum.update(b, off, len)

  def getChecksum(): Checksum = cksum

class DataFormatException(message: String) extends Exception(message):
  def this() = this(null)

class ZipException(message: String) extends java.io.IOException(message):
  def this() = this(null)
