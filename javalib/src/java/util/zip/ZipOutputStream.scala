package java.util.zip

import java.io.{IOException, OutputStream}
import java.nio.charset.{Charset, StandardCharsets}
import java.nio.file.attribute.FileTime

import ZipConstants.*
import ZipEntry.*

/** Writes a zip file, as the JDK's: deflated entries have data descriptors unless their sizes and
 *  CRC are set, names and comments are UTF-8 (flagged so) by default, and times beyond DOS times
 *  are written as Info-ZIP extended timestamps. Archives beyond the 4 GB and 65,535 entries of
 *  the original format (ZIP64) are not supported.
 */
class ZipOutputStream(_out: OutputStream, charset: Charset)
    extends DeflaterOutputStream(ZipOutputStream.checkOut(_out), new Deflater(Deflater.DEFAULT_COMPRESSION, true)):
  if charset == null then throw new NullPointerException("charset is null")
  usesDefaultDeflater = true

  private final class XEntry(val entry: ZipEntry, val offset: Long)

  private val xentries = new java.util.ArrayList[XEntry]()
  private val names = new java.util.HashSet[String]()
  private val crc = new CRC32()
  private var current: XEntry = null
  private var written = 0L
  private var locoff = 0L
  private var comment: Array[Byte] = null
  private var method = DEFLATED
  private var finished = false
  private var closed = false
  private val isUTF8 = charset == StandardCharsets.UTF_8

  def this(out: OutputStream) = this(out, StandardCharsets.UTF_8)

  private def ensureOpen(): Unit = if closed then throw new IOException("Stream closed")

  private def bytes(s: String): Array[Byte] = s.getBytes(charset)

  def setComment(comment: String): Unit =
    if comment != null then
      this.comment = bytes(comment)
      if this.comment.length > 0xffff then throw new IllegalArgumentException("ZIP file comment too long.")

  def setMethod(method: Int): Unit =
    if method != DEFLATED && method != STORED then throw new IllegalArgumentException("invalid compression method")
    this.method = method

  def setLevel(level: Int): Unit = `def`.setLevel(level)

  def putNextEntry(e: ZipEntry): Unit =
    ensureOpen()
    if current != null then closeEntry()
    if e.xdostime == -1 then e.setTime(System.currentTimeMillis())
    if e.method == -1 then e.method = method
    e.flag = 0
    e.method match
      case DEFLATED =>
        if e.size == -1 || e.csize == -1 || e.crc == -1 || !e.csizeSet then e.flag = 8
      case STORED =>
        if e.size == -1 then e.size = e.csize
        else if e.csize == -1 then e.csize = e.size
        else if e.size != e.csize then throw new ZipException("STORED entry where compressed != uncompressed size")
        if e.size == -1 || e.crc == -1 then throw new ZipException("STORED entry missing size, compressed size, or crc-32")
      case _ => throw new ZipException("unsupported compression method")
    if !names.add(e.getName()) then throw new ZipException("duplicate entry: " + e.getName())
    if isUTF8 then e.flag |= UseUTF8
    current = new XEntry(e, written)
    xentries.add(current)
    writeLOC(current)

  def closeEntry(): Unit =
    ensureOpen()
    if current != null then
      val e = current.entry
      e.method match
        case DEFLATED =>
          `def`.finish()
          while !`def`.finished() do deflate()
          if (e.flag & 8) == 0 then
            if e.size != `def`.getBytesRead() then
              throw new ZipException(s"invalid entry size (expected ${e.size} but got ${`def`.getBytesRead()} bytes)")
            if e.csize != `def`.getBytesWritten() then
              throw new ZipException(
                s"invalid entry compressed size (expected ${e.csize} but got ${`def`.getBytesWritten()} bytes)")
            if e.crc != crc.getValue() then
              throw new ZipException(
                s"invalid entry CRC-32 (expected 0x${java.lang.Long.toHexString(e.crc)} but got 0x${java.lang.Long.toHexString(crc.getValue())})")
          else
            e.size = `def`.getBytesRead()
            e.csize = `def`.getBytesWritten()
            e.crc = crc.getValue()
            writeEXT(e)
          `def`.reset()
          written += e.csize
        case _ =>
          if e.size != written - locoff then
            throw new ZipException(s"invalid entry size (expected ${e.size} but got ${written - locoff} bytes)")
          if e.crc != crc.getValue() then
            throw new ZipException(
              s"invalid entry crc-32 (expected 0x${java.lang.Long.toHexString(e.crc)} but got 0x${java.lang.Long.toHexString(crc.getValue())})")
      crc.reset()
      current = null

  override def write(b: Array[Byte], off: Int, len: Int): Unit =
    ensureOpen()
    if off < 0 || len < 0 || off > b.length - len then throw new IndexOutOfBoundsException()
    if len > 0 then
      if current == null then throw new ZipException("no current ZIP entry")
      val entry = current.entry
      entry.method match
        case DEFLATED => super.write(b, off, len)
        case _ =>
          written += len
          if written - locoff > entry.size then throw new ZipException("attempt to write past end of STORED entry")
          out.write(b, off, len)
      crc.update(b, off, len)

  override def finish(): Unit =
    ensureOpen()
    if !finished then
      if current != null then closeEntry()
      val off = written
      xentries.forEach(writeCEN)
      writeEND(off, written - off)
      finished = true

  override def close(): Unit =
    if !closed then
      super.close()
      closed = true

  // ---- Headers --------------------------------------------------------------------------------

  private def version(e: ZipEntry): Int = if e.method == DEFLATED then 20 else 10

  private def writeShort(v: Int): Unit =
    out.write(v & 0xff)
    out.write((v >>> 8) & 0xff)
    written += 2

  private def writeInt(v: Long): Unit =
    writeShort(v.toInt & 0xffff)
    writeShort((v >>> 16).toInt & 0xffff)

  private def writeByte(v: Int): Unit =
    out.write(v & 0xff)
    written += 1

  private def writeBytes(b: Array[Byte], off: Int, len: Int): Unit =
    out.write(b, off, len)
    written += len

  private def unixTime(t: FileTime): Long = Math.floorDiv(t.toMillis(), 1000L)
  private def windowsTime(t: FileTime): Long =
    if t == null then Long.MinValue else (t.toMillis() - WindowsEpochMillis) * 10000L

  /** The extended timestamp flags (modification, access, creation time). */
  private def extTimeFlags(e: ZipEntry): Int =
    (if e.mtime != null then 1 else 0) | (if e.atime != null then 2 else 0) | (if e.ctime != null then 4 else 0)

  /** Whether the times need the NTFS field, which goes beyond 2038, rather than the extended timestamp. */
  private def ntfsTimes(e: ZipEntry): Boolean =
    List(e.mtime, e.atime, e.ctime).exists(t => t != null && unixTime(t) > Int.MaxValue)

  /** The entry's extra data without the time fields, which this stream writes itself. */
  private def extraWithoutTimes(e: ZipEntry): Array[Byte] =
    val extra = e.extra
    if extra == null then null
    else
      val b = new java.io.ByteArrayOutputStream()
      var off = 0
      while off + 4 <= extra.length do
        val tag = ZipUtils.u16(extra, off)
        val sz = ZipUtils.u16(extra, off + 2)
        if off + 4 + sz > extra.length then
          b.write(extra, off, extra.length - off)
          off = extra.length
        else
          if tag != ExtIdExtT && tag != ExtIdNTFS then b.write(extra, off, 4 + sz)
          off += 4 + sz
      if off < extra.length then b.write(extra, off, extra.length - off)
      b.toByteArray()

  private def writeNTFS(e: ZipEntry): Unit =
    writeShort(ExtIdNTFS)
    writeShort(32)
    writeInt(0) // reserved
    writeShort(0x0001) // the times' attribute
    writeShort(24)
    for t <- List(e.mtime, e.atime, e.ctime) do
      val w = windowsTime(t)
      writeInt(w)
      writeInt(w >>> 32)

  /** The length of the time fields, and writes them if `write`; `cen` has the modification time only. */
  private def timeFields(e: ZipEntry, cen: Boolean, write: Boolean): Int =
    val flags = extTimeFlags(e)
    if flags == 0 then 0
    else if ntfsTimes(e) then
      if write then writeNTFS(e)
      36
    else
      val times = if cen then List(e.mtime).filter(_ != null) else List(e.mtime, e.atime, e.ctime).filter(_ != null)
      if write then
        writeShort(ExtIdExtT)
        writeShort(1 + 4 * times.length)
        writeByte(flags)
        times.foreach(t => writeInt(unixTime(t)))
      5 + 4 * times.length

  private def writeLOC(xentry: XEntry): Unit =
    val e = xentry.entry
    writeInt(LOCSIG)
    writeShort(version(e))
    writeShort(e.flag)
    writeShort(e.method)
    writeInt(e.xdostime)
    if (e.flag & 8) == 8 then
      writeInt(0)
      writeInt(0)
      writeInt(0)
    else
      writeInt(e.crc)
      writeInt(e.csize)
      writeInt(e.size)
    val nameBytes = bytes(e.getName())
    writeShort(nameBytes.length)
    val extra = extraWithoutTimes(e)
    writeShort((if extra == null then 0 else extra.length) + timeFields(e, false, false))
    writeBytes(nameBytes, 0, nameBytes.length)
    if extra != null then writeBytes(extra, 0, extra.length)
    timeFields(e, false, true)
    locoff = written

  private def writeEXT(e: ZipEntry): Unit =
    writeInt(EXTSIG)
    writeInt(e.crc)
    writeInt(e.csize)
    writeInt(e.size)

  private def writeCEN(xentry: XEntry): Unit =
    val e = xentry.entry
    writeInt(CENSIG)
    writeShort(version(e))
    writeShort(version(e))
    writeShort(e.flag)
    writeShort(e.method)
    writeInt(e.xdostime)
    writeInt(e.crc)
    writeInt(e.csize)
    writeInt(e.size)
    val nameBytes = bytes(e.getName())
    writeShort(nameBytes.length)
    val extra = extraWithoutTimes(e)
    writeShort((if extra == null then 0 else extra.length) + timeFields(e, true, false))
    val commentBytes = if e.comment != null then bytes(e.comment) else null
    writeShort(if commentBytes == null then 0 else Math.min(commentBytes.length, 0xffff))
    writeShort(0) // starting disk number
    writeShort(0) // internal file attributes
    writeInt(0) // external file attributes
    writeInt(xentry.offset)
    writeBytes(nameBytes, 0, nameBytes.length)
    if extra != null then writeBytes(extra, 0, extra.length)
    timeFields(e, true, true)
    if commentBytes != null then writeBytes(commentBytes, 0, Math.min(commentBytes.length, 0xffff))

  private def writeEND(off: Long, len: Long): Unit =
    val count = xentries.size()
    if count > 0xffff || off > Zip64MagicVal || len > Zip64MagicVal then
      throw new ZipException("ZIP64 archives are not supported")
    writeInt(ENDSIG)
    writeShort(0)
    writeShort(0)
    writeShort(count)
    writeShort(count)
    writeInt(len)
    writeInt(off)
    if comment != null then
      writeShort(comment.length)
      writeBytes(comment, 0, comment.length)
    else writeShort(0)

object ZipOutputStream:
  final val STORED = ZipEntry.STORED
  final val DEFLATED = ZipEntry.DEFLATED

  private def checkOut(out: OutputStream): OutputStream =
    if out == null then throw new NullPointerException("out is null")
    out
