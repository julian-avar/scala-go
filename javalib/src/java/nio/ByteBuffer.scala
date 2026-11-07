package java.nio

import scala.annotation.static

/** java.nio.ByteBuffer, on a byte array: `hb(offset)` is element 0. The multi-byte accessors and
 *  the views (`asIntBuffer`, ...) use the buffer's byte order, big-endian by default.
 */
abstract class ByteBuffer private[nio] (cap: Int, private[nio] val hb: Array[Byte], private[nio] val offset: Int)
    extends Buffer(cap)
    with Comparable[ByteBuffer]:
  private[nio] var bigEndian = true

  /** A buffer of the same kind (read-only or not) over the same array. */
  private[nio] def make(cap: Int, offset: Int): ByteBuffer

  def isDirect(): Boolean = false

  final def hasArray(): Boolean = !isReadOnly()

  final def array(): Array[Byte] =
    if isReadOnly() then throw new ReadOnlyBufferException()
    hb

  final def arrayOffset(): Int =
    if isReadOnly() then throw new ReadOnlyBufferException()
    offset

  // The slices, duplicates and read-only views of a byte buffer are big-endian, as on the JDK.

  def slice(): ByteBuffer = make(remaining(), offset + pos)

  def slice(index: Int, length: Int): ByteBuffer =
    Buffer.checkRange(index, length, lim)
    make(length, offset + index)

  def duplicate(): ByteBuffer =
    val b = make(capacity(), offset)
    b.init(pos, lim, markPos)
    b

  def asReadOnlyBuffer(): ByteBuffer =
    val b = new HeapByteBufferR(capacity(), hb, offset)
    b.init(pos, lim, markPos)
    b

  def compact(): ByteBuffer =
    checkWritable()
    val n = remaining()
    System.arraycopy(hb, offset + pos, hb, offset, n)
    pos = n
    lim = capacity()
    markPos = -1
    this

  // ---- Single bytes ---------------------------------------------------------------------------

  final def get(): Byte = hb(offset + nextGetIndex())
  final def get(index: Int): Byte = hb(offset + checkIndex(index))

  final def put(b: Byte): ByteBuffer =
    checkWritable()
    hb(offset + nextPutIndex()) = b
    this

  final def put(index: Int, b: Byte): ByteBuffer =
    checkWritable()
    hb(offset + checkIndex(index)) = b
    this

  // ---- Bulk transfers -------------------------------------------------------------------------

  def get(dst: Array[Byte], off: Int, length: Int): ByteBuffer =
    Buffer.checkRange(off, length, dst.length)
    if length > remaining() then throw new BufferUnderflowException()
    System.arraycopy(hb, offset + pos, dst, off, length)
    pos += length
    this

  def get(dst: Array[Byte]): ByteBuffer = get(dst, 0, dst.length)

  def get(index: Int, dst: Array[Byte], off: Int, length: Int): ByteBuffer =
    Buffer.checkRange(index, length, lim)
    Buffer.checkRange(off, length, dst.length)
    System.arraycopy(hb, offset + index, dst, off, length)
    this

  def get(index: Int, dst: Array[Byte]): ByteBuffer = get(index, dst, 0, dst.length)

  def put(src: ByteBuffer): ByteBuffer =
    if src eq this then throw new IllegalArgumentException("The source buffer is this buffer")
    checkWritable()
    val n = src.remaining()
    if n > remaining() then throw new BufferOverflowException()
    System.arraycopy(src.hb, src.offset + src.pos, hb, offset + pos, n)
    src.pos += n
    pos += n
    this

  def put(index: Int, src: ByteBuffer, off: Int, length: Int): ByteBuffer =
    Buffer.checkRange(index, length, lim)
    Buffer.checkRange(off, length, src.lim)
    checkWritable()
    System.arraycopy(src.hb, src.offset + off, hb, offset + index, length)
    this

  def put(src: Array[Byte], off: Int, length: Int): ByteBuffer =
    checkWritable()
    Buffer.checkRange(off, length, src.length)
    if length > remaining() then throw new BufferOverflowException()
    System.arraycopy(src, off, hb, offset + pos, length)
    pos += length
    this

  final def put(src: Array[Byte]): ByteBuffer = put(src, 0, src.length)

  def put(index: Int, src: Array[Byte], off: Int, length: Int): ByteBuffer =
    checkWritable()
    Buffer.checkRange(index, length, lim)
    Buffer.checkRange(off, length, src.length)
    System.arraycopy(src, off, hb, offset + index, length)
    this

  def put(index: Int, src: Array[Byte]): ByteBuffer = put(index, src, 0, src.length)

  // ---- Byte order and multi-byte values -------------------------------------------------------

  final def order(): ByteOrder = if bigEndian then ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN

  final def order(bo: ByteOrder): ByteBuffer =
    if bo == null then throw new NullPointerException()
    bigEndian = bo eq ByteOrder.BIG_ENDIAN
    this

  final def getChar(): Char = Bits.getChar(hb, offset + nextGetIndex(2), bigEndian)
  final def getChar(index: Int): Char = Bits.getChar(hb, offset + checkIndex(index, 2), bigEndian)
  final def putChar(value: Char): ByteBuffer =
    checkWritable()
    Bits.putChar(hb, offset + nextPutIndex(2), value, bigEndian)
    this
  final def putChar(index: Int, value: Char): ByteBuffer =
    checkWritable()
    Bits.putChar(hb, offset + checkIndex(index, 2), value, bigEndian)
    this

  final def getShort(): Short = Bits.getShort(hb, offset + nextGetIndex(2), bigEndian)
  final def getShort(index: Int): Short = Bits.getShort(hb, offset + checkIndex(index, 2), bigEndian)
  final def putShort(value: Short): ByteBuffer =
    checkWritable()
    Bits.putShort(hb, offset + nextPutIndex(2), value, bigEndian)
    this
  final def putShort(index: Int, value: Short): ByteBuffer =
    checkWritable()
    Bits.putShort(hb, offset + checkIndex(index, 2), value, bigEndian)
    this

  final def getInt(): Int = Bits.getInt(hb, offset + nextGetIndex(4), bigEndian)
  final def getInt(index: Int): Int = Bits.getInt(hb, offset + checkIndex(index, 4), bigEndian)
  final def putInt(value: Int): ByteBuffer =
    checkWritable()
    Bits.putInt(hb, offset + nextPutIndex(4), value, bigEndian)
    this
  final def putInt(index: Int, value: Int): ByteBuffer =
    checkWritable()
    Bits.putInt(hb, offset + checkIndex(index, 4), value, bigEndian)
    this

  final def getLong(): Long = Bits.getLong(hb, offset + nextGetIndex(8), bigEndian)
  final def getLong(index: Int): Long = Bits.getLong(hb, offset + checkIndex(index, 8), bigEndian)
  final def putLong(value: Long): ByteBuffer =
    checkWritable()
    Bits.putLong(hb, offset + nextPutIndex(8), value, bigEndian)
    this
  final def putLong(index: Int, value: Long): ByteBuffer =
    checkWritable()
    Bits.putLong(hb, offset + checkIndex(index, 8), value, bigEndian)
    this

  final def getFloat(): Float = Bits.getFloat(hb, offset + nextGetIndex(4), bigEndian)
  final def getFloat(index: Int): Float = Bits.getFloat(hb, offset + checkIndex(index, 4), bigEndian)
  final def putFloat(value: Float): ByteBuffer =
    checkWritable()
    Bits.putFloat(hb, offset + nextPutIndex(4), value, bigEndian)
    this
  final def putFloat(index: Int, value: Float): ByteBuffer =
    checkWritable()
    Bits.putFloat(hb, offset + checkIndex(index, 4), value, bigEndian)
    this

  final def getDouble(): Double = Bits.getDouble(hb, offset + nextGetIndex(8), bigEndian)
  final def getDouble(index: Int): Double = Bits.getDouble(hb, offset + checkIndex(index, 8), bigEndian)
  final def putDouble(value: Double): ByteBuffer =
    checkWritable()
    Bits.putDouble(hb, offset + nextPutIndex(8), value, bigEndian)
    this
  final def putDouble(index: Int, value: Double): ByteBuffer =
    checkWritable()
    Bits.putDouble(hb, offset + checkIndex(index, 8), value, bigEndian)
    this

  // ---- Views ----------------------------------------------------------------------------------

  /** The remaining bytes' offset in `hb`, and the number of `size`-byte elements they hold. */
  private def viewCapacity(size: Int): Int = remaining() / size

  def asCharBuffer(): CharBuffer =
    ByteBufferAsCharBuffer(hb, offset + pos, viewCapacity(2), isReadOnly(), bigEndian)
  def asShortBuffer(): ShortBuffer =
    ByteBufferAsShortBuffer(hb, offset + pos, viewCapacity(2), isReadOnly(), bigEndian)
  def asIntBuffer(): IntBuffer =
    ByteBufferAsIntBuffer(hb, offset + pos, viewCapacity(4), isReadOnly(), bigEndian)
  def asLongBuffer(): LongBuffer =
    ByteBufferAsLongBuffer(hb, offset + pos, viewCapacity(8), isReadOnly(), bigEndian)
  def asFloatBuffer(): FloatBuffer =
    ByteBufferAsFloatBuffer(hb, offset + pos, viewCapacity(4), isReadOnly(), bigEndian)
  def asDoubleBuffer(): DoubleBuffer =
    ByteBufferAsDoubleBuffer(hb, offset + pos, viewCapacity(8), isReadOnly(), bigEndian)

  // ---- Buffer's methods, with this type -------------------------------------------------------

  override def position(newPosition: Int): ByteBuffer =
    super.position(newPosition)
    this
  override def limit(newLimit: Int): ByteBuffer =
    super.limit(newLimit)
    this
  override def mark(): ByteBuffer =
    super.mark()
    this
  override def reset(): ByteBuffer =
    super.reset()
    this
  override def clear(): ByteBuffer =
    super.clear()
    this
  override def flip(): ByteBuffer =
    super.flip()
    this
  override def rewind(): ByteBuffer =
    super.rewind()
    this

  // ---- Comparisons ----------------------------------------------------------------------------

  /** The index (relative to the positions) of the first difference in the remaining bytes, or -1. */
  def mismatch(that: ByteBuffer): Int =
    val n = Math.min(remaining(), that.remaining())
    var i = 0
    while i < n && hb(offset + pos + i) == that.hb(that.offset + that.pos + i) do i += 1
    if i < n || remaining() != that.remaining() then i else -1

  def compareTo(that: ByteBuffer): Int =
    val i = mismatch(that)
    if i >= 0 && i < remaining() && i < that.remaining() then
      java.lang.Byte.compare(hb(offset + pos + i), that.hb(that.offset + that.pos + i))
    else remaining() - that.remaining()

  override def equals(that: Any): Boolean = that match
    case that: ByteBuffer => (this eq that) || mismatch(that) < 0
    case _                => false

  override def hashCode(): Int =
    var h = 1
    var i = lim - 1
    while i >= pos do
      h = 31 * h + hb(offset + i)
      i -= 1
    h

