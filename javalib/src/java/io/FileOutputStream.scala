package java.io

import scalago.runtime.OS

/** Writes a file through the native layer (`scalago.runtime.OS`). Unbuffered, as in the JDK. */
class FileOutputStream(fd: FileDescriptor) extends OutputStream:
  if fd == null then throw new NullPointerException()

  def this(file: File, append: Boolean) =
    this(FileDescriptor.open(file, OS.Write | OS.Create | (if append then OS.Append else OS.Truncate)))
  def this(file: File) = this(file, false)
  def this(name: String, append: Boolean) = this(new File(name), append)
  def this(name: String) = this(new File(name), false)

  final def getFD(): FileDescriptor = fd

  override def write(b: Int): Unit =
    val buf = new Array[Byte](1)
    buf(0) = b.toByte
    OS.write(fd.openHandle(), buf, 0, 1)

  override def write(b: Array[Byte]): Unit = write(b, 0, b.length)

  override def write(b: Array[Byte], off: Int, len: Int): Unit =
    IOUtils.checkFromIndexSize(off, len, b.length)
    OS.write(fd.openHandle(), b, off, len)

  override def close(): Unit = fd.close()
