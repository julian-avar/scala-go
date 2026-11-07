package java.util.concurrent

import java.util.{AbstractList, Collection, Comparator, ConcurrentModificationException, Iterator, List, ListIterator,
  NoSuchElementException, Objects, RandomAccess}
import java.util.function.{Consumer, Predicate, UnaryOperator}

/** java.util.concurrent.CopyOnWriteArrayList, as in the JDK: the elements are an immutable array in
 *  a volatile field, replaced by a modified copy under a lock. Reads and iteration need no
 *  locking, and iterators traverse the array of their creation (they never throw
 *  ConcurrentModificationException and do not support modification).
 */
@SerialVersionUID(8673264195747942595L)
class CopyOnWriteArrayList[E] private (initial: Array[AnyRef], ignored: Unit)
    extends AbstractList[E] with RandomAccess with Cloneable with java.io.Serializable:
  def this() = this(new Array[AnyRef](0), ())
  def this(c: Collection[? <: E]) = this(c.toArray(), ())
  def this(toCopyIn: Array[E & AnyRef]) =
    this(java.util.Arrays.copyOf[AnyRef](toCopyIn.asInstanceOf[Array[AnyRef]], toCopyIn.length), ())

  @volatile private var array: Array[AnyRef] = initial
  /** Writers hold this lock. */
  private val lock = new Object

  private[concurrent] final def getArray(): Array[AnyRef] = array
  private[concurrent] final def setArray(a: Array[AnyRef]): Unit = array = a
  private[concurrent] final def writeLock: Object = lock

  private def elementAt(a: Array[AnyRef], index: Int): E = a(index).asInstanceOf[E]

  def size(): Int = array.length
  override def isEmpty(): Boolean = array.length == 0

  private def indexOfRange(o: Any, es: Array[AnyRef], from: Int, to: Int): Int =
    var i = from
    while i < to do
      if Objects.equals(o, es(i)) then return i
      i += 1
    -1

  private def lastIndexOfRange(o: Any, es: Array[AnyRef], from: Int, to: Int): Int =
    var i = to - 1
    while i >= from do
      if Objects.equals(o, es(i)) then return i
      i -= 1
    -1

  override def contains(o: Any): Boolean = indexOf(o) >= 0
  override def indexOf(o: Any): Int =
    val es = array
    indexOfRange(o, es, 0, es.length)
  def indexOf(e: E, index: Int): Int =
    val es = array
    indexOfRange(e, es, index, es.length)
  override def lastIndexOf(o: Any): Int =
    val es = array
    lastIndexOfRange(o, es, 0, es.length)
  def lastIndexOf(e: E, index: Int): Int =
    val es = array
    lastIndexOfRange(e, es, 0, index + 1)

  override def clone(): AnyRef = new CopyOnWriteArrayList[E](array, ())

  override def toArray(): Array[AnyRef] = array.clone()
  override def toArray[T <: AnyRef](a: Array[T]): Array[T] =
    val es = array
    if a.length < es.length then java.util.Arrays.copyOf[AnyRef](es, es.length).asInstanceOf[Array[T]]
    else
      System.arraycopy(es, 0, a, 0, es.length)
      if a.length > es.length then a(es.length) = null.asInstanceOf[T]
      a

  def get(index: Int): E =
    val es = array
    if index < 0 || index >= es.length then throw outOfBounds(index, es.length)
    elementAt(es, index)

  override def getFirst(): E =
    val es = array
    if es.length == 0 then throw new NoSuchElementException() else elementAt(es, 0)
  override def getLast(): E =
    val es = array
    if es.length == 0 then throw new NoSuchElementException() else elementAt(es, es.length - 1)

  private def outOfBounds(index: Int, size: Int) =
    new IndexOutOfBoundsException("Index: " + index + ", Size: " + size)

  override def set(index: Int, element: E): E = lock.synchronized {
    val es = array
    if index < 0 || index >= es.length then throw outOfBounds(index, es.length)
    val old = elementAt(es, index)
    if old.asInstanceOf[AnyRef] ne element.asInstanceOf[AnyRef] then
      val copy = es.clone()
      copy(index) = element.asInstanceOf[AnyRef]
      array = copy
    old
  }

  override def add(e: E): Boolean = lock.synchronized {
    val es = array
    val copy = java.util.Arrays.copyOf[AnyRef](es, es.length + 1)
    copy(es.length) = e.asInstanceOf[AnyRef]
    array = copy
    true
  }

  override def add(index: Int, element: E): Unit = lock.synchronized {
    val es = array
    if index < 0 || index > es.length then throw outOfBounds(index, es.length)
    val copy = new Array[AnyRef](es.length + 1)
    System.arraycopy(es, 0, copy, 0, index)
    System.arraycopy(es, index, copy, index + 1, es.length - index)
    copy(index) = element.asInstanceOf[AnyRef]
    array = copy
  }

  override def addFirst(e: E): Unit = add(0, e)
  override def addLast(e: E): Unit = add(e)

  override def remove(index: Int): E = lock.synchronized {
    val es = array
    if index < 0 || index >= es.length then throw outOfBounds(index, es.length)
    val old = elementAt(es, index)
    val copy = new Array[AnyRef](es.length - 1)
    System.arraycopy(es, 0, copy, 0, index)
    System.arraycopy(es, index + 1, copy, index, es.length - index - 1)
    array = copy
    old
  }

  override def removeFirst(): E = lock.synchronized {
    if array.length == 0 then throw new NoSuchElementException() else remove(0)
  }
  override def removeLast(): E = lock.synchronized {
    if array.length == 0 then throw new NoSuchElementException() else remove(array.length - 1)
  }

  override def remove(o: Any): Boolean = lock.synchronized {
    val i = indexOf(o)
    if i < 0 then false
    else
      remove(i)
      true
  }

  override protected[util] def removeRange(fromIndex: Int, toIndex: Int): Unit = lock.synchronized {
    val es = array
    if fromIndex < 0 || toIndex > es.length || toIndex < fromIndex then throw new IndexOutOfBoundsException()
    val copy = new Array[AnyRef](es.length - (toIndex - fromIndex))
    System.arraycopy(es, 0, copy, 0, fromIndex)
    System.arraycopy(es, toIndex, copy, fromIndex, es.length - toIndex)
    array = copy
  }

  /** Appends `e` if it is not already present; whether it was added. */
  def addIfAbsent(e: E): Boolean = lock.synchronized {
    if indexOf(e) >= 0 then false else add(e)
  }

  override def containsAll(c: Collection[?]): Boolean =
    val es = array
    val it = c.iterator()
    while it.hasNext() do
      if indexOfRange(it.next(), es, 0, es.length) < 0 then return false
    true

  override def removeAll(c: Collection[?]): Boolean =
    Objects.requireNonNull(c)
    removeIf(e => c.contains(e))

  override def retainAll(c: Collection[?]): Boolean =
    Objects.requireNonNull(c)
    removeIf(e => !c.contains(e))

  /** Appends the elements of `c` not already present, in order; how many were added. */
  def addAllAbsent(c: Collection[? <: E]): Int =
    val cs = c.toArray()
    lock.synchronized {
      val es = array
      val added = new java.util.ArrayList[AnyRef]()
      for e <- cs do
        if indexOfRange(e, es, 0, es.length) < 0 && !added.contains(e) then added.add(e)
      if added.isEmpty() then 0
      else
        val copy = java.util.Arrays.copyOf[AnyRef](es, es.length + added.size())
        for i <- 0 until added.size() do copy(es.length + i) = added.get(i)
        array = copy
        added.size()
    }

  override def clear(): Unit = lock.synchronized { array = new Array[AnyRef](0) }

  override def addAll(c: Collection[? <: E]): Boolean =
    val cs = c.toArray()
    if cs.length == 0 then false
    else
      lock.synchronized {
        val es = array
        val copy = java.util.Arrays.copyOf[AnyRef](es, es.length + cs.length)
        System.arraycopy(cs, 0, copy, es.length, cs.length)
        array = copy
      }
      true

  override def addAll(index: Int, c: Collection[? <: E]): Boolean =
    val cs = c.toArray()
    lock.synchronized {
      val es = array
      if index < 0 || index > es.length then throw outOfBounds(index, es.length)
      if cs.length == 0 then false
      else
        val copy = new Array[AnyRef](es.length + cs.length)
        System.arraycopy(es, 0, copy, 0, index)
        System.arraycopy(cs, 0, copy, index, cs.length)
        System.arraycopy(es, index, copy, index + cs.length, es.length - index)
        array = copy
        true
    }

  override def forEach(action: Consumer[? >: E]): Unit =
    Objects.requireNonNull(action)
    for e <- array do action.accept(e.asInstanceOf[E])

  override def removeIf(filter: Predicate[? >: E]): Boolean =
    Objects.requireNonNull(filter)
    lock.synchronized {
      val es = array
      val kept = es.filterNot(e => filter.test(e.asInstanceOf[E]))
      if kept.length == es.length then false
      else
        array = kept
        true
    }

  override def replaceAll(operator: UnaryOperator[E]): Unit =
    Objects.requireNonNull(operator)
    lock.synchronized {
      array = array.map(e => operator.apply(e.asInstanceOf[E]).asInstanceOf[AnyRef])
    }

  override def sort(c: Comparator[? >: E]): Unit = lock.synchronized {
    val copy = array.clone()
    java.util.Arrays.sort(copy, c.asInstanceOf[Comparator[AnyRef]])
    array = copy
  }

  override def toString(): String = java.util.Arrays.toString(array)

  override def equals(o: Any): Boolean =
    if o.asInstanceOf[AnyRef] eq this then true
    else
      o match
        case list: List[?] =>
          val es = array
          val it = list.iterator()
          var i = 0
          while i < es.length do
            if !it.hasNext() || !Objects.equals(es(i), it.next()) then return false
            i += 1
          !it.hasNext()
        case _ => false

  override def hashCode(): Int =
    var h = 1
    for e <- array do h = 31 * h + (if e == null then 0 else e.hashCode())
    h

  override def iterator(): Iterator[E] = new CopyOnWriteArrayList.Snapshot[E](array, 0)
  override def listIterator(): ListIterator[E] = new CopyOnWriteArrayList.Snapshot[E](array, 0)
  override def listIterator(index: Int): ListIterator[E] =
    val es = array
    if index < 0 || index > es.length then throw outOfBounds(index, es.length)
    new CopyOnWriteArrayList.Snapshot[E](es, index)

  /** A view of a range, valid while the list is only modified through it (as in the JDK). */
  override def subList(fromIndex: Int, toIndex: Int): List[E] = lock.synchronized {
    val es = array
    if fromIndex < 0 || toIndex > es.length || fromIndex > toIndex then throw new IndexOutOfBoundsException()
    new CopyOnWriteArrayList.SubList[E](this, fromIndex, toIndex)
  }

