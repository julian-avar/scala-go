package java.util.zip

import java.io.{EOFException, FilterInputStream, FilterOutputStream, IOException, InputStream, OutputStream}

/** Decompresses what it reads from `in`, as the JDK's. */
class InflaterInputStream(_in: InputStream, protected var inf: Inflater, size: Int) extends FilterInputStream(_in):
  if _in == null || inf == null then throw new NullPointerException()
  if size <= 0 then throw new IllegalArgumentException("buffer size <= 0")

  protected var buf: Array[Byte] = new Array[Byte](size)
  protected var len: Int = 0
  private var closed = false
  private var reachEOF = false
  private[zip] var usesDefaultInflater = false

  def this(in: InputStream, inf: Inflater) = this(in, inf, 512)
  def this(in: InputStream) =
    this(in, if in == null then null else new Inflater(), 512)
    usesDefaultInflater = true

  private[zip] def ensureOpen(): Unit = if closed then throw new IOException("Stream closed")

  override def read(): Int =
    val b = new Array[Byte](1)
    if read(b, 0, 1) == -1 then -1 else b(0) & 0xff

  override def read(b: Array[Byte], off: Int, len: Int): Int =
    ensureOpen()
    if b == null then throw new NullPointerException()
    if off < 0 || len < 0 || len > b.length - off then throw new IndexOutOfBoundsException()
    if len == 0 then return 0
    try
      var n = 0
      while n == 0 do
        n = inf.inflate(b, off, len)
        if n == 0 then
          if inf.finished() || inf.needsDictionary() then
            reachEOF = true
            return -1
          if inf.needsInput() then fill()
      n
    catch
      case e: DataFormatException =>
        val s = e.getMessage
        throw new ZipException(if s != null then s else "Invalid ZLIB data format")

  override def available(): Int =
    ensureOpen()
    if reachEOF then 0
    else if inf.finished() then
      reachEOF = true
      0
    else 1

  override def skip(n: Long): Long =
    if n < 0 then throw new IllegalArgumentException("negative skip length")
    ensureOpen()
    val max = Math.min(n, Int.MaxValue).toInt
    var total = 0
    val b = new Array[Byte](512)
    while total < max do
      val r = read(b, 0, Math.min(max - total, b.length))
      if r == -1 then return total
      total += r
    total

  override def close(): Unit =
    if !closed then
      if usesDefaultInflater then inf.end()
      in.close()
      closed = true

  protected def fill(): Unit =
    ensureOpen()
    len = in.read(buf, 0, buf.length)
    if len == -1 then throw new EOFException("Unexpected end of ZLIB input stream")
    inf.setInput(buf, 0, len)

  override def markSupported(): Boolean = false
  override def mark(readlimit: Int): Unit = ()
  override def reset(): Unit = throw new IOException("mark/reset not supported")

/** Compresses what is written to it, as the JDK's. */
class DeflaterOutputStream(_out: OutputStream, protected var `def`: Deflater, size: Int, syncFlush: Boolean)
    extends FilterOutputStream(_out):
  if _out == null || `def` == null then throw new NullPointerException()
  if size <= 0 then throw new IllegalArgumentException("buffer size <= 0")

  protected var buf: Array[Byte] = new Array[Byte](size)
  private var closed = false
  private[zip] var usesDefaultDeflater = false

  def this(out: OutputStream, `def`: Deflater, size: Int) = this(out, `def`, size, false)
  def this(out: OutputStream, `def`: Deflater, syncFlush: Boolean) = this(out, `def`, 512, syncFlush)
  def this(out: OutputStream, `def`: Deflater) = this(out, `def`, 512, false)
  def this(out: OutputStream, syncFlush: Boolean) =
    this(out, new Deflater(), 512, syncFlush)
    usesDefaultDeflater = true
  def this(out: OutputStream) = this(out, false)

  override def write(b: Int): Unit =
    write(Array(b.toByte), 0, 1)

  override def write(b: Array[Byte], off: Int, len: Int): Unit =
    if `def`.finished() then throw new IOException("write beyond end of stream")
    if (off | len | (off + len) | (b.length - (off + len))) < 0 then throw new IndexOutOfBoundsException()
    if len > 0 && !`def`.finished() then
      `def`.setInput(b, off, len)
      while !`def`.needsInput() do deflate()

  def finish(): Unit =
    if !`def`.finished() then
      `def`.finish()
      while !`def`.finished() do deflate()

  override def close(): Unit =
    if !closed then
      closed = true
      try finish()
      finally if usesDefaultDeflater then `def`.end()
      out.close()

  protected def deflate(): Unit =
    val n = `def`.deflate(buf, 0, buf.length)
    if n > 0 then out.write(buf, 0, n)

  override def flush(): Unit =
    if syncFlush && !`def`.finished() then
      var n = `def`.deflate(buf, 0, buf.length, Deflater.SYNC_FLUSH)
      while n > 0 do
        out.write(buf, 0, n)
        n = if n < buf.length then 0 else `def`.deflate(buf, 0, buf.length, Deflater.SYNC_FLUSH)
    out.flush()

