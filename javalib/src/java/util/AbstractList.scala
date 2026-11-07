package java.util

/** The skeleton of lists, as in the JDK: iterators and sublists on top of `get`/`set`/`add`/
 *  `remove(index)`, fail-fast through `modCount` (which subclasses, including programs' own,
 *  increment on structural modifications).
 */
abstract class AbstractList[E] protected () extends AbstractCollection[E] with List[E]:
  /** The number of structural modifications (a `protected` field of the JDK's AbstractList). */
  protected[util] var modCount: Int = 0

  override def add(e: E): Boolean =
    add(size(), e)
    true

  def get(index: Int): E

  def set(index: Int, element: E): E = throw new UnsupportedOperationException()

  def add(index: Int, element: E): Unit = throw new UnsupportedOperationException()

  def remove(index: Int): E = throw new UnsupportedOperationException()

  def indexOf(o: Any): Int =
    val it = listIterator()
    while it.hasNext() do
      if Objects.equals(o, it.next()) then return it.previousIndex()
    -1

  def lastIndexOf(o: Any): Int =
    val it = listIterator(size())
    while it.hasPrevious() do
      if Objects.equals(o, it.previous()) then return it.nextIndex()
    -1

  override def clear(): Unit = removeRange(0, size())

  def addAll(index: Int, c: Collection[? <: E]): Boolean =
    AbstractList.checkPositionIndex(index, size())
    var i = index
    val it = c.iterator()
    while it.hasNext() do
      add(i, it.next())
      i += 1
    i != index

  def iterator(): Iterator[E] = new Itr

  def listIterator(): ListIterator[E] = listIterator(0)

  def listIterator(index: Int): ListIterator[E] =
    AbstractList.checkPositionIndex(index, size())
    new ListItr(index)

  def subList(fromIndex: Int, toIndex: Int): List[E] =
    AbstractList.checkSubListRange(fromIndex, toIndex, size())
    this match
      case _: RandomAccess => new AbstractList.RandomAccessSubList(this, null, fromIndex, toIndex - fromIndex)
      case _               => new AbstractList.SubList(this, null, fromIndex, toIndex - fromIndex)

  override def equals(o: Any): Boolean =
    if o.asInstanceOf[AnyRef] eq this then true
    else
      o match
        case o: List[?] =>
          val it1 = listIterator()
          val it2 = o.listIterator()
          while it1.hasNext() && it2.hasNext() do
            if !Objects.equals(it1.next(), it2.next()) then return false
          !(it1.hasNext() || it2.hasNext())
        case _ => false

  override def hashCode(): Int =
    var hash = 1
    val it = iterator()
    while it.hasNext() do hash = 31 * hash + Objects.hashCode(it.next())
    hash

  protected[util] def removeRange(fromIndex: Int, toIndex: Int): Unit =
    val it = listIterator(fromIndex)
    var n = toIndex - fromIndex
    while n > 0 do
      it.next()
      it.remove()
      n -= 1

  private class Itr extends Iterator[E]:
    /** The index of the element `next` returns. */
    protected var cursor = 0
    /** The index of the element last returned, or -1. */
    protected var lastRet = -1
    protected var expectedModCount = modCount

    def hasNext(): Boolean = cursor != size()

    def next(): E =
      checkForComodification()
      val i = cursor
      if i >= size() then throw new NoSuchElementException()
      val elem = get(i)
      lastRet = i
      cursor = i + 1
      elem

    override def remove(): Unit =
      if lastRet < 0 then throw new IllegalStateException()
      checkForComodification()
      AbstractList.this.remove(lastRet)
      if lastRet < cursor then cursor -= 1
      lastRet = -1
      expectedModCount = modCount

    protected final def checkForComodification(): Unit =
      if modCount != expectedModCount then throw new ConcurrentModificationException()

  private class ListItr(index: Int) extends Itr with ListIterator[E]:
    cursor = index

    def hasPrevious(): Boolean = cursor != 0

    def previous(): E =
      checkForComodification()
      val i = cursor - 1
      if i < 0 then throw new NoSuchElementException()
      val elem = get(i)
      lastRet = i
      cursor = i
      elem

    def nextIndex(): Int = cursor

    def previousIndex(): Int = cursor - 1

    def set(e: E): Unit =
      if lastRet < 0 then throw new IllegalStateException()
      checkForComodification()
      AbstractList.this.set(lastRet, e)
      expectedModCount = modCount

    def add(e: E): Unit =
      checkForComodification()
      AbstractList.this.add(cursor, e)
      lastRet = -1
      cursor += 1
      expectedModCount = modCount

