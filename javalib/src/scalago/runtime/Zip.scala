package scalago.runtime

/** The native side of java.util.zip (see runtime/rt/zip.go): checksums, and deflaters and
 *  inflaters on Go's compress/flate. Both take their input and give their output in pieces, as
 *  zlib's do; the zlib format's header and trailer are handled natively, gzip and zip in Scala.
 */
object Zip:
  private def native: Nothing = throw new UnsupportedOperationException("scala-go native")

  // Flush modes (zlib's, as java.util.zip.Deflater numbers them).
  final val NoFlush = 0
  final val SyncFlush = 2
  final val FullFlush = 3
  final val Finish = 4

  def crc32(crc: Int, b: Array[Byte], off: Int, len: Int): Int = native
  def crc32c(crc: Int, b: Array[Byte], off: Int, len: Int): Int = native
  def adler32(adler: Int, b: Array[Byte], off: Int, len: Int): Int = native

  /** A deflater, writing the zlib format, or raw deflate data if `nowrap`. */
  def newDeflater(nowrap: Boolean): Object = native
  /** Compresses all of the input with the level and strategy, flushes or finishes the stream as
   *  `flush` says, and moves output into `out`: the number of bytes written there.
   */
  def deflate(deflater: Object, in: Array[Byte], inOff: Int, inLen: Int, out: Array[Byte], outOff: Int, outLen: Int,
      level: Int, strategy: Int, flush: Int): Int = native

  /** The number of compressed bytes the deflater has not delivered yet. */
  def pending(deflater: Object): Int = native

  /** An inflater, reading the zlib format, or raw deflate data if `nowrap`. */
  def newInflater(nowrap: Boolean): Object = native
  /** Replaces the inflater's input (which it reads in place until it has consumed it). */
  def setInput(inflater: Object, b: Array[Byte], off: Int, len: Int): Unit = native
  /** The input not consumed yet. */
  def remaining(inflater: Object): Int = native
  /** Decompresses into `b`: the number of bytes written, 0 if more input or a dictionary is
   *  needed or the stream is finished, -1 if the data is corrupt ([[errorMessage]]).
   */
  def inflate(inflater: Object, b: Array[Byte], off: Int, len: Int): Int = native
  def errorMessage(inflater: Object): String = native
  def needsDictionary(inflater: Object): Boolean = native

  /** Whether the stream is complete and all its output delivered. */
  def finished(z: Object): Boolean = native
  /** false if the dictionary cannot be used now (or is not the one the zlib stream asks for). */
  def setDictionary(z: Object, b: Array[Byte], off: Int, len: Int): Boolean = native
  def adler(z: Object): Int = native
  def bytesRead(z: Object): Long = native
  def bytesWritten(z: Object): Long = native
  def reset(z: Object): Unit = native
  def end(z: Object): Unit = native
