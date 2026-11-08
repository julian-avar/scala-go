package scalago.javalib.lang

trait CharSequence:
  def length(): Int
  def charAt(index: Int): Char
  def subSequence(start: Int, end: Int): CharSequence
  def isEmpty(): scala.Boolean = length() == 0
  /** Since JDK 25 (the Scala library's StringBuilder calls it). */
  def getChars(srcBegin: Int, srcEnd: Int, dst: Array[Char], dstBegin: Int): Unit =
    if srcBegin < 0 || srcEnd > length() || srcBegin > srcEnd then
      throw new java.lang.IndexOutOfBoundsException("begin " + srcBegin + ", end " + srcEnd + ", length " + length())
    if dstBegin < 0 || dstBegin > dst.length - (srcEnd - srcBegin) then
      throw new java.lang.IndexOutOfBoundsException(
        "Range [" + dstBegin + ", " + dstBegin + " + " + (srcEnd - srcBegin) + ") out of bounds for length " + dst.length)
    var i = srcBegin
    while i < srcEnd do
      dst(dstBegin + i - srcBegin) = charAt(i)
      i += 1

  /** The chars, zero-extended to ints. Late-binding, as the JDK's: the length is read when the
   *  terminal operation starts. Strings use this too (the linker calls interfaces' default
   *  methods for strings when there is no native).
   */
  def chars(): java.util.stream.IntStream =
    java.util.stream.StreamSupport.intStream(
      () => new CharSequence.CharsSpliterator(this),
      java.util.Spliterator.ORDERED | java.util.Spliterator.SIZED | java.util.Spliterator.SUBSIZED,
      false)

  /** The code points: surrogate pairs combined, unpaired surrogates as they are. */
  def codePoints(): java.util.stream.IntStream =
    java.util.stream.StreamSupport.intStream(
      () => new CharSequence.CodePointsSpliterator(this),
      java.util.Spliterator.ORDERED,
      false)

object CharSequence:
  private final class CharsSpliterator(cs: CharSequence) extends java.util.Spliterator.OfInt:
    private var index = 0
    private val fence = cs.length()

    def tryAdvance(action: java.util.function.IntConsumer): Boolean =
      java.util.Objects.requireNonNull(action)
      if index < fence then
        action.accept(cs.charAt(index))
        index += 1
        true
      else false

    override def forEachRemaining(action: java.util.function.IntConsumer): Unit =
      java.util.Objects.requireNonNull(action)
      var i = index
      index = fence
      while i < fence do
        action.accept(cs.charAt(i))
        i += 1

    def trySplit(): java.util.Spliterator.OfInt = null
    def estimateSize(): scala.Long = fence - index
    def characteristics(): Int =
      java.util.Spliterator.ORDERED | java.util.Spliterator.SIZED | java.util.Spliterator.SUBSIZED

  private final class CodePointsSpliterator(cs: CharSequence) extends java.util.Spliterator.OfInt:
    private var index = 0
    private val fence = cs.length()

    def tryAdvance(action: java.util.function.IntConsumer): Boolean =
      java.util.Objects.requireNonNull(action)
      if index < fence then
        val c = cs.charAt(index)
        index += 1
        if java.lang.Character.isHighSurrogate(c) && index < fence then
          val d = cs.charAt(index)
          if java.lang.Character.isLowSurrogate(d) then
            index += 1
            action.accept(java.lang.Character.toCodePoint(c, d))
          else action.accept(c)
        else action.accept(c)
        true
      else false

    def trySplit(): java.util.Spliterator.OfInt = null
    def estimateSize(): scala.Long = fence - index
    def characteristics(): Int = java.util.Spliterator.ORDERED

trait Comparable[T]:
  def compareTo(o: T): Int

trait Runnable:
  def run(): Unit

trait AutoCloseable:
  def close(): Unit

trait Appendable:
  def append(csq: CharSequence): Appendable
  def append(csq: CharSequence, start: Int, end: Int): Appendable
  def append(c: Char): Appendable

trait Readable:
  def read(cb: java.nio.CharBuffer): Int

trait Cloneable

trait Iterable[T]:
  def iterator(): java.util.Iterator[T]

  def forEach(action: java.util.function.Consumer[? >: T]): Unit =
    val it = iterator()
    while it.hasNext() do action.accept(it.next())

  def spliterator(): java.util.Spliterator[T] = java.util.Spliterators.spliteratorUnknownSize(iterator(), 0)

abstract class Number extends Object with java.io.Serializable:
  def intValue(): Int
  def longValue(): scala.Long
  def floatValue(): scala.Float
  def doubleValue(): scala.Double
  def byteValue(): scala.Byte = intValue().toByte
  def shortValue(): scala.Short = intValue().toShort
