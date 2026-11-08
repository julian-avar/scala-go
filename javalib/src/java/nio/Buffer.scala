package java.nio

import scala.annotation.static

/** java.nio.Buffer: the position, limit, capacity and mark of the JDK's buffers, with its
 *  exceptions and messages.
 *
 *  All buffers are heap buffers: arrays, the views of byte arrays that `ByteBuffer.asIntBuffer`
 *  and the like make, and read-only text (`CharBuffer.wrap(CharSequence)`). The concrete classes
 *  have the JDK's names (`HeapByteBuffer`, `HeapByteBufferR`, `ByteBufferAsIntBufferB`,
 *  `StringCharBuffer`, ...), which `toString` shows. `allocateDirect` makes a heap buffer.
 */
abstract class Buffer private[nio] (cap: Int):
  if cap < 0 then throw new IllegalArgumentException("capacity < 0: (" + cap + " < 0)")

  private[nio] var pos = 0
  private[nio] var lim = cap
  private[nio] var markPos = -1

  final def capacity(): Int = cap
  final def position(): Int = pos
  final def limit(): Int = lim

  def position(newPosition: Int): Buffer =
    if newPosition > lim || newPosition < 0 then
      throw new IllegalArgumentException(
        if newPosition > lim then "newPosition > limit: (" + newPosition + " > " + lim + ")"
        else "newPosition < 0: (" + newPosition + " < 0)")
    if markPos > newPosition then markPos = -1
    pos = newPosition
    this

  def limit(newLimit: Int): Buffer =
    if newLimit > cap || newLimit < 0 then
      throw new IllegalArgumentException(
        if newLimit > cap then "newLimit > capacity: (" + newLimit + " > " + cap + ")"
        else "newLimit < 0: (" + newLimit + " < 0)")
    lim = newLimit
    if pos > newLimit then pos = newLimit
    if markPos > newLimit then markPos = -1
    this

  def mark(): Buffer =
    markPos = pos
    this

  def reset(): Buffer =
    if markPos < 0 then throw new InvalidMarkException()
    pos = markPos
    this

  def clear(): Buffer =
    pos = 0
    lim = cap
    markPos = -1
    this

  def flip(): Buffer =
    lim = pos
    pos = 0
    markPos = -1
    this

  def rewind(): Buffer =
    pos = 0
    markPos = -1
    this

  final def remaining(): Int =
    val rem = lim - pos
    if rem > 0 then rem else 0

  final def hasRemaining(): Boolean = pos < lim

  def isReadOnly(): Boolean
  def hasArray(): Boolean
  def array(): Object
  def arrayOffset(): Int
  def isDirect(): Boolean
  def slice(): Buffer
  def slice(index: Int, length: Int): Buffer
  def duplicate(): Buffer

  override def toString(): String =
    getClass().getName() + "[pos=" + pos + " lim=" + lim + " cap=" + cap + "]"

  // ---- For the subclasses -------------------------------------------------------------------

  /** Sets the state of a new buffer (slices, duplicates, wrapped arrays). */
  private[nio] final def init(position: Int, limit: Int, mark: Int): Unit =
    lim = limit
    pos = position
    markPos = mark

  /** The position, advanced by one: for relative gets. */
  private[nio] final def nextGetIndex(): Int =
    val p = pos
    if p >= lim then throw new BufferUnderflowException()
    pos = p + 1
    p

  private[nio] final def nextGetIndex(n: Int): Int =
    val p = pos
    if lim - p < n then throw new BufferUnderflowException()
    pos = p + n
    p

  /** The position, advanced by one: for relative puts. */
  private[nio] final def nextPutIndex(): Int =
    val p = pos
    if p >= lim then throw new BufferOverflowException()
    pos = p + 1
    p

  private[nio] final def nextPutIndex(n: Int): Int =
    val p = pos
    if lim - p < n then throw new BufferOverflowException()
    pos = p + n
    p

  /** An absolute index, checked against the limit. */
  private[nio] final def checkIndex(i: Int): Int =
    if i < 0 || i >= lim then throw new IndexOutOfBoundsException()
    i

  /** An absolute index of `n` elements (or bytes), checked against the limit. */
  private[nio] final def checkIndex(i: Int, n: Int): Int =
    if i < 0 || n > lim - i then throw new IndexOutOfBoundsException()
    i

  private[nio] final def checkWritable(): Unit =
    if isReadOnly() then throw new ReadOnlyBufferException()

