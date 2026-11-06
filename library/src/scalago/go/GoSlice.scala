package scalago.go

import scala.reflect.ClassTag

import scalago.go.internal.GoNatives

/** A Go slice, `[]T`, shared with Go code without copying. `GoSlice[GoByte]` is `[]byte`,
 *  `GoSlice[GoString]` is `[]string`, `GoSlice[Long]` views any slice of 64-bit integers
 *  (`[]int`, `[]int64`, `[]uint64`). Elements are read and written in their canonical Scala
 *  representation (see `GoType`). As in Go, sub-slices share memory with the slice they come
 *  from, and `appended` may or may not.
 *
 *  On the JVM, a slice is an array segment with the same semantics.
 */
opaque type GoSlice[T] = AnyRef

object GoSlice:
  def make[T](length: Int)(using t: GoType[T]): GoSlice[T] = GoNatives.makeSlice(t.underlying, length, length)
  def make[T](length: Int, capacity: Int)(using t: GoType[T]): GoSlice[T] = GoNatives.makeSlice(t.underlying, length, capacity)
  def empty[T](using t: GoType[T]): GoSlice[T] = make[T](0)

  def of[T](elems: T*)(using t: GoType[T]): GoSlice[T] = fromSeq(elems)

  def fromSeq[T](elems: collection.Seq[T])(using t: GoType[T]): GoSlice[T] =
    val s = make[T](elems.length)
    var i = 0
    for e <- elems do
      s(i) = e
      i += 1
    s

  extension [T](s: GoSlice[T])
    def length: Int = GoNatives.sliceLength(s)
    def capacity: Int = GoNatives.sliceCapacity(s)
    def isEmpty: Boolean = length == 0
    /** Whether this is a nil slice (or null). */
    def isNil: Boolean = GoNatives.isNil(s)
    def apply(i: Int): T = GoNatives.sliceGet(s, i).asInstanceOf[T]
    def update(i: Int, value: T): Unit = GoNatives.sliceSet(s, i, value)
    /** `s[from:until]`, sharing memory. `until` may extend up to the capacity, as in Go. */
    def slice(from: Int, until: Int): GoSlice[T] = GoNatives.sliceSlice(s, from, until)
    /** Go's `append(s, value)`. */
    def appended(value: T): GoSlice[T] = GoNatives.sliceAppend(s, value)
    /** Go's `append(s, other...)`. */
    def appendedAll(other: GoSlice[T]): GoSlice[T] = GoNatives.sliceAppendAll(s, other)
    /** Go's `copy(s, src)`: the number of elements copied. */
    def copyFrom(src: GoSlice[T]): Int = GoNatives.sliceCopy(s, src)

    def iterator: Iterator[T] = Iterator.range(0, length).map(i => s(i))
    def foreach[U](f: T => U): Unit =
      var i = 0
      val n = length
      while i < n do
        f(s(i))
        i += 1
    def toSeq: IndexedSeq[T] = IndexedSeq.tabulate(length)(i => s(i))
    def toArray(using ClassTag[T]): Array[T] = Array.tabulate(length)(i => s(i))
    def mkString(sep: String): String = iterator.mkString(sep)
