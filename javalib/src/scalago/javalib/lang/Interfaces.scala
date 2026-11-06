package scalago.javalib.lang

trait CharSequence:
  def length(): Int
  def charAt(index: Int): Char
  def subSequence(start: Int, end: Int): CharSequence
  def isEmpty(): scala.Boolean = length() == 0

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

abstract class Number extends Object with java.io.Serializable:
  def intValue(): Int
  def longValue(): scala.Long
  def floatValue(): scala.Float
  def doubleValue(): scala.Double
  def byteValue(): scala.Byte = intValue().toByte
  def shortValue(): scala.Short = intValue().toShort
