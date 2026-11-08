package java.util.zip

import java.nio.ByteBuffer

import scalago.runtime.Zip

/** Compresses with Go's compress/flate (see `scalago.runtime.Zip`). The API and its states are
 *  the JDK's; the compressed bytes are valid deflate (and zlib) data, but not those zlib writes.
 *  Each `deflate` call consumes all the input set; `needsInput()` is true once the output is
 *  delivered too, as zlib's input is consumed when there is room for its output.
 */
class Deflater(level: Int, nowrap: Boolean) extends AutoCloseable:
  import Deflater.*

  if (level < 0 || level > 9) && level != DEFAULT_COMPRESSION then throw new IllegalArgumentException()

  def this(level: Int) = this(level, false)
  def this() = this(Deflater.DEFAULT_COMPRESSION, false)

  private var handle: Object = Zip.newDeflater(nowrap)
  private var currentLevel = level
  private var strategy = DEFAULT_STRATEGY
  private var input: Array[Byte] = Deflater.Empty
  private var inputOff = 0
  private var inputLen = 0
  /** A buffer the input was copied from: its position moves when the input is consumed. */
  private var inputBuffer: ByteBuffer = null
  private var finishing = false
  /** Whether the stream was finished when the deflater was ended. */
  private var endedFinished = false

  private def ensureOpen(): Object =
    if handle == null then throw new IllegalStateException("Deflater has been closed")
    handle

  def setInput(input: Array[Byte], off: Int, len: Int): Unit =
    ZipUtils.checkRange(off, len, input.length)
    synchronized {
      this.input = input
      inputOff = off
      inputLen = len
      inputBuffer = null
    }

  def setInput(input: Array[Byte]): Unit = setInput(input, 0, input.length)

  def setInput(input: ByteBuffer): Unit =
    val bytes = new Array[Byte](input.remaining())
    input.duplicate().get(bytes)
    synchronized {
      this.input = bytes
      inputOff = 0
      inputLen = bytes.length
      inputBuffer = input
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

  def setStrategy(strategy: Int): Unit =
    if strategy != DEFAULT_STRATEGY && strategy != FILTERED && strategy != HUFFMAN_ONLY then
      throw new IllegalArgumentException()
    synchronized { this.strategy = strategy }

  def setLevel(level: Int): Unit =
    checkLevel(level)
    synchronized { currentLevel = level }

  def needsInput(): Boolean = synchronized { inputLen <= 0 && (handle == null || Zip.pending(handle) == 0) }

  def finish(): Unit = synchronized { finishing = true }

  def finished(): Boolean = synchronized { if handle == null then endedFinished else Zip.finished(handle) }

  def deflate(output: Array[Byte], off: Int, len: Int): Int = deflate(output, off, len, NO_FLUSH)
  def deflate(output: Array[Byte]): Int = deflate(output, 0, output.length, NO_FLUSH)

  def deflate(output: Array[Byte], off: Int, len: Int, flush: Int): Int =
    ZipUtils.checkRange(off, len, output.length)
    if flush != NO_FLUSH && flush != SYNC_FLUSH && flush != FULL_FLUSH then throw new IllegalArgumentException()
    synchronized {
      val h = ensureOpen()
      val n = Zip.deflate(h, input, inputOff, inputLen, output, off, len, currentLevel, strategy,
        if finishing then Zip.Finish else flush)
      if inputBuffer != null then inputBuffer.position(inputBuffer.position() + inputLen)
      inputOff += inputLen
      inputLen = 0
      n
    }

  def deflate(output: ByteBuffer): Int = deflate(output, NO_FLUSH)

  def deflate(output: ByteBuffer, flush: Int): Int =
    if output.isReadOnly() then throw new java.nio.ReadOnlyBufferException()
    val bytes = new Array[Byte](output.remaining())
    val n = deflate(bytes, 0, bytes.length, flush)
    output.put(bytes, 0, n)
    n

  def getAdler(): Int = synchronized { Zip.adler(ensureOpen()) }
  def getTotalIn(): Int = getBytesRead().toInt
  def getBytesRead(): Long = synchronized { Zip.bytesRead(ensureOpen()) }
  def getTotalOut(): Int = getBytesWritten().toInt
  def getBytesWritten(): Long = synchronized { Zip.bytesWritten(ensureOpen()) }

  def reset(): Unit = synchronized {
    Zip.reset(ensureOpen())
    finishing = false
    input = Deflater.Empty
    inputOff = 0
    inputLen = 0
    inputBuffer = null
  }

  def end(): Unit = synchronized {
    if handle != null then
      endedFinished = Zip.finished(handle)
      Zip.end(handle)
      handle = null
      input = Deflater.Empty
      inputOff = 0
      inputLen = 0
      inputBuffer = null
  }

  override def close(): Unit = end()

object Deflater:
  final val DEFLATED = 8
  final val NO_COMPRESSION = 0
  final val BEST_SPEED = 1
  final val BEST_COMPRESSION = 9
  final val DEFAULT_COMPRESSION = -1
  final val FILTERED = 1
  final val HUFFMAN_ONLY = 2
  final val DEFAULT_STRATEGY = 0
  final val NO_FLUSH = 0
  final val SYNC_FLUSH = 2
  final val FULL_FLUSH = 3

  private val Empty = new Array[Byte](0)

  private def checkLevel(level: Int): Unit =
    if (level < 0 || level > 9) && level != DEFAULT_COMPRESSION then
      throw new IllegalArgumentException("invalid compression level")
