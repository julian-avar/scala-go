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
