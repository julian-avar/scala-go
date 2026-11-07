package java.util

/** A `HashSet` backed by a `LinkedHashMap`: it iterates in insertion order. */
class LinkedHashSet[E] private (map: LinkedHashMap[E, AnyRef])
    extends HashSet[E](map)
    with SequencedSet[E]
    with Cloneable
    with Serializable:

  def this(initialCapacity: Int, loadFactor: Float) = this(new LinkedHashMap[E, AnyRef](initialCapacity, loadFactor))

  def this(initialCapacity: Int) = this(new LinkedHashMap[E, AnyRef](initialCapacity))

  def this() = this(new LinkedHashMap[E, AnyRef]())

  def this(c: Collection[? <: E]) =
    this(new LinkedHashMap[E, AnyRef](Math.max(2 * c.size(), 11)))
    addAll(c)

  /** Adds `e` first, or moves it there (Java 21). */
  override def addFirst(e: E): Unit = map.putFirst(e, HashSet.Present)

  /** Adds `e` last, or moves it there (Java 21). */
  override def addLast(e: E): Unit = map.putLast(e, HashSet.Present)

  override def getFirst(): E =
    val entry = map.firstEntry()
    if entry == null then throw new NoSuchElementException()
    entry.getKey()

  override def getLast(): E =
    val entry = map.lastEntry()
    if entry == null then throw new NoSuchElementException()
    entry.getKey()

  override def removeFirst(): E =
    val entry = map.pollFirstEntry()
    if entry == null then throw new NoSuchElementException()
    entry.getKey()

  override def removeLast(): E =
    val entry = map.pollLastEntry()
    if entry == null then throw new NoSuchElementException()
    entry.getKey()

  /** @since JDK 21 */
  def reversed(): SequencedSet[E] = new ReverseOrderSequencedSetView(this)

  override def clone(): AnyRef =
    val set = new LinkedHashSet[E](2 * size())
    set.addAll(this)
    set

object LinkedHashSet:
  // Since: Java 19
  def newLinkedHashSet[T](numElements: Int): LinkedHashSet[T] =
    if numElements < 0 then throw new IllegalArgumentException("Negative number of elements: " + numElements)
    new LinkedHashSet[T](Math.ceil(numElements / 0.75).toInt)
