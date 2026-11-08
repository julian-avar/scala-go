package scalago.go

import scala.reflect.ClassTag

import scalago.go.internal.GoNatives

/** A Go slice, `[]T`, shared with Go code without copying.
 *
 *  {{{
 *  import scalago.go.*
 *
 *  val xs = GoSlice.of[Long](3, 1, 2)   // []int64
 *  xs(0) = 30
 *  val tail = xs.slice(1, 3)            // shares memory with xs
 *  val more = xs.appended(4)            // Go's append
 *  val ints = GoSlice.make[GoInt](10)   // []int, ten zeros
 *  }}}
 *
 *  `GoSlice[GoByte]` is `[]byte`, `GoSlice[GoString]` is `[]string`, `GoSlice[Long]` views any
 *  slice of 64-bit integers (`[]int`, `[]int64`, `[]uint64`). Elements are read and written in
 *  their canonical Scala representation (see [[GoType]]); creating a slice takes the Go element
 *  type from the type argument. As in Go, sub-slices share memory with the slice they come from,
 *  and `appended` may or may not.
 *
 *  On the JVM, a slice is an array segment with the same semantics.
 */
opaque type GoSlice[T] = AnyRef

object GoSlice:
  /** Go's `make([]T, length)`: `length` zero values. */
  def make[T](length: Int)(using t: GoType[T]): GoSlice[T] = GoNatives.makeSlice(t.underlying, length, length)
  /** Go's `make([]T, length, capacity)`. */
  def make[T](length: Int, capacity: Int)(using t: GoType[T]): GoSlice[T] = GoNatives.makeSlice(t.underlying, length, capacity)
  /** An empty, non-nil slice. */
  def empty[T](using t: GoType[T]): GoSlice[T] = make[T](0)

  /** A new slice holding `elems`. */
  def of[T](elems: T*)(using t: GoType[T]): GoSlice[T] = fromSeq(elems)

  /** A new slice holding the elements of `elems`. */
  def fromSeq[T](elems: collection.Seq[T])(using t: GoType[T]): GoSlice[T] =
    val s = make[T](elems.length)
    var i = 0
    for e <- elems do
      s(i) = e
      i += 1
    s

  extension [T](s: GoSlice[T])
    /** Go's `len(s)`. */
    def length: Int = GoNatives.sliceLength(s)
    /** Go's `cap(s)`. */
    def capacity: Int = GoNatives.sliceCapacity(s)
    def isEmpty: Boolean = length == 0
    /** Whether this is a nil slice (or null). */
    def isNil: Boolean = GoNatives.isNil(s)
    /** `s[i]`; throws `ArrayIndexOutOfBoundsException` out of range. */
    def apply(i: Int): T = GoNatives.sliceGet(s, i).asInstanceOf[T]
    /** `s[i] = value`. */
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
