package java.nio

import scala.annotation.static

/** java.nio.CharBuffer (see [[Buffer]]): an array (`HeapCharBuffer`), a view of a byte array
 *  (`ByteBufferAsCharBuffer`) or read-only text (`StringCharBuffer`). Its implementations, but
 *  for `StringCharBuffer`, are generated with those of the other buffers (see `javalib/templates`).
 *
 *  As a `CharSequence`, it is the remaining chars.
 */
abstract class CharBuffer private[nio] (cap: Int)
    extends Buffer(cap)
    with Comparable[CharBuffer]
    with Appendable
    with CharSequence
    with Readable:
  // The implementations' part, as in the other buffers (see TypedBuffer.scala.template).
  private[nio] def load(i: Int): Char
  private[nio] def store(i: Int, x: Char): Unit
  private[nio] def backing: Array[Char]
  private[nio] def backingOffset: Int
  private[nio] def sliceAt(index: Int, cap: Int): CharBuffer
  private[nio] def readOnlyView(): CharBuffer

  def order(): ByteOrder

  final def hasArray(): Boolean = backing != null && !isReadOnly()

  final def array(): Array[Char] =
    if backing == null then throw new UnsupportedOperationException()
    if isReadOnly() then throw new ReadOnlyBufferException()
    backing

  final def arrayOffset(): Int =
    if backing == null then throw new UnsupportedOperationException()
    if isReadOnly() then throw new ReadOnlyBufferException()
    backingOffset

  def slice(): CharBuffer = sliceAt(pos, remaining())

  def slice(index: Int, length: Int): CharBuffer =
    Buffer.checkRange(index, length, lim)
    sliceAt(index, length)

  def duplicate(): CharBuffer =
    val b = sliceAt(0, capacity())
    b.init(pos, lim, markPos)
    b

  def asReadOnlyBuffer(): CharBuffer =
    val b = readOnlyView()
    b.init(pos, lim, markPos)
    b

  def compact(): CharBuffer =
    checkWritable()
    val n = remaining()
    val a = backing
    if a != null then System.arraycopy(a, backingOffset + pos, a, backingOffset, n)
    else
      var i = 0
      while i < n do
        store(i, load(pos + i))
        i += 1
    pos = n
    lim = capacity()
    markPos = -1
    this

  final def get(): Char = load(nextGetIndex())
  final def get(index: Int): Char = load(checkIndex(index))

  final def put(c: Char): CharBuffer =
    checkWritable()
    store(nextPutIndex(), c)
    this

  final def put(index: Int, c: Char): CharBuffer =
    checkWritable()
    store(checkIndex(index), c)
    this

  private def loadAll(index: Int, dst: Array[Char], off: Int, n: Int): Unit =
    val a = backing
    if a != null then System.arraycopy(a, backingOffset + index, dst, off, n)
    else
      var i = 0
      while i < n do
        dst(off + i) = load(index + i)
        i += 1

  private def storeAll(index: Int, src: Array[Char], off: Int, n: Int): Unit =
    val a = backing
    if a != null then System.arraycopy(src, off, a, backingOffset + index, n)
    else
      var i = 0
      while i < n do
        store(index + i, src(off + i))
        i += 1

  private def storeAll(index: Int, src: CharBuffer, srcIndex: Int, n: Int): Unit =
    val a = src.backing
    if a != null then storeAll(index, a, src.backingOffset + srcIndex, n)
    else
      var i = 0
      while i < n do
        store(index + i, src.load(srcIndex + i))
        i += 1

  def get(dst: Array[Char], off: Int, length: Int): CharBuffer =
    Buffer.checkRange(off, length, dst.length)
    if length > remaining() then throw new BufferUnderflowException()
    loadAll(pos, dst, off, length)
    pos += length
    this

  def get(dst: Array[Char]): CharBuffer = get(dst, 0, dst.length)

  def get(index: Int, dst: Array[Char], off: Int, length: Int): CharBuffer =
    Buffer.checkRange(index, length, lim)
    Buffer.checkRange(off, length, dst.length)
    loadAll(index, dst, off, length)
    this

  def get(index: Int, dst: Array[Char]): CharBuffer = get(index, dst, 0, dst.length)

  def put(src: CharBuffer): CharBuffer =
    if src eq this then throw new IllegalArgumentException("The source buffer is this buffer")
    checkWritable()
    val n = src.remaining()
    if n > remaining() then throw new BufferOverflowException()
    storeAll(pos, src, src.pos, n)
    src.pos += n
    pos += n
    this

  def put(index: Int, src: CharBuffer, off: Int, length: Int): CharBuffer =
    Buffer.checkRange(index, length, lim)
    Buffer.checkRange(off, length, src.lim)
    checkWritable()
    storeAll(index, src, off, length)
    this

  def put(src: Array[Char], off: Int, length: Int): CharBuffer =
    checkWritable()
    Buffer.checkRange(off, length, src.length)
    if length > remaining() then throw new BufferOverflowException()
    storeAll(pos, src, off, length)
    pos += length
    this

  final def put(src: Array[Char]): CharBuffer = put(src, 0, src.length)

  def put(index: Int, src: Array[Char], off: Int, length: Int): CharBuffer =
    checkWritable()
    Buffer.checkRange(index, length, lim)
    Buffer.checkRange(off, length, src.length)
    storeAll(index, src, off, length)
    this

  def put(index: Int, src: Array[Char]): CharBuffer = put(index, src, 0, src.length)

  def put(src: String, start: Int, end: Int): CharBuffer =
    Buffer.checkRange(start, end - start, src.length())
    checkWritable()
    val n = end - start
    if n > remaining() then throw new BufferOverflowException()
    val a = backing
    if a != null then src.getChars(start, end, a, backingOffset + pos)
    else
      var i = 0
      while i < n do
        store(pos + i, src.charAt(start + i))
        i += 1
    pos += n
    this

  final def put(src: String): CharBuffer = put(src, 0, src.length())

  /** Readable: transfers chars to `target`. */
  def read(target: CharBuffer): Int =
    if target eq this then throw new IllegalArgumentException("The source buffer is this buffer")
    val n = remaining()
    if n == 0 then -1
    else
      val m = Math.min(n, target.remaining())
      if m > 0 then
        val oldLimit = lim
        limit(pos + m)
        try target.put(this)
        finally limit(oldLimit)
      m

  // ---- CharSequence and Appendable ------------------------------------------------------------

  final def length(): Int = remaining()
  override final def isEmpty(): Boolean = remaining() == 0

  final def charAt(index: Int): Char =
    if index < 0 || index >= remaining() then throw new IndexOutOfBoundsException()
    load(pos + index)

  /** The chars from `start` to `end` (relative to the position), sharing this buffer's chars:
   *  its position and limit are those of the subsequence, its capacity this buffer's.
   */
  def subSequence(start: Int, end: Int): CharBuffer =
    if start < 0 || end > remaining() || start > end then throw new IndexOutOfBoundsException()
    val b = sliceAt(0, capacity())
    b.init(pos + start, pos + end, -1)
    b

  override def toString(): String =
    val n = remaining()
    val a = backing
    if a != null then new String(a, backingOffset + pos, n)
    else
      val chars = new Array[Char](n)
      loadAll(pos, chars, 0, n)
      new String(chars)

  def append(csq: CharSequence): CharBuffer = put(String.valueOf(csq))

  def append(csq: CharSequence, start: Int, end: Int): CharBuffer =
    val cs: CharSequence = if csq == null then "null" else csq
    put(cs.subSequence(start, end).toString())

  def append(c: Char): CharBuffer = put(c)

  // ---- Buffer's methods, with this type -------------------------------------------------------

  override def position(newPosition: Int): CharBuffer =
    super.position(newPosition)
    this
  override def limit(newLimit: Int): CharBuffer =
    super.limit(newLimit)
    this
  override def mark(): CharBuffer =
    super.mark()
    this
  override def reset(): CharBuffer =
    super.reset()
    this
  override def clear(): CharBuffer =
    super.clear()
    this
  override def flip(): CharBuffer =
    super.flip()
    this
  override def rewind(): CharBuffer =
    super.rewind()
    this

  // ---- Comparisons ----------------------------------------------------------------------------

  def mismatch(that: CharBuffer): Int =
    val n = Math.min(remaining(), that.remaining())
    var i = 0
    while i < n && load(pos + i) == that.load(that.pos + i) do i += 1
    if i < n || remaining() != that.remaining() then i else -1

  def compareTo(that: CharBuffer): Int =
    val i = mismatch(that)
    if i >= 0 && i < remaining() && i < that.remaining() then load(pos + i) - that.load(that.pos + i)
    else remaining() - that.remaining()

  override def equals(that: Any): Boolean = that match
    case that: CharBuffer => (this eq that) || mismatch(that) < 0
    case _                => false

  override def hashCode(): Int =
    var h = 1
    var i = lim - 1
    while i >= pos do
      h = 31 * h + load(i)
      i -= 1
    h

