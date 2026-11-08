package java.util.zip

import java.nio.ByteBuffer

import scalago.runtime.Zip

/** Decompresses with Go's compress/flate (see `scalago.runtime.Zip`), with the JDK's API and
 *  states. It reads its input in place, never more than the compressed data, so `getRemaining()`
 *  is what follows the data, as with zlib.
 */
class Inflater(nowrap: Boolean) extends AutoCloseable:
  def this() = this(false)

  private var handle: Object = Zip.newInflater(nowrap)
  private var inputLen = 0
  /** A buffer the input was copied from: its position follows the input consumed. */
  private var inputBuffer: ByteBuffer = null
  private var inputBufferStart = 0
  // The state when the inflater was ended.
  private var endedRemaining = 0
  private var endedFinished = false
  private var endedNeedsDictionary = false

  private def ensureOpen(): Object =
    if handle == null then throw new IllegalStateException("Inflater has been closed")
    handle

  def setInput(input: Array[Byte], off: Int, len: Int): Unit =
    ZipUtils.checkRange(off, len, input.length)
    synchronized {
      if handle != null then Zip.setInput(handle, input, off, len) else endedRemaining = len
      inputLen = len
      inputBuffer = null
    }

  def setInput(input: Array[Byte]): Unit = setInput(input, 0, input.length)

  def setInput(input: ByteBuffer): Unit =
    val bytes = new Array[Byte](input.remaining())
    input.duplicate().get(bytes)
    synchronized {
      if handle != null then Zip.setInput(handle, bytes, 0, bytes.length) else endedRemaining = bytes.length
      inputLen = bytes.length
      inputBuffer = input
      inputBufferStart = input.position()
    }

  def setDictionary(dictionary: Array[Byte], off: Int, len: Int): Unit =
    ZipUtils.checkRange(off, len, dictionary.length)
    synchronized {
      if !Zip.setDictionary(ensureOpen(), dictionary, off, len) then throw new IllegalArgumentException()
    }

  def setDictionary(dictionary: Array[Byte]): Unit = setDictionary(dictionary, 0, dictionary.length)

  def setDictionary(dictionary: ByteBuffer): Unit =
    val bytes = new Array[Byte](dictionary.remaining())
    dictionary.get(bytes)
    setDictionary(bytes)

  def getRemaining(): Int = synchronized { if handle == null then endedRemaining else Zip.remaining(handle) }
  def needsInput(): Boolean = getRemaining() <= 0
  def needsDictionary(): Boolean = synchronized {
    if handle == null then endedNeedsDictionary else Zip.needsDictionary(handle)
  }
  def finished(): Boolean = synchronized { if handle == null then endedFinished else Zip.finished(handle) }

  def inflate(output: Array[Byte], off: Int, len: Int): Int =
    ZipUtils.checkRange(off, len, output.length)
    synchronized {
      val h = ensureOpen()
      val n = Zip.inflate(h, output, off, len)
      if inputBuffer != null then inputBuffer.position(inputBufferStart + inputLen - Zip.remaining(h))
      if n < 0 then throw new DataFormatException(Zip.errorMessage(h))
      n
    }

  def inflate(output: Array[Byte]): Int = inflate(output, 0, output.length)

  def inflate(output: ByteBuffer): Int =
    if output.isReadOnly() then throw new java.nio.ReadOnlyBufferException()
    val bytes = new Array[Byte](output.remaining())
    val n = inflate(bytes, 0, bytes.length)
    output.put(bytes, 0, n)
    n

  def getAdler(): Int = synchronized { Zip.adler(ensureOpen()) }
  def getTotalIn(): Int = getBytesRead().toInt
  def getBytesRead(): Long = synchronized { Zip.bytesRead(ensureOpen()) }
  def getTotalOut(): Int = getBytesWritten().toInt
  def getBytesWritten(): Long = synchronized { Zip.bytesWritten(ensureOpen()) }

  def reset(): Unit = synchronized {
    Zip.reset(ensureOpen())
    inputLen = 0
    inputBuffer = null
  }

  def end(): Unit = synchronized {
    if handle != null then
      endedRemaining = Zip.remaining(handle)
      endedFinished = Zip.finished(handle)
      endedNeedsDictionary = Zip.needsDictionary(handle)
      Zip.end(handle)
      handle = null
      inputBuffer = null
  }

  override def close(): Unit = end()