/** Decompresses what is written to it, as the JDK's. */
class InflaterOutputStream(_out: OutputStream, protected val inf: Inflater, bufLen: Int) extends FilterOutputStream(_out):
  if _out == null then throw new NullPointerException("Null output")
  if inf == null then throw new NullPointerException("Null inflater")
  if bufLen <= 0 then throw new IllegalArgumentException("Buffer size < 1")

  protected val buf: Array[Byte] = new Array[Byte](bufLen)
  private var closed = false
  private var usesDefaultInflater = false

  def this(out: OutputStream, inf: Inflater) = this(out, inf, 512)
  def this(out: OutputStream) =
    this(out, new Inflater(), 512)
    usesDefaultInflater = true

  private def ensureOpen(): Unit = if closed then throw new IOException("Stream closed")

  override def close(): Unit =
    if !closed then
      try finish()
      finally
        out.close()
        closed = true

  override def flush(): Unit =
    ensureOpen()
    if !inf.finished() then
      try
        var done = false
        while !done && !inf.finished() && !inf.needsInput() do
          val n = inf.inflate(buf, 0, buf.length)
          if n < 1 then done = true else out.write(buf, 0, n)
        super.flush()
      catch
        case e: DataFormatException =>
          val s = e.getMessage
          throw new ZipException(if s != null then s else "Invalid ZLIB data format")

  def finish(): Unit =
    ensureOpen()
    flush()
    if usesDefaultInflater then inf.end()

  override def write(b: Int): Unit = write(Array(b.toByte), 0, 1)

  override def write(b: Array[Byte], off: Int, len: Int): Unit =
    ensureOpen()
    if b == null then throw new NullPointerException("Null buffer for read")
    if off < 0 || len < 0 || len > b.length - off then throw new IndexOutOfBoundsException()
    var o = off
    var l = len
    try
      var done = false
      while !done do
        var noInput = false
        if inf.needsInput() then
          if l < 1 then noInput = true
          else
            val part = Math.min(l, 512)
            inf.setInput(b, o, part)
            o += part
            l -= part
        if noInput then done = true
        else
          var more = true
          while more do
            val n = inf.inflate(buf, 0, buf.length)
            if n < 1 then more = false
            else
              out.write(buf, 0, n)
              more = !inf.finished()
          if inf.finished() then done = true
          else if inf.needsDictionary() then throw new ZipException("ZLIB dictionary missing")
    catch
      case e: DataFormatException =>
        val s = e.getMessage
        throw new ZipException(if s != null then s else "Invalid ZLIB data format")

/** Compresses what it reads from `in`, as the JDK's. */
class DeflaterInputStream(_in: InputStream, protected val `def`: Deflater, bufLen: Int) extends FilterInputStream(_in):
  if _in == null then throw new NullPointerException("Null input")
  if `def` == null then throw new NullPointerException("Null deflater")
  if bufLen < 1 then throw new IllegalArgumentException("Buffer size < 1")

  protected val buf: Array[Byte] = new Array[Byte](bufLen)
  private var reachEOF = false
  private var closed = false
  private var usesDefaultDeflater = false

  def this(in: InputStream, `def`: Deflater) = this(in, `def`, 512)
  def this(in: InputStream) =
    this(in, new Deflater(), 512)
    usesDefaultDeflater = true

  private def ensureOpen(): Unit = if closed then throw new IOException("Stream closed")

  override def close(): Unit =
    if !closed then
      closed = true
      if usesDefaultDeflater then `def`.end()
      in.close()

  override def read(): Int =
    val b = new Array[Byte](1)
    if read(b, 0, 1) <= 0 then -1 else b(0) & 0xff

  override def read(b: Array[Byte], off: Int, len: Int): Int =
    ensureOpen()
    if b == null then throw new NullPointerException("Null buffer for read")
    if off < 0 || len < 0 || len > b.length - off then throw new IndexOutOfBoundsException()
    if len == 0 then return 0
    var count = 0
    var o = off
    var l = len
    while l > 0 && !`def`.finished() do
      if `def`.needsInput() then
        val n = in.read(buf, 0, buf.length)
        if n < 0 then `def`.finish()
        else if n > 0 then `def`.setInput(buf, 0, n)
      val n = `def`.deflate(b, o, l)
      count += n
      o += n
      l -= n
    if count == 0 && `def`.finished() then
      reachEOF = true
      -1
    else count

  override def skip(n: Long): Long =
    if n < 0 then throw new IllegalArgumentException("negative skip length")
    ensureOpen()
    val b = new Array[Byte](Math.min(n, 512L).toInt)
    var total = 0L
    while total < n do
      val r = read(b, 0, Math.min(n - total, b.length.toLong).toInt)
      if r < 0 then return total
      total += r
    total

  override def available(): Int =
    ensureOpen()
    if reachEOF then 0 else 1

  override def markSupported(): Boolean = false
  override def mark(limit: Int): Unit = ()
  override def reset(): Unit = throw new IOException("mark/reset not supported")