object ByteBuffer:
  @static def allocate(capacity: Int): ByteBuffer =
    if capacity < 0 then throw new IllegalArgumentException("capacity < 0: (" + capacity + " < 0)")
    new HeapByteBuffer(capacity, new Array[Byte](capacity), 0)

  /** A heap buffer (see [[Buffer]]). */
  @static def allocateDirect(capacity: Int): ByteBuffer = allocate(capacity)

  @static def wrap(array: Array[Byte], offset: Int, length: Int): ByteBuffer =
    Buffer.checkWrap(array.length, offset, length)
    val b = new HeapByteBuffer(array.length, array, 0)
    b.init(offset, offset + length, -1)
    b

  @static def wrap(array: Array[Byte]): ByteBuffer = new HeapByteBuffer(array.length, array, 0)

private[nio] class HeapByteBuffer(cap: Int, array0: Array[Byte], offset0: Int) extends ByteBuffer(cap, array0, offset0):
  def isReadOnly(): Boolean = false
  private[nio] def make(cap: Int, offset: Int): ByteBuffer = new HeapByteBuffer(cap, hb, offset)

private[nio] final class HeapByteBufferR(cap: Int, array0: Array[Byte], offset0: Int) extends ByteBuffer(cap, array0, offset0):
  def isReadOnly(): Boolean = true
  private[nio] def make(cap: Int, offset: Int): ByteBuffer = new HeapByteBufferR(cap, hb, offset)

