package java.util

import java.io.Serializable
import java.util.function.{Consumer, Predicate, UnaryOperator}

/** A resizable array, with the JDK's behavior: elements in an `Array[AnyRef]` grown by half its
 *  length, fail-fast iterators, and `modCount` maintained for the inherited sublists.
 */
class ArrayList[E] private (private var elems: Array[AnyRef], private var _size: Int)
    extends AbstractList[E]
    with List[E]
    with RandomAccess
    with Cloneable
    with Serializable:

  def this(initialCapacity: Int) =
    this(
      if initialCapacity < 0 then throw new IllegalArgumentException("Illegal Capacity: " + initialCapacity)
      else new Array[AnyRef](initialCapacity),
      0
    )

  def this() = this(10)

  def this(c: Collection[? <: E]) =
    this(ArrayList.toArrayOf(c), 0)
    _size = elems.length

  def trimToSize(): Unit =
    modCount += 1
    if _size < elems.length then elems = Arrays.copyOf(elems, _size)

  def ensureCapacity(minCapacity: Int): Unit =
    if minCapacity > elems.length then
      modCount += 1
      grow(minCapacity)

  /** Grows to at least `minCapacity` (and by at least half the current capacity). */
  private def grow(minCapacity: Int): Unit =
    val old = elems.length
    val preferred = old + Math.max(minCapacity - old, old >> 1)
    val newCapacity = if preferred > 0 then Math.max(preferred, 10) else Math.max(minCapacity, Integer.MAX_VALUE - 8)
    elems = Arrays.copyOf(elems, newCapacity)

  def size(): Int = _size

  override def isEmpty(): Boolean = _size == 0

  override def contains(o: Any): Boolean = indexOf(o) >= 0

  override def indexOf(o: Any): Int =
    var i = 0
    if o == null then
      while i < _size do
        if elems(i) == null then return i
        i += 1
    else
      while i < _size do
        if o.equals(elems(i)) then return i
        i += 1
    -1

  override def lastIndexOf(o: Any): Int =
    var i = _size - 1
    if o == null then
      while i >= 0 do
        if elems(i) == null then return i
        i -= 1
    else
      while i >= 0 do
        if o.equals(elems(i)) then return i
        i -= 1
    -1

  /** A shallow copy. */
  override def clone(): AnyRef = new ArrayList[E](Arrays.copyOf(elems, _size), _size)

  override def toArray(): Array[AnyRef] = Arrays.copyOf(elems, _size)

  override def toArray[T <: AnyRef](a: Array[T]): Array[T] =
    if a.length < _size then Arrays.copyOf(elems, _size).asInstanceOf[Array[T]]
    else
      System.arraycopy(elems, 0, a, 0, _size)
      if a.length > _size then a(_size) = null.asInstanceOf[T]
      a

  def get(index: Int): E =
    AbstractList.checkElementIndex(index, _size)
    elems(index).asInstanceOf[E]

  override def getFirst(): E =
    if _size == 0 then throw new NoSuchElementException()
    elems(0).asInstanceOf[E]

  override def getLast(): E =
    if _size == 0 then throw new NoSuchElementException()
    elems(_size - 1).asInstanceOf[E]

  override def set(index: Int, element: E): E =
    AbstractList.checkElementIndex(index, _size)
    val old = elems(index)
    elems(index) = element.asInstanceOf[AnyRef]
    old.asInstanceOf[E]

  override def add(e: E): Boolean =
    modCount += 1
    if _size == elems.length then grow(_size + 1)
    elems(_size) = e.asInstanceOf[AnyRef]
    _size += 1
    true

  override def add(index: Int, element: E): Unit =
    AbstractList.checkPositionIndex(index, _size)
    modCount += 1
    if _size == elems.length then grow(_size + 1)
    System.arraycopy(elems, index, elems, index + 1, _size - index)
    elems(index) = element.asInstanceOf[AnyRef]
    _size += 1

  override def addFirst(element: E): Unit = add(0, element)

  override def addLast(element: E): Unit = add(element)

  override def remove(index: Int): E =
    AbstractList.checkElementIndex(index, _size)
    val old = elems(index)
    fastRemove(index)
    old.asInstanceOf[E]

  override def removeFirst(): E =
    if _size == 0 then throw new NoSuchElementException()
    remove(0)

  override def removeLast(): E =
    if _size == 0 then throw new NoSuchElementException()
    remove(_size - 1)

  override def remove(o: Any): Boolean =
    val i = indexOf(o)
    if i < 0 then false
    else
      fastRemove(i)
      true

  private def fastRemove(i: Int): Unit =
    modCount += 1
    val newSize = _size - 1
    if newSize > i then System.arraycopy(elems, i + 1, elems, i, newSize - i)
    _size = newSize
    elems(newSize) = null

  override def clear(): Unit =
    modCount += 1
    Arrays.fill(elems, 0, _size, null)
    _size = 0

  override def addAll(c: Collection[? <: E]): Boolean =
    val a = c.toArray()
    modCount += 1
    val n = a.length
    if n == 0 then false
    else
      if n > elems.length - _size then grow(_size + n)
      System.arraycopy(a, 0, elems, _size, n)
      _size += n
      true

  override def addAll(index: Int, c: Collection[? <: E]): Boolean =
    AbstractList.checkPositionIndex(index, _size)
    val a = c.toArray()
    modCount += 1
    val n = a.length
    if n == 0 then false
    else
      if n > elems.length - _size then grow(_size + n)
      System.arraycopy(elems, index, elems, index + n, _size - index)
      System.arraycopy(a, 0, elems, index, n)
      _size += n
      true

  override protected[util] def removeRange(fromIndex: Int, toIndex: Int): Unit =
    if fromIndex > toIndex then
      throw new IndexOutOfBoundsException("From Index: " + fromIndex + " > To Index: " + toIndex)
    modCount += 1
    System.arraycopy(elems, toIndex, elems, fromIndex, _size - toIndex)
    val newSize = _size - (toIndex - fromIndex)
    Arrays.fill(elems, newSize, _size, null)
    _size = newSize

  override def removeAll(c: Collection[?]): Boolean =
    Objects.requireNonNull(c)
    removeWhere(e => c.contains(e))

  override def retainAll(c: Collection[?]): Boolean =
    Objects.requireNonNull(c)
    removeWhere(e => !c.contains(e))

  override def removeIf(filter: Predicate[? >: E]): Boolean =
    Objects.requireNonNull(filter)
    val expected = modCount
    val removed = removeWhere(e => filter.test(e.asInstanceOf[E]))
    if removed && modCount != expected + 1 then throw new ConcurrentModificationException()
    removed

  /** Removes the elements matching `p`, keeping the order of the others. */
  private def removeWhere(p: Predicate[AnyRef]): Boolean =
    val expected = modCount
    var w = 0
    var r = 0
    while r < _size do
      val e = elems(r)
      if !p.test(e) then
        elems(w) = e
        w += 1
      r += 1
    if modCount != expected then throw new ConcurrentModificationException()
    if w == _size then false
    else
      Arrays.fill(elems, w, _size, null)
      modCount += 1
      _size = w
      true

  override def forEach(action: Consumer[? >: E]): Unit =
    Objects.requireNonNull(action)
    val expected = modCount
    var i = 0
    while i < _size && modCount == expected do
      action.accept(elems(i).asInstanceOf[E])
      i += 1
    if modCount != expected then throw new ConcurrentModificationException()

  override def replaceAll(operator: UnaryOperator[E]): Unit =
    Objects.requireNonNull(operator)
    val expected = modCount
    var i = 0
    while i < _size && modCount == expected do
      elems(i) = operator.apply(elems(i).asInstanceOf[E]).asInstanceOf[AnyRef]
      i += 1
    if modCount != expected then throw new ConcurrentModificationException()
    modCount += 1

  override def sort(c: Comparator[? >: E]): Unit =
    val expected = modCount
    Arrays.sort(elems, 0, _size, c.asInstanceOf[Comparator[AnyRef]])
    if modCount != expected then throw new ConcurrentModificationException()
    modCount += 1

  override def equals(o: Any): Boolean = o match
    case o: ArrayList[?] if o ne this =>
      val expected = modCount
      val result = _size == o._size && {
        var i = 0
        while i < _size && Objects.equals(elems(i), o.elems(i)) do i += 1
        i == _size
      }
      if modCount != expected then throw new ConcurrentModificationException()
      result
    case _ => super.equals(o)

  override def hashCode(): Int =
    val expected = modCount
    var hash = 1
    var i = 0
    while i < _size do
      hash = 31 * hash + Objects.hashCode(elems(i))
      i += 1
    if modCount != expected then throw new ConcurrentModificationException()
    hash

  override def iterator(): Iterator[E] = new Itr

  override def listIterator(index: Int): ListIterator[E] =
    AbstractList.checkPositionIndex(index, _size)
    new ListItr(index)

  override def spliterator(): Spliterator[E] = new Splitr

  /** As the JDK's: late-binding (the size and `modCount` are read on first use) and fail-fast. */
  private final class Splitr extends Spliterator[E]:
    private var cursor = 0
    private var fence = -1
    private var expectedModCount = 0

    private def getFence(): Int =
      if fence < 0 then
        fence = _size
        expectedModCount = modCount
      fence

    def tryAdvance(action: Consumer[? >: E]): Boolean =
      Objects.requireNonNull(action)
      val hi = getFence()
      val i = cursor
      if i < hi then
        cursor = i + 1
        action.accept(elems(i).asInstanceOf[E])
        if modCount != expectedModCount then throw new ConcurrentModificationException()
        true
      else false

    override def forEachRemaining(action: Consumer[? >: E]): Unit =
      Objects.requireNonNull(action)
      val hi = getFence()
      val a = elems
      var i = cursor
      cursor = hi
      while i < hi do
        action.accept(a(i).asInstanceOf[E])
        i += 1
      if modCount != expectedModCount then throw new ConcurrentModificationException()

    def trySplit(): Spliterator[E] = null

    def estimateSize(): Long = getFence() - cursor

    def characteristics(): Int = Spliterator.ORDERED | Spliterator.SIZED | Spliterator.SUBSIZED

  /** The iterator, on the array directly. */
  private class Itr extends Iterator[E]:
    protected var cursor = 0
    protected var lastRet = -1
    protected var expectedModCount = modCount

    def hasNext(): Boolean = cursor != _size

    def next(): E =
      checkForComodification()
      val i = cursor
      if i >= _size then throw new NoSuchElementException()
      if i >= elems.length then throw new ConcurrentModificationException()
      cursor = i + 1
      lastRet = i
      elems(i).asInstanceOf[E]

    override def remove(): Unit =
      if lastRet < 0 then throw new IllegalStateException()
      checkForComodification()
      ArrayList.this.remove(lastRet)
      cursor = lastRet
      lastRet = -1
      expectedModCount = modCount

    override def forEachRemaining(action: Consumer[? >: E]): Unit =
      Objects.requireNonNull(action)
      var i = cursor
      while i < _size && modCount == expectedModCount do
        action.accept(elems(i).asInstanceOf[E])
        i += 1
      cursor = i
      lastRet = i - 1
      checkForComodification()

    protected final def checkForComodification(): Unit =
      if modCount != expectedModCount then throw new ConcurrentModificationException()

  private class ListItr(index: Int) extends Itr with ListIterator[E]:
    cursor = index

    def hasPrevious(): Boolean = cursor != 0

    def nextIndex(): Int = cursor

    def previousIndex(): Int = cursor - 1

    def previous(): E =
      checkForComodification()
      val i = cursor - 1
      if i < 0 then throw new NoSuchElementException()
      if i >= elems.length then throw new ConcurrentModificationException()
      cursor = i
      lastRet = i
      elems(i).asInstanceOf[E]

    def set(e: E): Unit =
      if lastRet < 0 then throw new IllegalStateException()
      checkForComodification()
      ArrayList.this.set(lastRet, e)

    def add(e: E): Unit =
      checkForComodification()
      ArrayList.this.add(cursor, e)
      cursor += 1
      lastRet = -1
      expectedModCount = modCount

object ArrayList:
  /** The elements of `c` in a new array (one `c` does not keep), as the JDK's constructor. */
  private def toArrayOf(c: Collection[?]): Array[AnyRef] =
    val a = c.toArray()
    if c.getClass == classOf[ArrayList[?]] then a else Arrays.copyOf(a, a.length)
