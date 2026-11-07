package java.util

/** A set backed by a `HashMap` (a `LinkedHashMap` for `LinkedHashSet`), as in the JDK, so that it
 *  iterates in the same order.
 */
class HashSet[E] private[util] (private[util] val map: HashMap[E, AnyRef])
    extends AbstractSet[E]
    with Set[E]
    with Cloneable
    with Serializable:

  def this(initialCapacity: Int, loadFactor: Float) = this(new HashMap[E, AnyRef](initialCapacity, loadFactor))

  def this(initialCapacity: Int) = this(new HashMap[E, AnyRef](initialCapacity))

  def this() = this(new HashMap[E, AnyRef]())

  def this(c: Collection[? <: E]) =
    this(new HashMap[E, AnyRef](Math.max((c.size() / .75f).toInt + 1, 16)))
    addAll(c)

  def iterator(): Iterator[E] = map.keyIterator()

  override def size(): Int = map.size()

  override def isEmpty(): Boolean = map.isEmpty()

  override def contains(o: Any): Boolean = map.containsKey(o)

  override def add(e: E): Boolean = map.put(e, HashSet.Present) == null

  override def remove(o: Any): Boolean = map.remove(o) eq HashSet.Present

  override def clear(): Unit = map.clear()

  override def clone(): AnyRef =
    val set = new HashSet[E](map.size())
    set.addAll(this)
    set

object HashSet:
  private[util] val Present: AnyRef = new Object

  // Since: Java 19
  def newHashSet[E](numElements: Int): HashSet[E] =
    if numElements < 0 then throw new IllegalArgumentException("Negative number of elements: " + numElements)
    new HashSet[E](Math.ceil(numElements / 0.75).toInt)