/** java.nio.MappedByteBuffer. `FileChannel.map` gives a copy of the file's contents in memory
 *  (see `FileChannel`).
 */
abstract class MappedByteBuffer private[nio] (cap: Int, array0: Array[Byte], offset0: Int) extends ByteBuffer(cap, array0, offset0):
  def isLoaded(): Boolean = true
  def load(): MappedByteBuffer = this
  def force(): MappedByteBuffer = this
  def force(index: Int, length: Int): MappedByteBuffer =
    Buffer.checkRange(index, length, lim)
    this

  override def slice(): MappedByteBuffer = super.slice().asInstanceOf[MappedByteBuffer]
  override def slice(index: Int, length: Int): MappedByteBuffer = super.slice(index, length).asInstanceOf[MappedByteBuffer]
  override def duplicate(): MappedByteBuffer = super.duplicate().asInstanceOf[MappedByteBuffer]
  override def compact(): MappedByteBuffer =
    super.compact()
    this
  override def position(newPosition: Int): MappedByteBuffer =
    super.position(newPosition)
    this
  override def limit(newLimit: Int): MappedByteBuffer =
    super.limit(newLimit)
    this
  override def mark(): MappedByteBuffer =
    super.mark()
    this
  override def reset(): MappedByteBuffer =
    super.reset()
    this
  override def clear(): MappedByteBuffer =
    super.clear()
    this
  override def flip(): MappedByteBuffer =
    super.flip()
    this
  override def rewind(): MappedByteBuffer =
    super.rewind()
    this

private[nio] final class HeapMappedByteBuffer(cap: Int, array0: Array[Byte], offset0: Int, readOnly: Boolean)
    extends MappedByteBuffer(cap, array0, offset0):
  def isReadOnly(): Boolean = readOnly
  private[nio] def make(cap: Int, offset: Int): ByteBuffer = new HeapMappedByteBuffer(cap, hb, offset, readOnly)
