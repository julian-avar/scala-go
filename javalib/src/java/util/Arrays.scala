// Adapted from Scala Native's javalib (Apache-2.0, see javalib/LICENSE-scala-native.md).
/* Ported from Scala.js commit: ba618ed dated: 2020-10-05
 *
 *  Contains Scala Native specific updates subsequent to original port.
 *  See Scala Native git repository history.
 */

package java.util

import java.util.function.*

/** scala-go: the operations on primitive arrays (`sort`, `binarySearch`, `equals`, `hashCode`,
 *  `fill`, `copyOf`, `copyOfRange`, `toString`), and copying, filling, comparing and hashing of
 *  object arrays, are implemented natively by the linker (`Natives.scala`, `rt/scalalib.go`),
 *  with Go generics where Scala Native relies on specialization. Their declarations here only
 *  give javalib code something to call. `stream` is left out: `java.util.stream` is not
 *  implemented.
 */
object Arrays:
  private def native: Nothing = throw new UnsupportedOperationException("scala-go native")

  // ---- Natively implemented -------------------------------------------------------------------

  def sort(a: Array[Int]): Unit = native
  def sort(a: Array[Int], fromIndex: Int, toIndex: Int): Unit = native
  def sort(a: Array[Long]): Unit = native
  def sort(a: Array[Long], fromIndex: Int, toIndex: Int): Unit = native
  def sort(a: Array[Short]): Unit = native
  def sort(a: Array[Short], fromIndex: Int, toIndex: Int): Unit = native
  def sort(a: Array[Char]): Unit = native
  def sort(a: Array[Char], fromIndex: Int, toIndex: Int): Unit = native
  def sort(a: Array[Byte]): Unit = native
  def sort(a: Array[Byte], fromIndex: Int, toIndex: Int): Unit = native
  def sort(a: Array[Float]): Unit = native
  def sort(a: Array[Float], fromIndex: Int, toIndex: Int): Unit = native
  def sort(a: Array[Double]): Unit = native
  def sort(a: Array[Double], fromIndex: Int, toIndex: Int): Unit = native

  def binarySearch(a: Array[Long], key: Long): Int = native
  def binarySearch(a: Array[Long], fromIndex: Int, toIndex: Int, key: Long): Int = native
  def binarySearch(a: Array[Int], key: Int): Int = native
  def binarySearch(a: Array[Int], fromIndex: Int, toIndex: Int, key: Int): Int = native
  def binarySearch(a: Array[Short], key: Short): Int = native
  def binarySearch(a: Array[Short], fromIndex: Int, toIndex: Int, key: Short): Int = native
  def binarySearch(a: Array[Char], key: Char): Int = native
  def binarySearch(a: Array[Char], fromIndex: Int, toIndex: Int, key: Char): Int = native
  def binarySearch(a: Array[Byte], key: Byte): Int = native
  def binarySearch(a: Array[Byte], fromIndex: Int, toIndex: Int, key: Byte): Int = native
  def binarySearch(a: Array[Float], key: Float): Int = native
  def binarySearch(a: Array[Float], fromIndex: Int, toIndex: Int, key: Float): Int = native
  def binarySearch(a: Array[Double], key: Double): Int = native
  def binarySearch(a: Array[Double], fromIndex: Int, toIndex: Int, key: Double): Int = native

  def equals(a: Array[Long], b: Array[Long]): Boolean = native
  def equals(a: Array[Int], b: Array[Int]): Boolean = native
  def equals(a: Array[Short], b: Array[Short]): Boolean = native
  def equals(a: Array[Char], b: Array[Char]): Boolean = native
  def equals(a: Array[Byte], b: Array[Byte]): Boolean = native
  def equals(a: Array[Boolean], b: Array[Boolean]): Boolean = native
  def equals(a: Array[Float], b: Array[Float]): Boolean = native
  def equals(a: Array[Double], b: Array[Double]): Boolean = native
  def equals(a: Array[AnyRef], b: Array[AnyRef]): Boolean = native

  def hashCode(a: Array[Long]): Int = native
  def hashCode(a: Array[Int]): Int = native
  def hashCode(a: Array[Short]): Int = native
  def hashCode(a: Array[Char]): Int = native
  def hashCode(a: Array[Byte]): Int = native
  def hashCode(a: Array[Boolean]): Int = native
  def hashCode(a: Array[Float]): Int = native
  def hashCode(a: Array[Double]): Int = native
  def hashCode(a: Array[AnyRef]): Int = native

  def fill(a: Array[Long], value: Long): Unit = native
  def fill(a: Array[Long], fromIndex: Int, toIndex: Int, value: Long): Unit = native
  def fill(a: Array[Int], value: Int): Unit = native
  def fill(a: Array[Int], fromIndex: Int, toIndex: Int, value: Int): Unit = native
  def fill(a: Array[Short], value: Short): Unit = native
  def fill(a: Array[Short], fromIndex: Int, toIndex: Int, value: Short): Unit = native
  def fill(a: Array[Char], value: Char): Unit = native
  def fill(a: Array[Char], fromIndex: Int, toIndex: Int, value: Char): Unit = native
  def fill(a: Array[Byte], value: Byte): Unit = native
  def fill(a: Array[Byte], fromIndex: Int, toIndex: Int, value: Byte): Unit = native
  def fill(a: Array[Boolean], value: Boolean): Unit = native
  def fill(a: Array[Boolean], fromIndex: Int, toIndex: Int, value: Boolean): Unit = native
  def fill(a: Array[Float], value: Float): Unit = native
  def fill(a: Array[Float], fromIndex: Int, toIndex: Int, value: Float): Unit = native
  def fill(a: Array[Double], value: Double): Unit = native
  def fill(a: Array[Double], fromIndex: Int, toIndex: Int, value: Double): Unit = native
  def fill(a: Array[AnyRef], value: AnyRef): Unit = native
  def fill(a: Array[AnyRef], fromIndex: Int, toIndex: Int, value: AnyRef): Unit = native

  def copyOf[T <: AnyRef](original: Array[T], newLength: Int): Array[T] = native
  def copyOf[T <: AnyRef, U <: AnyRef](original: Array[U], newLength: Int, newType: Class[? <: Array[T]]): Array[T] =
    native
  def copyOf(original: Array[Byte], newLength: Int): Array[Byte] = native
  def copyOf(original: Array[Short], newLength: Int): Array[Short] = native
  def copyOf(original: Array[Int], newLength: Int): Array[Int] = native
  def copyOf(original: Array[Long], newLength: Int): Array[Long] = native
  def copyOf(original: Array[Char], newLength: Int): Array[Char] = native
  def copyOf(original: Array[Float], newLength: Int): Array[Float] = native
  def copyOf(original: Array[Double], newLength: Int): Array[Double] = native
  def copyOf(original: Array[Boolean], newLength: Int): Array[Boolean] = native

  def copyOfRange[T <: AnyRef](original: Array[T], from: Int, to: Int): Array[T] = native
  def copyOfRange[T <: AnyRef, U <: AnyRef](original: Array[U], from: Int, to: Int,
      newType: Class[? <: Array[T]]): Array[T] = native
  def copyOfRange(original: Array[Byte], start: Int, end: Int): Array[Byte] = native
  def copyOfRange(original: Array[Short], start: Int, end: Int): Array[Short] = native
  def copyOfRange(original: Array[Int], start: Int, end: Int): Array[Int] = native
  def copyOfRange(original: Array[Long], start: Int, end: Int): Array[Long] = native
  def copyOfRange(original: Array[Char], start: Int, end: Int): Array[Char] = native
  def copyOfRange(original: Array[Float], start: Int, end: Int): Array[Float] = native
  def copyOfRange(original: Array[Double], start: Int, end: Int): Array[Double] = native
  def copyOfRange(original: Array[Boolean], start: Int, end: Int): Array[Boolean] = native

  def toString(a: Array[Long]): String = native
  def toString(a: Array[Int]): String = native
  def toString(a: Array[Short]): String = native
  def toString(a: Array[Char]): String = native
  def toString(a: Array[Byte]): String = native
  def toString(a: Array[Boolean]): String = native
  def toString(a: Array[Float]): String = native
  def toString(a: Array[Double]): String = native

  // ---- Object arrays --------------------------------------------------------------------------

  private val naturalOrder: Comparator[AnyRef] = (x, y) => x.asInstanceOf[Comparable[AnyRef]].compareTo(y)

  private def orNatural(c: Comparator[?]): Comparator[AnyRef] =
    if c == null then naturalOrder else c.asInstanceOf[Comparator[AnyRef]]

  def sort(a: Array[AnyRef]): Unit = stableMergeSort(a, 0, a.length, naturalOrder)

  def sort(a: Array[AnyRef], fromIndex: Int, toIndex: Int): Unit =
    checkRangeIndices(a.length, fromIndex, toIndex)
    stableMergeSort(a, fromIndex, toIndex, naturalOrder)

  def sort[T <: AnyRef](array: Array[T], comparator: Comparator[? >: T]): Unit =
    stableMergeSort(array.asInstanceOf[Array[AnyRef]], 0, array.length, orNatural(comparator))

  def sort[T <: AnyRef](array: Array[T], fromIndex: Int, toIndex: Int, comparator: Comparator[? >: T]): Unit =
    checkRangeIndices(array.length, fromIndex, toIndex)
    stableMergeSort(array.asInstanceOf[Array[AnyRef]], fromIndex, toIndex, orNatural(comparator))

  private final val inPlaceSortThreshold = 16

  /** Sorts `a` with merge sort and insertion sort (stable, as the JDK's sort of objects). */
  private def stableMergeSort(a: Array[AnyRef], start: Int, end: Int, cmp: Comparator[AnyRef]): Unit =
    if end - start > inPlaceSortThreshold then stableSplitMerge(a, new Array(a.length), start, end, cmp)
    else insertionSort(a, start, end, cmp)

  private def stableSplitMerge(a: Array[AnyRef], temp: Array[AnyRef], start: Int, end: Int,
      cmp: Comparator[AnyRef]): Unit =
    val length = end - start
    if length > inPlaceSortThreshold then
      val middle = start + (length / 2)
      stableSplitMerge(a, temp, start, middle, cmp)
      stableSplitMerge(a, temp, middle, end, cmp)
      stableMerge(a, temp, start, middle, end, cmp)
      System.arraycopy(temp, start, a, start, length)
    else insertionSort(a, start, end, cmp)

  private def stableMerge(a: Array[AnyRef], temp: Array[AnyRef], start: Int, middle: Int, end: Int,
      cmp: Comparator[AnyRef]): Unit =
    var outIndex = start
    var leftInIndex = start
    var rightInIndex = middle
    while outIndex < end do
      if leftInIndex < middle && (rightInIndex >= end || cmp.compare(a(leftInIndex), a(rightInIndex)) <= 0) then
        temp(outIndex) = a(leftInIndex)
        leftInIndex += 1
      else
        temp(outIndex) = a(rightInIndex)
        rightInIndex += 1
      outIndex += 1

  private def insertionSort(a: Array[AnyRef], start: Int, end: Int, cmp: Comparator[AnyRef]): Unit =
    val n = end - start
    if n >= 2 then
      if cmp.compare(a(start), a(start + 1)) > 0 then
        val temp = a(start)
        a(start) = a(start + 1)
        a(start + 1) = temp
      var m = 2
      while m < n do
        // Speed up the already-sorted case by checking the last element first.
        val next = a(start + m)
        if cmp.compare(next, a(start + m - 1)) < 0 then
          var iA = start
          var iB = start + m - 1
          while iB - iA > 1 do
            val ix = (iA + iB) >>> 1
            if cmp.compare(next, a(ix)) < 0 then iB = ix else iA = ix
          val ix = iA + (if cmp.compare(next, a(iA)) < 0 then 0 else 1)
          var i = start + m
          while i > ix do
            a(i) = a(i - 1)
            i -= 1
          a(ix) = next
        m += 1

  def binarySearch(a: Array[AnyRef], key: AnyRef): Int = binarySearchImpl(a, 0, a.length, key, naturalOrder)

  def binarySearch(a: Array[AnyRef], startIndex: Int, endIndex: Int, key: AnyRef): Int =
    checkRangeIndices(a.length, startIndex, endIndex)
    binarySearchImpl(a, startIndex, endIndex, key, naturalOrder)

  def binarySearch[T <: AnyRef](a: Array[T], key: T, c: Comparator[? >: T]): Int =
    binarySearchImpl(a.asInstanceOf[Array[AnyRef]], 0, a.length, key, orNatural(c))

  def binarySearch[T <: AnyRef](a: Array[T], startIndex: Int, endIndex: Int, key: T, c: Comparator[? >: T]): Int =
    checkRangeIndices(a.length, startIndex, endIndex)
    binarySearchImpl(a.asInstanceOf[Array[AnyRef]], startIndex, endIndex, key, orNatural(c))

  /** The JDK's algorithm: the same probes, so the same index among equal elements. */
  private def binarySearchImpl(a: Array[AnyRef], startIndex: Int, endIndex: Int, key: AnyRef,
      cmp: Comparator[AnyRef]): Int =
    var low = startIndex
    var high = endIndex - 1
    while low <= high do
      val mid = (low + high) >>> 1
      val c = cmp.compare(a(mid), key)
      if c < 0 then low = mid + 1
      else if c > 0 then high = mid - 1
      else return mid
    -(low + 1)

  def asList[T <: AnyRef](a: Array[T]): List[T] =
    new AbstractList[T] with RandomAccess:
      def size(): Int = a.length
      def get(index: Int): T = a(index)
      override def set(index: Int, element: T): T =
        val ret = a(index)
        a(index) = element
        ret

  def deepHashCode(a: Array[AnyRef]): Int =
    if a == null then 0
    else
      var acc = 1
      var i = 0
      while i < a.length do
        val elemHash = a(i) match
          case elem: Array[AnyRef]  => deepHashCode(elem)
          case elem: Array[Long]    => hashCode(elem)
          case elem: Array[Int]     => hashCode(elem)
          case elem: Array[Short]   => hashCode(elem)
          case elem: Array[Char]    => hashCode(elem)
          case elem: Array[Byte]    => hashCode(elem)
          case elem: Array[Boolean] => hashCode(elem)
          case elem: Array[Float]   => hashCode(elem)
          case elem: Array[Double]  => hashCode(elem)
          case elem                 => Objects.hashCode(elem)
        acc = 31 * acc + elemHash
        i += 1
      acc

  def deepEquals(a1: Array[AnyRef], a2: Array[AnyRef]): Boolean =
    if a1 eq a2 then true
    else if a1 == null || a2 == null || a1.length != a2.length then false
    else
      var i = 0
      while i < a1.length && Objects.deepEquals(a1(i), a2(i)) do i += 1
      i == a1.length

  def toString(a: Array[AnyRef]): String =
    if a == null then "null"
    else
      val sb = new java.lang.StringBuilder("[")
      var i = 0
      while i < a.length do
        if i != 0 then sb.append(", ")
        sb.append(a(i))
        i += 1
      sb.append(']').toString

  def deepToString(a: Array[AnyRef]): String =
    deepToStringImpl(a, new java.util.HashSet[AsRef])

  private def deepToStringImpl(a: Array[AnyRef], branch: java.util.Set[AsRef]): String =
    def valueToString(e: AnyRef): String =
      if e == null then "null"
      else
        e match
          case e: Array[AnyRef] =>
            branch.add(new AsRef(a))
            deepToStringImpl(e, branch)
          case e: Array[Long]    => toString(e)
          case e: Array[Int]     => toString(e)
          case e: Array[Short]   => toString(e)
          case e: Array[Byte]    => toString(e)
          case e: Array[Char]    => toString(e)
          case e: Array[Boolean] => toString(e)
          case e: Array[Float]   => toString(e)
          case e: Array[Double]  => toString(e)
          case _                 => String.valueOf(e)
    if a == null then "null"
    else if branch.contains(new AsRef(a)) then "[...]"
    else
      val sb = new java.lang.StringBuilder("[")
      var i = 0
      while i < a.length do
        if i != 0 then sb.append(", ")
        sb.append(valueToString(a(i)))
        i += 1
      sb.append(']').toString

  private def checkRangeIndices(length: Int, start: Int, end: Int): Unit =
    if start > end then throw new IllegalArgumentException("fromIndex(" + start + ") > toIndex(" + end + ")")
    if start < 0 then throw new ArrayIndexOutOfBoundsException("Array index out of range: " + start)
    if end > length then throw new ArrayIndexOutOfBoundsException("Array index out of range: " + end)

  private final class AsRef(val inner: AnyRef):
    override def hashCode(): Int = System.identityHashCode(inner)

    override def equals(obj: Any): Boolean = obj match
      case obj: AsRef => obj.inner eq inner
      case _          => false

  // ---- Scala Native additions -----------------------------------------------------------------

  // parallelPrefix(), parallelSetAll() and parallelSort() are sequential.

  def parallelPrefix(array: Array[Double], op: DoubleBinaryOperator): Unit =
    parallelPrefix(array, 0, array.length, op)

  def parallelPrefix(array: Array[Double], fromIndex: Int, toIndex: Int, op: DoubleBinaryOperator): Unit =
    checkRangeIndices(array.length, fromIndex, toIndex)
    var j = fromIndex + 1
    while j < toIndex do
      array(j) = op.applyAsDouble(array(j - 1), array(j))
      j += 1

  def parallelPrefix(array: Array[Int], op: IntBinaryOperator): Unit =
    parallelPrefix(array, 0, array.length, op)

  def parallelPrefix(array: Array[Int], fromIndex: Int, toIndex: Int, op: IntBinaryOperator): Unit =
    checkRangeIndices(array.length, fromIndex, toIndex)
    var j = fromIndex + 1
    while j < toIndex do
      array(j) = op.applyAsInt(array(j - 1), array(j))
      j += 1

  def parallelPrefix(array: Array[Long], op: LongBinaryOperator): Unit =
    parallelPrefix(array, 0, array.length, op)

  def parallelPrefix(array: Array[Long], fromIndex: Int, toIndex: Int, op: LongBinaryOperator): Unit =
    checkRangeIndices(array.length, fromIndex, toIndex)
    var j = fromIndex + 1
    while j < toIndex do
      array(j) = op.applyAsLong(array(j - 1), array(j))
      j += 1

  def parallelPrefix[T <: AnyRef](array: Array[T], op: BinaryOperator[T]): Unit =
    parallelPrefix[T](array, 0, array.length, op)

  def parallelPrefix[T <: AnyRef](array: Array[T], fromIndex: Int, toIndex: Int, op: BinaryOperator[T]): Unit =
    checkRangeIndices(array.length, fromIndex, toIndex)
    var j = fromIndex + 1
    while j < toIndex do
      array(j) = op.apply(array(j - 1), array(j))
      j += 1

  def parallelSetAll(array: Array[Double], generator: IntToDoubleFunction): Unit = setAll(array, generator)
  def parallelSetAll(array: Array[Int], generator: IntUnaryOperator): Unit = setAll(array, generator)
  def parallelSetAll(array: Array[Long], generator: IntToLongFunction): Unit = setAll(array, generator)
  def parallelSetAll[T <: AnyRef](array: Array[T], generator: IntFunction[? <: T]): Unit = setAll(array, generator)

  def parallelSort(a: Array[Byte]): Unit = sort(a)
  def parallelSort(a: Array[Byte], fromIndex: Int, toIndex: Int): Unit = sort(a, fromIndex, toIndex)
  def parallelSort(a: Array[Char]): Unit = sort(a)
  def parallelSort(a: Array[Char], fromIndex: Int, toIndex: Int): Unit = sort(a, fromIndex, toIndex)
  def parallelSort(a: Array[Double]): Unit = sort(a)
  def parallelSort(a: Array[Double], fromIndex: Int, toIndex: Int): Unit = sort(a, fromIndex, toIndex)
  def parallelSort(a: Array[Float]): Unit = sort(a)
  def parallelSort(a: Array[Float], fromIndex: Int, toIndex: Int): Unit = sort(a, fromIndex, toIndex)
  def parallelSort(a: Array[Int]): Unit = sort(a)
  def parallelSort(a: Array[Int], fromIndex: Int, toIndex: Int): Unit = sort(a, fromIndex, toIndex)
  def parallelSort(a: Array[Long]): Unit = sort(a)
  def parallelSort(a: Array[Long], fromIndex: Int, toIndex: Int): Unit = sort(a, fromIndex, toIndex)
  def parallelSort(a: Array[Short]): Unit = sort(a)
  def parallelSort(a: Array[Short], fromIndex: Int, toIndex: Int): Unit = sort(a, fromIndex, toIndex)

  def parallelSort[T <: Comparable[? <: AnyRef]](array: Array[T]): Unit = sort(array.asInstanceOf[Array[AnyRef]])

  def parallelSort[T <: AnyRef](array: Array[T], comparator: Comparator[? >: T]): Unit = sort[T](array, comparator)

  def parallelSort[T <: Comparable[? <: AnyRef]](array: Array[T], fromIndex: Int, toIndex: Int): Unit =
    sort(array.asInstanceOf[Array[AnyRef]], fromIndex, toIndex)

  def parallelSort[T <: AnyRef](array: Array[T], fromIndex: Int, toIndex: Int, comparator: Comparator[? >: T]): Unit =
    sort[T](array, fromIndex, toIndex, comparator)

  def setAll(array: Array[Double], generator: IntToDoubleFunction): Unit =
    var j = 0
    while j < array.length do
      array(j) = generator.applyAsDouble(j)
      j += 1

  def setAll(array: Array[Int], generator: IntUnaryOperator): Unit =
    var j = 0
    while j < array.length do
      array(j) = generator.applyAsInt(j)
      j += 1

  def setAll(array: Array[Long], generator: IntToLongFunction): Unit =
    var j = 0
    while j < array.length do
      array(j) = generator.applyAsLong(j)
      j += 1

  def setAll[T <: AnyRef](array: Array[T], generator: IntFunction[? <: T]): Unit =
    var j = 0
    while j < array.length do
      array(j) = generator.apply(j)
      j += 1

  private final val standardArraySpliteratorCharacteristics =
    Spliterator.SIZED | Spliterator.SUBSIZED | Spliterator.ORDERED | Spliterator.IMMUTABLE

  def spliterator(array: Array[Double]): Spliterator.OfDouble =
    spliterator(array, 0, array.length)

  def spliterator(array: Array[Double], startInclusive: Int, endExclusive: Int): Spliterator.OfDouble =
    Objects.requireNonNull(array)
    Spliterators.spliterator(array, startInclusive, endExclusive, standardArraySpliteratorCharacteristics)

  def spliterator(array: Array[Int]): Spliterator.OfInt =
    spliterator(array, 0, array.length)

  def spliterator(array: Array[Int], startInclusive: Int, endExclusive: Int): Spliterator.OfInt =
    Objects.requireNonNull(array)
    Spliterators.spliterator(array, startInclusive, endExclusive, standardArraySpliteratorCharacteristics)

  def spliterator(array: Array[Long]): Spliterator.OfLong =
    spliterator(array, 0, array.length)

  def spliterator(array: Array[Long], startInclusive: Int, endExclusive: Int): Spliterator.OfLong =
    Objects.requireNonNull(array)
    Spliterators.spliterator(array, startInclusive, endExclusive, standardArraySpliteratorCharacteristics)

  def spliterator[T](array: Array[AnyRef]): Spliterator[T] =
    spliterator(array, 0, array.length)

  def spliterator[T](array: Array[AnyRef], startInclusive: Int, endExclusive: Int): Spliterator[T] =
    Objects.requireNonNull(array)
    Spliterators.spliterator(array, startInclusive, endExclusive, standardArraySpliteratorCharacteristics)
