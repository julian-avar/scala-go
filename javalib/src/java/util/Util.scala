package java.util

class NoSuchElementException(message: String) extends RuntimeException(message):
  def this() = this(null)

class ConcurrentModificationException(message: String) extends RuntimeException(message):
  def this() = this(null)

trait Iterator[E]:
  def hasNext(): Boolean
  def next(): E
  def remove(): Unit = throw new UnsupportedOperationException("remove")

trait Comparator[T]:
  def compare(o1: T, o2: T): Int

  def reversed(): Comparator[T] =
    val self = this
    new Comparator[T]:
      def compare(o1: T, o2: T): Int = self.compare(o2, o1)

  def thenComparing(other: Comparator[? >: T]): Comparator[T] =
    val self = this
    new Comparator[T]:
      def compare(o1: T, o2: T): Int =
        val c = self.compare(o1, o2)
        if c != 0 then c else other.compare(o1, o2)