object CopyOnWriteArrayList:
  /** An iterator over a snapshot of the array. */
  private final class Snapshot[E](es: Array[AnyRef], start: Int) extends ListIterator[E]:
    private var cursor = start
    def hasNext(): Boolean = cursor < es.length
    def hasPrevious(): Boolean = cursor > 0
    def next(): E =
      if cursor >= es.length then throw new NoSuchElementException()
      cursor += 1
      es(cursor - 1).asInstanceOf[E]
    def previous(): E =
      if cursor <= 0 then throw new NoSuchElementException()
      cursor -= 1
      es(cursor).asInstanceOf[E]
    def nextIndex(): Int = cursor
    def previousIndex(): Int = cursor - 1
    override def remove(): Unit = throw new UnsupportedOperationException()
    def set(e: E): Unit = throw new UnsupportedOperationException()
    def add(e: E): Unit = throw new UnsupportedOperationException()
    override def forEachRemaining(action: Consumer[? >: E]): Unit =
      while cursor < es.length do
        action.accept(es(cursor).asInstanceOf[E])
        cursor += 1

  /** A range of `list`, which throws ConcurrentModificationException once the list was modified
   *  other than through it.
   */
  private final class SubList[E](list: CopyOnWriteArrayList[E], offset: Int, var length: Int)
      extends AbstractList[E] with RandomAccess:
    length -= offset
    private var expected = list.getArray()

    private def checked[T](body: => T): T = list.writeLock.synchronized {
      if list.getArray() ne expected then throw new ConcurrentModificationException()
      val result = body
      expected = list.getArray()
      result
    }

    private def checkIndex(index: Int, size: Int): Unit =
      if index < 0 || index >= size then throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size)

    def size(): Int = checked(length)
    def get(index: Int): E = checked {
      checkIndex(index, length)
      list.get(offset + index)
    }
    override def set(index: Int, element: E): E = checked {
      checkIndex(index, length)
      list.set(offset + index, element)
    }
    override def add(index: Int, element: E): Unit = checked {
      if index < 0 || index > length then throw new IndexOutOfBoundsException()
      list.add(offset + index, element)
      length += 1
    }
    override def remove(index: Int): E = checked {
      checkIndex(index, length)
      val old = list.remove(offset + index)
      length -= 1
      old
    }
    override def clear(): Unit = checked {
      list.removeRange(offset, offset + length)
      length = 0
    }
    override def iterator(): Iterator[E] = listIterator(0)
    override def listIterator(index: Int): ListIterator[E] = checked {
      if index < 0 || index > length then throw new IndexOutOfBoundsException()
      val es = java.util.Arrays.copyOfRange[AnyRef](expected, offset, offset + length)
      new Snapshot[E](es, index)
    }
    override def subList(fromIndex: Int, toIndex: Int): List[E] = checked {
      if fromIndex < 0 || toIndex > length || fromIndex > toIndex then throw new IndexOutOfBoundsException()
      new SubList[E](list, offset + fromIndex, offset + toIndex)
    }
