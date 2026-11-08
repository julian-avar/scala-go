package java.util.zip

import java.io.{EOFException, IOException, InputStream, PushbackInputStream}
import java.nio.charset.{Charset, StandardCharsets}

import ZipConstants.*
import ZipEntry.*

/** Reads the entries of a zip file in order, as the JDK's (the local headers, and the data
 *  descriptors of entries whose sizes follow their data; ZIP64 sizes are read).
 */
class ZipInputStream(_in: InputStream, charset: Charset)
    extends InflaterInputStream(new PushbackInputStream(ZipInputStream.checkIn(_in), 512), new Inflater(true), 512):
  if charset == null then throw new NullPointerException("charset is null")
  usesDefaultInflater = true

  private var entry: ZipEntry = null
  private var flag = 0
  private val crc = new CRC32()
  private var remaining = 0L
  private val tmpbuf = new Array[Byte](512)
  private var closed = false
  private var entryEOF = false

  def this(in: InputStream) = this(in, StandardCharsets.UTF_8)

  private def ensureOpenStream(): Unit = if closed then throw new IOException("Stream closed")

  def getNextEntry(): ZipEntry =
    ensureOpenStream()
    if entry != null then closeEntry()
    crc.reset()
    inf.reset()
    entry = readLOC()
    if entry == null then null
    else
      if entry.method == STORED then remaining = entry.size
      entryEOF = false
      entry

  def closeEntry(): Unit =
    ensureOpenStream()
    while read(tmpbuf, 0, tmpbuf.length) != -1 do ()
    entryEOF = true

  override def available(): Int =
    ensureOpenStream()
    if entryEOF then 0 else 1

  override def read(b: Array[Byte], off: Int, len: Int): Int =
    ensureOpenStream()
    if off < 0 || len < 0 || off > b.length - len then throw new IndexOutOfBoundsException()
    if len == 0 then return 0
    if entry == null then return -1
    entry.method match
      case DEFLATED =>
        val n = super.read(b, off, len)
        if n == -1 then
          readEnd(entry)
          entryEOF = true
          entry = null
        else crc.update(b, off, n)
        n
      case STORED =>
        if remaining <= 0 then
          entryEOF = true
          entry = null
          -1
        else
          val n = in.read(b, off, Math.min(len.toLong, remaining).toInt)
          if n == -1 then throw new ZipException("unexpected EOF")
          crc.update(b, off, n)
          remaining -= n
          if remaining == 0 && entry.crc != crc.getValue() then
            throw new ZipException(
              s"invalid entry CRC (expected 0x${java.lang.Long.toHexString(entry.crc)} but got 0x${java.lang.Long.toHexString(crc.getValue())})")
          n
      case _ => throw new ZipException("invalid compression method")

  override def skip(n: Long): Long =
    if n < 0 then throw new IllegalArgumentException("negative skip length")
    ensureOpenStream()
    val max = Math.min(n, Int.MaxValue).toInt
    var total = 0
    while total < max do
      val r = read(tmpbuf, 0, Math.min(max - total, tmpbuf.length))
      if r == -1 then
        entryEOF = true
        return total
      total += r
    total

  override def close(): Unit =
    if !closed then
      super.close()
      closed = true

  private def readLOC(): ZipEntry =
    try readFully(tmpbuf, 0, LOCHDR)
    catch case _: EOFException => return null
    if ZipUtils.u32(tmpbuf, 0) != LOCSIG then return null
    flag = ZipUtils.u16(tmpbuf, 6)
    val nameLen = ZipUtils.u16(tmpbuf, 26)
    val nameBytes = new Array[Byte](nameLen)
    readFully(nameBytes, 0, nameLen)
    val e = createZipEntry(new String(nameBytes, if (flag & UseUTF8) != 0 then StandardCharsets.UTF_8 else charset))
    if (flag & 1) == 1 then throw new ZipException("encrypted ZIP entry not supported")
    e.method = ZipUtils.u16(tmpbuf, 8)
    e.xdostime = ZipUtils.u32(tmpbuf, 10)
    if (flag & 8) == 8 then
      if e.method != DEFLATED then throw new ZipException("only DEFLATED entries can have EXT descriptor")
    else
      e.crc = ZipUtils.u32(tmpbuf, 14)
      e.csize = ZipUtils.u32(tmpbuf, 18)
      e.size = ZipUtils.u32(tmpbuf, 22)
    val extraLen = ZipUtils.u16(tmpbuf, 28)
    if extraLen > 0 then
      val extra = new Array[Byte](extraLen)
      readFully(extra, 0, extraLen)
      e.setExtra0(extra, e.csize == Zip64MagicVal || e.size == Zip64MagicVal, true)
    e

  protected def createZipEntry(name: String): ZipEntry = new ZipEntry(name)

  /** After a deflated entry's data: gives back the input the inflater did not use, reads the
   *  data descriptor if there is one, and checks the sizes and CRC.
   */
  private def readEnd(e: ZipEntry): Unit =
    val n = inf.getRemaining()
    if n > 0 then in.asInstanceOf[PushbackInputStream].unread(buf, len - n, n)
    if (flag & 8) == 8 then
      if inf.getBytesWritten() > Zip64MagicVal || inf.getBytesRead() > Zip64MagicVal then
        readFully(tmpbuf, 0, 24)
        val sig = ZipUtils.u32(tmpbuf, 0)
        if sig != EXTSIG then
          e.crc = sig
          e.csize = ZipUtils.u64(tmpbuf, 4)
          e.size = ZipUtils.u64(tmpbuf, 12)
          in.asInstanceOf[PushbackInputStream].unread(tmpbuf, 20, 4)
        else
          e.crc = ZipUtils.u32(tmpbuf, 4)
          e.csize = ZipUtils.u64(tmpbuf, 8)
          e.size = ZipUtils.u64(tmpbuf, 16)
      else
        readFully(tmpbuf, 0, EXTHDR)
        val sig = ZipUtils.u32(tmpbuf, 0)
        if sig != EXTSIG then
          // A descriptor without its signature.
          e.crc = sig
          e.csize = ZipUtils.u32(tmpbuf, 4)
          e.size = ZipUtils.u32(tmpbuf, 8)
          in.asInstanceOf[PushbackInputStream].unread(tmpbuf, 12, 4)
        else
          e.crc = ZipUtils.u32(tmpbuf, 4)
          e.csize = ZipUtils.u32(tmpbuf, 8)
          e.size = ZipUtils.u32(tmpbuf, 12)
    if e.size != inf.getBytesWritten() then
      throw new ZipException(s"invalid entry size (expected ${e.size} but got ${inf.getBytesWritten()} bytes)")
    if e.csize != inf.getBytesRead() then
      throw new ZipException(s"invalid entry compressed size (expected ${e.csize} but got ${inf.getBytesRead()} bytes)")
    if e.crc != crc.getValue() then
      throw new ZipException(
        s"invalid entry CRC (expected 0x${java.lang.Long.toHexString(e.crc)} but got 0x${java.lang.Long.toHexString(crc.getValue())})")

  private def readFully(b: Array[Byte], off: Int, len: Int): Unit =
    var o = off
    var l = len
    while l > 0 do
      val n = in.read(b, o, l)
      if n == -1 then throw new EOFException()
      o += n
      l -= n

object ZipInputStream:
  final val STORED = ZipEntry.STORED
  final val DEFLATED = ZipEntry.DEFLATED

  private def checkIn(in: InputStream): InputStream =
    if in == null then throw new NullPointerException("in is null")
    in
