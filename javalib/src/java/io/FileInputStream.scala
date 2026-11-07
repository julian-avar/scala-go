package java.io

import scalago.runtime.OS

/** Reads a file through the native layer (`scalago.runtime.OS`). Unbuffered, as in the JDK. */
class FileInputStream(fd: FileDescriptor) extends InputStream:
  if fd == null then throw new NullPointerException()

  def this(file: File) = this(FileDescriptor.open(file, OS.Read))
  def this(name: String) = this(new File(name))

  final def getFD(): FileDescriptor = fd

  override def read(): Int =
    val b = new Array[Byte](1)
    if OS.read(fd.openHandle(), b, 0, 1) == -1 then -1 else b(0) & 0xff

  override def read(b: Array[Byte]): Int = read(b, 0, b.length)

  override def read(b: Array[Byte], off: Int, len: Int): Int =
    IOUtils.checkFromIndexSize(off, len, b.length)
    if len == 0 then 0 else OS.read(fd.openHandle(), b, off, len)

  /** Seeks forward (possibly past the end, as the JDK); streams that cannot seek read instead. */
  override def skip(n: Long): Long =
    val handle = fd.openHandle()
    if n <= 0 then 0L
    else
      try
        OS.seek(handle, n, 1)
        n
      catch case _: IOException => super.skip(n)

  override def available(): Int = OS.available(fd.openHandle())

  private var channel: java.nio.channels.FileChannel = null

  /** The channel sharing this stream's file and position. */
  def getChannel(): java.nio.channels.FileChannel = synchronized {
    if channel == null then channel = new java.nio.channels.FileChannelImpl(fd, true, false, false)
    channel
  }

  override def close(): Unit =
    val c = synchronized(channel)
    if c != null then c.close() else fd.close()
