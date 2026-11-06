package java.util

import scala.annotation.static

/** Sorting of object arrays (stable merge sort, like the JDK's TimSort). Operations on primitive
 *  arrays and copying are implemented natively by the linker.
 */
final class Arrays private ()

object Arrays:
  private def naturalOrder: Comparator[Object] = new Comparator[Object]:
    def compare(o1: Object, o2: Object): Int = o1.asInstanceOf[Comparable[Object]].compareTo(o2)

  // Signatures use Array[AnyRef]: a generic Array[T] would erase to Object, not Object[].
  @static def sort(a: Array[AnyRef]): Unit = sort(a, 0, a.length, naturalOrder)
  @static def sort(a: Array[AnyRef], fromIndex: Int, toIndex: Int): Unit = sort(a, fromIndex, toIndex, naturalOrder)
  @static def sort(a: Array[AnyRef], c: Comparator[?]): Unit = sort(a, 0, a.length, c)

  @static def sort(a: Array[AnyRef], fromIndex: Int, toIndex: Int, c: Comparator[?]): Unit =
    val cmp = (if c == null then naturalOrder else c).asInstanceOf[Comparator[Object]]
    val arr = a
    if fromIndex < 0 || toIndex > arr.length || fromIndex > toIndex then
      throw new ArrayIndexOutOfBoundsException("Array index out of range: " + toIndex)
    val tmp = new Array[Object](toIndex - fromIndex)
    mergeSort(arr, tmp, fromIndex, toIndex, cmp)

  private def mergeSort(a: Array[Object], tmp: Array[Object], from: Int, to: Int, cmp: Comparator[Object]): Unit =
    if to - from <= 16 then
      // Insertion sort for short runs.
      var i = from + 1
      while i < to do
        val x = a(i)
        var j = i - 1
        while j >= from && cmp.compare(a(j), x) > 0 do
          a(j + 1) = a(j)
          j -= 1
        a(j + 1) = x
        i += 1
    else
      val mid = (from + to) >>> 1
      mergeSort(a, tmp, from, mid, cmp)
      mergeSort(a, tmp, mid, to, cmp)
      if cmp.compare(a(mid - 1), a(mid)) > 0 then
        System.arraycopy(a, from, tmp, 0, to - from)
        var i = 0
        var j = mid - from
        var k = from
        val leftEnd = mid - from
        val rightEnd = to - from
        while k < to do
          if j >= rightEnd || (i < leftEnd && cmp.compare(tmp(i), tmp(j)) <= 0) then
            a(k) = tmp(i)
            i += 1
          else
            a(k) = tmp(j)
            j += 1
          k += 1
