package java.util.zip

import java.io.{EOFException, File, IOException, InputStream, RandomAccessFile}
import java.nio.charset.{Charset, StandardCharsets}

import ZipConstants.*
import ZipEntry.*

/** Reads a zip file through its central directory, as the JDK's. Entries' streams read the file
 *  independently of each other (each from its own position), so several may be open at a time.
 *  ZIP64 sizes and offsets are read; encrypted entries are not supported.
 */
class ZipFile(file: File, mode: Int, charset: Charset) extends AutoCloseable:
  if (mode & ZipFile.OPEN_READ) == 0 || (mode & ~(ZipFile.OPEN_READ | ZipFile.OPEN_DELETE)) != 0 then
    throw new IllegalArgumentException("Illegal mode: 0x" + Integer.toHexString(mode))
  if charset == null then throw new NullPointerException("charset is null")

  private val name = file.getPath()
  private var raf: RandomAccessFile =
    if !file.exists() then throw new java.nio.file.NoSuchFileException(name)
    new RandomAccessFile(file, "r")
  private val entries0 = new java.util.LinkedHashMap[String, ZipEntry]()
  private val offsets = new java.util.HashMap[String, java.lang.Long]()
  private var comment: String = null
  /** The entries' streams that are open (the program's references keep them until closed). */
  private val streams = new java.util.HashSet[InputStream]()

  try readCentralDirectory()
  catch
    case e: IOException =>
      raf.close()
      throw e
  if (mode & ZipFile.OPEN_DELETE) != 0 then file.delete()

  def this(file: File, mode: Int) = this(file, mode, StandardCharsets.UTF_8)
  def this(file: File, charset: Charset) = this(file, ZipFile.OPEN_READ, charset)
  def this(file: File) = this(file, ZipFile.OPEN_READ, StandardCharsets.UTF_8)
  def this(name: String, charset: Charset) = this(new File(name), ZipFile.OPEN_READ, charset)
  def this(name: String) = this(new File(name), ZipFile.OPEN_READ, StandardCharsets.UTF_8)

  private def ensureOpen(): Unit = if raf == null then throw new IllegalStateException("zip file closed")

  private def decode(b: Array[Byte], off: Int, len: Int, flag: Int): String =
    new String(b, off, len, if (flag & UseUTF8) != 0 then StandardCharsets.UTF_8 else charset)

  private def readCentralDirectory(): Unit =
    val fileLen = raf.length()
    if fileLen < ENDHDR then throw new ZipException("zip END header not found")
    val tailLen = Math.min(fileLen, ENDHDR + 0xffffL).toInt
    val tail = new Array[Byte](tailLen)
    raf.seek(fileLen - tailLen)
    raf.readFully(tail)
    // The end record is the last one whose comment ends the file.
    var end = tailLen - ENDHDR
    while end >= 0 && !(ZipUtils.u32(tail, end) == ENDSIG && end + ENDHDR + ZipUtils.u16(tail, end + 20) == tailLen) do
      end -= 1
    if end < 0 then throw new ZipException("zip END header not found")
    val commentLen = ZipUtils.u16(tail, end + 20)
    if commentLen > 0 then comment = decode(tail, end + ENDHDR, commentLen, 0)
    var total = ZipUtils.u16(tail, end + 10).toLong
    var cenLen = ZipUtils.u32(tail, end + 12)
    var cenOff = ZipUtils.u32(tail, end + 16)
    if cenOff == Zip64MagicVal || cenLen == Zip64MagicVal || total == 0xffff then
      // The ZIP64 end record, found through its locator just before the end record.
      val locator = end - 20
      if locator >= 0 && ZipUtils.u32(tail, locator) == 0x07064b50L then
        val endOff = ZipUtils.u64(tail, locator + 8)
        val end64 = new Array[Byte](56)
        raf.seek(endOff)
        raf.readFully(end64)
        if ZipUtils.u32(end64, 0) != 0x06064b50L then throw new ZipException("invalid zip64 END header")
        total = ZipUtils.u64(end64, 32)
        cenLen = ZipUtils.u64(end64, 40)
        cenOff = ZipUtils.u64(end64, 48)
    if cenOff + cenLen > fileLen || cenLen > Int.MaxValue then throw new ZipException("invalid END header (bad central directory offset)")
    val cen = new Array[Byte](cenLen.toInt)
    raf.seek(cenOff)
    raf.readFully(cen)
    var pos = 0
    var count = 0L
    while count < total do
      if pos + CENHDR > cen.length || ZipUtils.u32(cen, pos) != CENSIG then
        throw new ZipException("invalid CEN header (bad signature)")
      val flag = ZipUtils.u16(cen, pos + 8)
      val nameLen = ZipUtils.u16(cen, pos + 28)
      val extraLen = ZipUtils.u16(cen, pos + 30)
      val commentLen = ZipUtils.u16(cen, pos + 32)
      if pos + CENHDR + nameLen + extraLen + commentLen > cen.length then throw new ZipException("invalid CEN header (bad header size)")
      val e = new ZipEntry(decode(cen, pos + CENHDR, nameLen, flag))
      e.flag = flag
      e.method = ZipUtils.u16(cen, pos + 10)
      e.xdostime = ZipUtils.u32(cen, pos + 12)
      e.crc = ZipUtils.u32(cen, pos + 16)
      e.csize = ZipUtils.u32(cen, pos + 20)
      e.size = ZipUtils.u32(cen, pos + 24)
      var offset = ZipUtils.u32(cen, pos + 42)
      if extraLen > 0 then
        val extraStart = pos + CENHDR + nameLen
        e.setExtra0(java.util.Arrays.copyOfRange(cen, extraStart, extraStart + extraLen), false, false)
        // ZIP64: the 64-bit values of the fields that are 0xFFFFFFFF, in this order.
        var off = extraStart
        while off + 4 <= extraStart + extraLen do
          val sz = ZipUtils.u16(cen, off + 2)
          if ZipUtils.u16(cen, off) == ExtIdZip64 then
            var p = off + 4
            if e.size == Zip64MagicVal && p + 8 <= off + 4 + sz then
              e.size = ZipUtils.u64(cen, p)
              p += 8
            if e.csize == Zip64MagicVal && p + 8 <= off + 4 + sz then
              e.csize = ZipUtils.u64(cen, p)
              p += 8
            if offset == Zip64MagicVal && p + 8 <= off + 4 + sz then offset = ZipUtils.u64(cen, p)
          off += 4 + sz
      if commentLen > 0 then e.comment = decode(cen, pos + CENHDR + nameLen + extraLen, commentLen, flag)
      entries0.put(e.getName(), e)
      offsets.put(e.getName(), offset)
      pos += CENHDR + nameLen + extraLen + commentLen
      count += 1

  def getName(): String = name

  def getComment(): String =
    ensureOpen()
    comment

  def size(): Int =
    ensureOpen()
    entries0.size()

  def getEntry(name: String): ZipEntry =
    java.util.Objects.requireNonNull(name, "name")
    ensureOpen()
    var e = entries0.get(name)
    if e == null && !name.endsWith("/") then e = entries0.get(name + "/")
    if e == null then null else e.clone().asInstanceOf[ZipEntry]

  def entries(): java.util.Enumeration[? <: ZipEntry] =
    ensureOpen()
    val it = new java.util.ArrayList[ZipEntry](entries0.values()).iterator()
    new java.util.Enumeration[ZipEntry]:
      def hasMoreElements(): Boolean = synchronized { ensureOpen(); it.hasNext() }
      def nextElement(): ZipEntry = synchronized { ensureOpen(); it.next().clone().asInstanceOf[ZipEntry] }

  def stream(): java.util.stream.Stream[? <: ZipEntry] =
    ensureOpen()
    new java.util.ArrayList[ZipEntry](entries0.values()).stream().map(e => e.clone().asInstanceOf[ZipEntry])

  def getInputStream(entry: ZipEntry): InputStream =
    java.util.Objects.requireNonNull(entry, "entry")
    ensureOpen()
    val e = entries0.get(entry.getName())
    if e == null then return null
    val offset: Long = offsets.get(entry.getName())
    val loc = new Array[Byte](LOCHDR)
    synchronized {
      raf.seek(offset)
      raf.readFully(loc)
    }
    if ZipUtils.u32(loc, 0) != LOCSIG then throw new ZipException("invalid LOC header (bad signature)")
    val dataStart = offset + LOCHDR + ZipUtils.u16(loc, 26) + ZipUtils.u16(loc, 28)
    val raw = new RegionInputStream(dataStart, e.csize)
    val in: InputStream = e.method match
      case STORED   => raw
      case DEFLATED =>
        val size = e.size
        new InflaterInputStream(raw, new Inflater(true), Math.max(64, Math.min(e.csize, 8192L).toInt)):
          usesDefaultInflater = true
          override def available(): Int =
            val left = size - inf.getBytesWritten()
            if left <= 0 then super.available() else Math.min(left, Int.MaxValue).toInt
          override def close(): Unit =
            super.close()
            streams.synchronized(streams.remove(this))
      case m => throw new ZipException("invalid compression method")
    streams.synchronized(streams.add(in))
    in

  /** Closes the file and the entries' streams. */
  def close(): Unit =
    synchronized {
      if raf != null then
        val open = streams.synchronized {
          val copy = new java.util.ArrayList[InputStream](streams)
          streams.clear()
          copy
        }
        open.forEach(_.close())
        raf.close()
        raf = null
    }

  /** The bytes of the file from `start`, `length` of them. */
  private final class RegionInputStream(start: Long, length: Long) extends InputStream:
    private var pos = start
    private val end = start + length
    private var closed = false

    override def read(): Int =
      val b = new Array[Byte](1)
      if read(b, 0, 1) == -1 then -1 else b(0) & 0xff

    override def read(b: Array[Byte], off: Int, len: Int): Int =
      if closed then throw new IOException("Stream closed")
      if pos >= end then -1
      else
        val n = Math.min(len.toLong, end - pos).toInt
        val r = ZipFile.this.synchronized {
          if raf == null then throw new ZipException("ZipFile closed")
          raf.seek(pos)
          raf.read(b, off, n)
        }
        if r == -1 then throw new EOFException()
        pos += r
        r

    override def skip(n: Long): Long =
      val s = Math.max(0L, Math.min(n, end - pos))
      pos += s
      s

    override def available(): Int = Math.min(end - pos, Int.MaxValue).toInt
    override def close(): Unit =
      closed = true
      streams.synchronized(streams.remove(this))

object ZipFile:
  final val OPEN_READ = 0x1
  final val OPEN_DELETE = 0x4
  final val STORED = ZipEntry.STORED
  final val DEFLATED = ZipEntry.DEFLATED