object AbstractList:
  private[util] def checkElementIndex(index: Int, size: Int): Unit =
    if index < 0 || index >= size then
      throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + size)

  private[util] def checkPositionIndex(index: Int, size: Int): Unit =
    if index < 0 || index > size then throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size)

  private[util] def checkSubListRange(fromIndex: Int, toIndex: Int, size: Int): Unit =
    if fromIndex < 0 then throw new IndexOutOfBoundsException("fromIndex = " + fromIndex)
    if toIndex > size then throw new IndexOutOfBoundsException("toIndex = " + toIndex)
    if fromIndex > toIndex then
      throw new IllegalArgumentException("fromIndex(" + fromIndex + ") > toIndex(" + toIndex + ")")

  /** A view of `root`'s elements `offset` until `offset + size`: structural changes made through
   *  it update its ancestors (`parent`), and changes made otherwise make it throw
   *  `ConcurrentModificationException`.
   */
  private[util] class SubList[E](protected val root: AbstractList[E], private val parent: SubList[E],
      protected val offset: Int, private var length: Int)
      extends AbstractList[E]:
    modCount = root.modCount

    override def set(index: Int, element: E): E =
      checkElementIndex(index, length)
      checkForComodification()
      root.set(offset + index, element)

    def get(index: Int): E =
      checkElementIndex(index, length)
      checkForComodification()
      root.get(offset + index)

    def size(): Int =
      checkForComodification()
      length

    override def add(index: Int, element: E): Unit =
      checkPositionIndex(index, length)
      checkForComodification()
      root.add(offset + index, element)
      updateSizeAndModCount(1)

    override def remove(index: Int): E =
      checkElementIndex(index, length)
      checkForComodification()
      val result = root.remove(offset + index)
      updateSizeAndModCount(-1)
      result

    override protected[util] def removeRange(fromIndex: Int, toIndex: Int): Unit =
      checkForComodification()
      root.removeRange(offset + fromIndex, offset + toIndex)
      updateSizeAndModCount(fromIndex - toIndex)

    override def addAll(c: Collection[? <: E]): Boolean = addAll(length, c)

    override def addAll(index: Int, c: Collection[? <: E]): Boolean =
      checkPositionIndex(index, length)
      val n = c.size()
      if n == 0 then false
      else
        checkForComodification()
        root.addAll(offset + index, c)
        updateSizeAndModCount(n)
        true

    override def iterator(): Iterator[E] = listIterator()

    override def listIterator(index: Int): ListIterator[E] =
      checkForComodification()
      checkPositionIndex(index, length)
      val sub = this
      new ListIterator[E]:
        private val it = root.listIterator(offset + index)

        def hasNext(): Boolean = nextIndex() < length
        def next(): E = if hasNext() then it.next() else throw new NoSuchElementException()
        def hasPrevious(): Boolean = previousIndex() >= 0
        def previous(): E = if hasPrevious() then it.previous() else throw new NoSuchElementException()
        def nextIndex(): Int = it.nextIndex() - offset
        def previousIndex(): Int = it.previousIndex() - offset

        override def remove(): Unit =
          it.remove()
          sub.updateSizeAndModCount(-1)

        def set(e: E): Unit = it.set(e)

        def add(e: E): Unit =
          it.add(e)
          sub.updateSizeAndModCount(1)

    override def subList(fromIndex: Int, toIndex: Int): List[E] =
      checkSubListRange(fromIndex, toIndex, length)
      newSubList(fromIndex, toIndex - fromIndex)

    protected def newSubList(from: Int, n: Int): List[E] = new SubList(root, this, offset + from, n)

    private def checkForComodification(): Unit =
      if root.modCount != modCount then throw new ConcurrentModificationException()

    private def updateSizeAndModCount(delta: Int): Unit =
      var s = this
      while s != null do
        s.length += delta
        s.modCount = root.modCount
        s = s.parent

  private[util] final class RandomAccessSubList[E](root0: AbstractList[E], parent0: SubList[E], offset0: Int, length0: Int)
      extends SubList[E](root0, parent0, offset0, length0)
      with RandomAccess:
    override protected def newSubList(from: Int, n: Int): List[E] =
      new RandomAccessSubList(root, this, offset + from, n)