private[nio] object Buffer:
  /** `Objects.checkFromIndexSize`, with the JDK's message. */
  def checkRange(from: Int, size: Int, length: Int): Unit =
    if (length | from | size) < 0 || size > length - from then
      throw new IndexOutOfBoundsException(
        "Range [" + from + ", " + from + " + " + size + ") out of bounds for length " + length)

  /** The checks of `wrap(array, offset, length)`. */
  def checkWrap(arrayLength: Int, offset: Int, length: Int): Unit =
    if (offset | length) < 0 || length > arrayLength - offset then throw new IndexOutOfBoundsException()

final class ByteOrder private (name: String):
  override def toString(): String = name

object ByteOrder:
  @static val BIG_ENDIAN: ByteOrder = new ByteOrder("BIG_ENDIAN")
  @static val LITTLE_ENDIAN: ByteOrder = new ByteOrder("LITTLE_ENDIAN")

  /** Go runs on little-endian machines only (amd64, arm64, ...), except for a few rarely used ones. */
  @static def nativeOrder(): ByteOrder = LITTLE_ENDIAN

class BufferOverflowException() extends RuntimeException()
class BufferUnderflowException() extends RuntimeException()
class InvalidMarkException() extends IllegalStateException()
class ReadOnlyBufferException() extends UnsupportedOperationException()

/** Reads and writes multi-byte values in byte arrays, in either byte order. */
private[nio] object Bits:
  def getShort(a: Array[Byte], i: Int, bigEndian: Boolean): Short =
    if bigEndian then ((a(i) << 8) | (a(i + 1) & 0xff)).toShort
    else ((a(i + 1) << 8) | (a(i) & 0xff)).toShort

  def putShort(a: Array[Byte], i: Int, x: Short, bigEndian: Boolean): Unit =
    if bigEndian then
      a(i) = (x >> 8).toByte
      a(i + 1) = x.toByte
    else
      a(i) = x.toByte
      a(i + 1) = (x >> 8).toByte

  def getChar(a: Array[Byte], i: Int, bigEndian: Boolean): Char = getShort(a, i, bigEndian).toChar
  def putChar(a: Array[Byte], i: Int, x: Char, bigEndian: Boolean): Unit = putShort(a, i, x.toShort, bigEndian)

  def getInt(a: Array[Byte], i: Int, bigEndian: Boolean): Int =
    if bigEndian then (a(i) << 24) | ((a(i + 1) & 0xff) << 16) | ((a(i + 2) & 0xff) << 8) | (a(i + 3) & 0xff)
    else (a(i + 3) << 24) | ((a(i + 2) & 0xff) << 16) | ((a(i + 1) & 0xff) << 8) | (a(i) & 0xff)

  def putInt(a: Array[Byte], i: Int, x: Int, bigEndian: Boolean): Unit =
    if bigEndian then
      a(i) = (x >> 24).toByte
      a(i + 1) = (x >> 16).toByte
      a(i + 2) = (x >> 8).toByte
      a(i + 3) = x.toByte
    else
      a(i) = x.toByte
      a(i + 1) = (x >> 8).toByte
      a(i + 2) = (x >> 16).toByte
      a(i + 3) = (x >> 24).toByte

  def getLong(a: Array[Byte], i: Int, bigEndian: Boolean): Long =
    val first = getInt(a, i, bigEndian).toLong
    val second = getInt(a, i + 4, bigEndian).toLong
    if bigEndian then (first << 32) | (second & 0xffffffffL) else (second << 32) | (first & 0xffffffffL)

  def putLong(a: Array[Byte], i: Int, x: Long, bigEndian: Boolean): Unit =
    if bigEndian then
      putInt(a, i, (x >> 32).toInt, true)
      putInt(a, i + 4, x.toInt, true)
    else
      putInt(a, i, x.toInt, false)
      putInt(a, i + 4, (x >> 32).toInt, false)

  def getFloat(a: Array[Byte], i: Int, bigEndian: Boolean): Float =
    java.lang.Float.intBitsToFloat(getInt(a, i, bigEndian))
  def putFloat(a: Array[Byte], i: Int, x: Float, bigEndian: Boolean): Unit =
    putInt(a, i, java.lang.Float.floatToRawIntBits(x), bigEndian)

  def getDouble(a: Array[Byte], i: Int, bigEndian: Boolean): Double =
    java.lang.Double.longBitsToDouble(getLong(a, i, bigEndian))
  def putDouble(a: Array[Byte], i: Int, x: Double, bigEndian: Boolean): Unit =
    putLong(a, i, java.lang.Double.doubleToRawLongBits(x), bigEndian)