object CharBuffer:
  @static def allocate(capacity: Int): CharBuffer =
    if capacity < 0 then throw new IllegalArgumentException("capacity < 0: (" + capacity + " < 0)")
    new HeapCharBuffer(capacity, new Array[Char](capacity), 0)

  @static def wrap(array: Array[Char], offset: Int, length: Int): CharBuffer =
    Buffer.checkWrap(array.length, offset, length)
    val b = new HeapCharBuffer(array.length, array, 0)
    b.init(offset, offset + length, -1)
    b

  @static def wrap(array: Array[Char]): CharBuffer = new HeapCharBuffer(array.length, array, 0)

  /** A read-only buffer of the text, which it reads when its chars are read. */
  @static def wrap(csq: CharSequence, start: Int, end: Int): CharBuffer =
    if start < 0 || end > csq.length() || start > end then throw new IndexOutOfBoundsException()
    val b = new StringCharBuffer(csq.length(), csq, 0)
    b.init(start, end, -1)
    b

  @static def wrap(csq: CharSequence): CharBuffer = new StringCharBuffer(csq.length(), csq, 0)

/** Read-only text as a CharBuffer: element `i` is `text.charAt(offset + i)`. */
private[nio] final class StringCharBuffer(cap: Int, text: CharSequence, offset: Int) extends CharBuffer(cap):
  def isReadOnly(): Boolean = true
  def isDirect(): Boolean = false
  def order(): ByteOrder = ByteOrder.nativeOrder()
  private[nio] def load(i: Int): Char = text.charAt(offset + i)
  private[nio] def store(i: Int, x: Char): Unit = throw new ReadOnlyBufferException()
  private[nio] def backing: Array[Char] = null
  private[nio] def backingOffset: Int = 0
  private[nio] def sliceAt(index: Int, cap: Int): CharBuffer = new StringCharBuffer(cap, text, offset + index)
  private[nio] def readOnlyView(): CharBuffer = new StringCharBuffer(capacity(), text, offset)

  override def toString(): String = text.subSequence(offset + pos, offset + lim).toString()
