// Adapted from Scala Native's javalib (Apache-2.0, see javalib/LICENSE-scala-native.md).

package java.io

import java.{lang => jl}

import scalago.runtime.OS

// scala-go: on the native layer's handles (scalago.runtime.OS) instead of
// file channels.
class RandomAccessFile private (fd: FileDescriptor, flush: Boolean, writable: Boolean)
    extends DataOutput
    with DataInput
    with Closeable {
  def this(file: File, mode: String) =
    this(RandomAccessFile.open(file, mode), mode == "rws" || mode == "rwd", mode != "r")
  def this(name: String, mode: String) = this(new File(name), mode)

  // Unbuffered, so that the file pointer is the stream's position.
  private val in = new DataInputStream(new FileInputStream(fd))
  private val out = new DataOutputStream(new FileOutputStream(fd))

  private var channel: java.nio.channels.FileChannel = null

  /** The channel sharing this file and its file pointer. */
  final def getChannel(): java.nio.channels.FileChannel = synchronized {
    if (channel == null) channel = new java.nio.channels.FileChannelImpl(fd, true, writable, false)
    channel
  }

  override def close(): Unit = {
    val c = synchronized(channel)
    if (c != null) c.close() else fd.close()
  }

  def getFD(): FileDescriptor =
    fd

  def getFilePointer(): Long =
    OS.seek(fd.openHandle(), 0L, 1)

  def length(): Long =
    OS.size(fd.openHandle())

  def read(): Int =
    in.read()

  def read(b: Array[Byte]): Int =
    in.read(b)

  def read(b: Array[Byte], off: Int, len: Int): Int =
    in.read(b, off, len)

  override final def readBoolean(): Boolean =
    in.readBoolean()

  override final def readByte(): Byte =
    in.readByte()

  override final def readChar(): Char =
    in.readChar()

  override final def readDouble(): Double =
    in.readDouble()

  override final def readFloat(): Float =
    in.readFloat()

  override final def readFully(b: Array[Byte]): Unit =
    in.readFully(b)

  override final def readFully(b: Array[Byte], off: Int, len: Int): Unit =
    in.readFully(b, off, len)

  override final def readInt(): Int =
    in.readInt()

  override final def readLine(): String = {
    // DataInputStream#readLine has been deprecated since JDK 1.1
    // so implement RAF#readLine, rather than delegating.
    var pos = getFilePointer()
    var end = length() // standard practice: 1 past last valid byte.
    if (pos >= end) {
      null // JDK 8 specification requires null here.
    } else {
      val builder = new jl.StringBuilder
      var done = false

      while (!done && (pos < end)) {
        val c = (readByte() & 0xff).toChar
        pos += 1

        c match {
          case '\n' => done = true

          case '\r' =>
            // If there's a newline after carriage-return, we must eat it too.
            if (pos < end) {
              if (readByte().toChar == '\n') {
                pos += 1
              } else {
                seek(getFilePointer() - 1)
              }
            }
            done = true

          case _ => builder.append(c)
        }
      }

      builder.toString
    }
  }

  override final def readLong(): Long =
    in.readLong()

  override final def readShort(): Short =
    in.readShort()

  override final def readUnsignedByte(): Int =
    in.readUnsignedByte()

  override final def readUnsignedShort(): Int =
    in.readUnsignedShort()

  override final def readUTF(): String =
    in.readUTF()

  def seek(pos: Long): Unit = {
    if (pos < 0) throw new IOException("Negative seek offset")
    OS.seek(fd.openHandle(), pos, 0)
  }

  def setLength(newLength: Long): Unit = {
    val handle = fd.openHandle()
    val pos = getFilePointer()
    OS.truncate(handle, newLength)
    if (pos > newLength) seek(newLength)
  }

  override def skipBytes(n: Int): Int =
    if (n <= 0) 0
    else {
      val currentPosition = getFilePointer()
      val fileLength = length()
      val toSkip =
        if (currentPosition + n > fileLength) fileLength - currentPosition
        else n.toLong
      seek(currentPosition + toSkip)
      toSkip.toInt
    }

  override def write(b: Array[Byte]): Unit = {
    out.write(b)
    maybeFlush()
  }

  override def write(b: Array[Byte], off: Int, len: Int): Unit = {
    out.write(b, off, len)
    maybeFlush()
  }

  override def write(b: Int): Unit = {
    out.write(b)
    maybeFlush()
  }

  override final def writeBoolean(v: Boolean): Unit = {
    out.writeBoolean(v)
    maybeFlush()
  }

  override final def writeByte(v: Int): Unit = {
    out.writeByte(v)
    maybeFlush()
  }

  override final def writeBytes(s: String): Unit = {
    out.writeBytes(s)
    maybeFlush()
  }

  override final def writeChar(v: Int): Unit = {
    out.writeChar(v)
    maybeFlush()
  }

  override final def writeChars(s: String): Unit = {
    out.writeChars(s)
    maybeFlush()
  }

  override final def writeDouble(v: Double): Unit = {
    out.writeDouble(v)
    maybeFlush()
  }

  override final def writeFloat(v: Float): Unit = {
    out.writeFloat(v)
    maybeFlush()
  }

  override final def writeInt(v: Int): Unit = {
    out.writeInt(v)
    maybeFlush()
  }

  override final def writeLong(v: Long): Unit = {
    out.writeLong(v)
    maybeFlush()
  }

  override final def writeShort(v: Int): Unit = {
    out.writeShort(v)
    maybeFlush()
  }

  override final def writeUTF(str: String): Unit = {
    out.writeUTF(str)
    maybeFlush()
  }

  private def maybeFlush(): Unit =
    if (flush) OS.sync(fd.openHandle())
}

private object RandomAccessFile {
  private def open(file: File, mode: String): FileDescriptor = {
    val flags = mode match {
      case "r"                  => OS.Read
      case "rw" | "rws" | "rwd" => OS.Read | OS.Write | OS.Create
      case _ =>
        throw new IllegalArgumentException(
          "Illegal mode \"" + mode + "\" must be one of \"r\", \"rw\", \"rws\", or \"rwd\""
        )
    }
    FileDescriptor.open(file, flags)
  }
}
