package java.io

/** java.io.PipedInputStream, as the JDK's: a circular buffer filled by the connected
 *  PipedOutputStream's thread. A reader waiting for data notices a writer thread that ended
 *  without closing the pipe ("Write end dead", "Pipe broken"), and a writer waiting for space a
 *  reader thread that ended ("Read end dead"), by checking every second.
 */
class PipedInputStream(pipeSize: Int) extends InputStream:
  if pipeSize <= 0 then throw new IllegalArgumentException("Pipe Size <= 0")

  protected var buffer: Array[Byte] = new Array[Byte](pipeSize)
  /** Where the next byte received goes; -1 when the buffer is empty. */
  protected var in: Int = -1
  /** Where the next byte read comes from. */
  protected var out: Int = 0

  @volatile private var closedByWriter = false
  @volatile private var closedByReader = false
  private[io] var connected = false
  private var readSide: Thread = null
  private var writeSide: Thread = null

  def this() = this(PipedInputStream.PIPE_SIZE)

  def this(src: PipedOutputStream, pipeSize: Int) =
    this(pipeSize)
    connect(src)

  def this(src: PipedOutputStream) = this(src, PipedInputStream.PIPE_SIZE)

  def connect(src: PipedOutputStream): Unit = src.connect(this)

  private def checkStateForReceive(): Unit =
    if !connected then throw new IOException("Pipe not connected")
    else if closedByWriter || closedByReader then throw new IOException("Pipe closed")
    else if readSide != null && !readSide.isAlive() then throw new IOException("Read end dead")

  /** Waits for space in the buffer (the writer's side). */
  private def awaitSpace(): Unit =
    while in == out do
      checkStateForReceive()
      notifyAll()
      try wait(1000)
      catch case _: InterruptedException => throw new InterruptedIOException()

  protected def receive(b: Int): Unit = synchronized {
    checkStateForReceive()
    writeSide = Thread.currentThread()
    if in == out then awaitSpace()
    if in < 0 then
      in = 0
      out = 0
    buffer(in) = b.toByte
    in += 1
    if in >= buffer.length then in = 0
  }

  private[io] def receiveOne(b: Int): Unit = receive(b)

  private[io] def receive(b: Array[Byte], off: Int, len: Int): Unit = synchronized {
    checkStateForReceive()
    writeSide = Thread.currentThread()
    var offset = off
    var remaining = len
    while remaining > 0 do
      if in == out then awaitSpace()
      if in < 0 then
        in = 0
        out = 0
      // The free space up to the end of the buffer, or up to the unread bytes.
      val space = if in >= out then buffer.length - in else out - in
      val n = Math.min(space, remaining)
      System.arraycopy(b, offset, buffer, in, n)
      remaining -= n
      offset += n
      in += n
      if in >= buffer.length then in = 0
  }

  /** The writer closed its end. */
  private[io] def receivedLast(): Unit = synchronized {
    closedByWriter = true
    notifyAll()
  }

  /** Waits for data (the reader's side): false at the end of the stream. */
  private def awaitData(): Boolean =
    if !connected then throw new IOException("Pipe not connected")
    else if closedByReader then throw new IOException("Pipe closed")
    else if writeSide != null && !writeSide.isAlive() && !closedByWriter && in < 0 then
      throw new IOException("Write end dead")
    readSide = Thread.currentThread()
    var trials = 2
    while in < 0 do
      if closedByWriter then return false
      if writeSide != null && !writeSide.isAlive() then
        trials -= 1
        if trials < 0 then throw new IOException("Pipe broken")
      notifyAll()
      try wait(1000)
      catch case _: InterruptedException => throw new InterruptedIOException()
    true

  override def read(): Int = synchronized {
    if !awaitData() then -1
    else
      val b = buffer(out) & 0xff
      out += 1
      if out >= buffer.length then out = 0
      if in == out then in = -1
      b
  }

  override def read(b: Array[Byte], off: Int, len: Int): Int = synchronized {
    if b == null then throw new NullPointerException()
    IOUtils.checkFromIndexSize(off, len, b.length)
    if len == 0 then 0
    else if !awaitData() then -1
    else
      var n = 0
      while in >= 0 && n < len do
        val available = if in > out then in - out else buffer.length - out
        val count = Math.min(available, len - n)
        System.arraycopy(buffer, out, b, off + n, count)
        out += count
        n += count
        if out >= buffer.length then out = 0
        if in == out then in = -1
      n
  }

  override def available(): Int = synchronized {
    if in < 0 then 0
    else if in == out then buffer.length
    else if in > out then in - out
    else in + buffer.length - out
  }

  override def close(): Unit = synchronized {
    closedByReader = true
    in = -1
  }

object PipedInputStream:
  final val PIPE_SIZE = 1024

/** java.io.PipedOutputStream: writes go to the connected PipedInputStream's buffer. */
class PipedOutputStream() extends OutputStream:
  private var sink: PipedInputStream = null

  def this(snk: PipedInputStream) =
    this()
    connect(snk)

  def connect(snk: PipedInputStream): Unit = synchronized {
    if snk == null then throw new NullPointerException()
    else if sink != null || snk.connected then throw new IOException("Already connected")
    sink = snk
    snk.connected = true
  }

  private def connectedSink(): PipedInputStream =
    val s = sink
    if s == null then throw new IOException("Pipe not connected")
    s

  override def write(b: Int): Unit =
    connectedSink().receiveOne(b)

  override def write(b: Array[Byte], off: Int, len: Int): Unit =
    val s = connectedSink()
    if b == null then throw new NullPointerException()
    IOUtils.checkFromIndexSize(off, len, b.length)
    if len > 0 then s.receive(b, off, len)

  override def flush(): Unit =
    val s = sink
    if s != null then s.synchronized { s.notifyAll() }

  override def close(): Unit =
    val s = sink
    if s != null then s.receivedLast()
