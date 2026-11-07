package java.util

import java.util.function.{BiConsumer, BiFunction}

/** The legacy synchronized hash table, with the JDK's layout (a bucket per `(hash & 0x7FFFFFFF) %
 *  capacity`, new entries first in their bucket, buckets visited from the last), so that it
 *  iterates in the same order. Keys and values cannot be null. Iterators are fail-fast.
 */
class Hashtable[K, V](initialCapacity: Int, loadFactor: Float)
    extends Dictionary[K, V]
    with Map[K, V]
    with Cloneable
    with Serializable:
  import Hashtable.Entry

  if initialCapacity < 0 then throw new IllegalArgumentException("Illegal Capacity: " + initialCapacity)
  if loadFactor <= 0 || java.lang.Float.isNaN(loadFactor) then
    throw new IllegalArgumentException("Illegal Load: " + loadFactor)

  private var table = new Array[Entry[K, V]](Math.max(initialCapacity, 1))
  private var threshold = Math.min(table.length * loadFactor, Integer.MAX_VALUE - 8 + 1f).toInt
  private var count = 0
  private var modCount = 0

  def this(initialCapacity: Int) = this(initialCapacity, 0.75f)

  def this() = this(11, 0.75f)

  def this(t: Map[? <: K, ? <: V]) =
    this(Math.max(2 * t.size(), 11), 0.75f)
    putAll(t)

  private def indexFor(hash: Int, length: Int): Int = (hash & 0x7fffffff) % length

  private def findEntry(key: Any): Entry[K, V] =
    val hash = key.hashCode()
    var e = table(indexFor(hash, table.length))
    while e != null && !(e.hash == hash && e.key.equals(key)) do e = e.next
    e

  def size(): Int = synchronized(count)

  def isEmpty(): Boolean = synchronized(count == 0)

  def keys(): Enumeration[K] = synchronized(new Iter[K](_.key))

  def elements(): Enumeration[V] = synchronized(new Iter[V](_.value))

  def contains(value: Any): Boolean = synchronized {
    if value == null then throw new NullPointerException()
    var i = table.length - 1
    while i >= 0 do
      var e = table(i)
      while e != null do
        if e.value.equals(value) then return true
        e = e.next
      i -= 1
    false
  }

  def containsValue(value: Any): Boolean = contains(value)

  def containsKey(key: Any): Boolean = synchronized(findEntry(key) != null)

  def get(key: Any): V = synchronized {
    val e = findEntry(key)
    if e == null then null.asInstanceOf[V] else e.value
  }

  /** Doubles the capacity (plus one) and redistributes the entries, as the JDK does. */
  protected def rehash(): Unit =
    val old = table
    val newCapacity = old.length * 2 + 1
    val newTable = new Array[Entry[K, V]](newCapacity)
    modCount += 1
    threshold = Math.min(newCapacity * loadFactor, Integer.MAX_VALUE - 8 + 1f).toInt
    table = newTable
    var i = old.length - 1
    while i >= 0 do
      var e = old(i)
      while e != null do
        val next = e.next
        val index = indexFor(e.hash, newCapacity)
        e.next = newTable(index)
        newTable(index) = e
        e = next
      i -= 1

  def put(key: K, value: V): V = synchronized {
    if value == null then throw new NullPointerException()
    val hash = key.hashCode()
    var index = indexFor(hash, table.length)
    var e = table(index)
    while e != null do
      if e.hash == hash && e.key.equals(key) then
        val old = e.value
        e.value = value
        return old
      e = e.next
    if count >= threshold then
      rehash()
      index = indexFor(hash, table.length)
    table(index) = new Entry(hash, key, value, table(index))
    count += 1
    modCount += 1
    null.asInstanceOf[V]
  }

  def remove(key: Any): V = synchronized {
    val hash = key.hashCode()
    val index = indexFor(hash, table.length)
    var prev: Entry[K, V] = null
    var e = table(index)
    while e != null do
      if e.hash == hash && e.key.equals(key) then
        if prev == null then table(index) = e.next else prev.next = e.next
        modCount += 1
        count -= 1
        return e.value
      prev = e
      e = e.next
    null.asInstanceOf[V]
  }

  def putAll(m: Map[? <: K, ? <: V]): Unit = synchronized {
    val it = m.entrySet().iterator()
    while it.hasNext() do
      val e = it.next()
      put(e.getKey(), e.getValue())
  }

  def clear(): Unit = synchronized {
    Arrays.fill(table.asInstanceOf[Array[AnyRef]], null)
    modCount += 1
    count = 0
  }

  override def clone(): AnyRef = synchronized {
    val result = new Hashtable[K, V](table.length, loadFactor)
    var i = table.length - 1
    while i >= 0 do
      result.table(i) = if table(i) == null then null else table(i).copyChain()
      i -= 1
    result.count = count
    result
  }

  override def toString(): String = synchronized {
    val sb = new java.lang.StringBuilder("{")
    val it = new Iter[Entry[K, V]](e => e)
    while it.hasNext() do
      val e = it.next()
      sb.append(if e.key.asInstanceOf[AnyRef] eq this then "(this Map)" else e.key)
      sb.append('=')
      sb.append(if e.value.asInstanceOf[AnyRef] eq this then "(this Map)" else e.value)
      if it.hasNext() then sb.append(", ")
    sb.append('}').toString
  }

  override def equals(o: Any): Boolean = synchronized {
    o match
      case m: Map[?, ?] =>
        (m eq this) || m.size() == count && {
          val it = new Iter[Entry[K, V]](e => e)
          var same = true
          while same && it.hasNext() do
            val e = it.next()
            same = e.value.equals(m.get(e.key))
          same
        }
      case _ => false
  }

  override def hashCode(): Int = synchronized {
    var h = 0
    val it = new Iter[Entry[K, V]](e => e)
    while it.hasNext() do h += it.next().hashCode()
    h
  }

  override def forEach(action: BiConsumer[? >: K, ? >: V]): Unit = synchronized {
    val expected = modCount
    var i = table.length - 1
    while i >= 0 do
      var e = table(i)
      while e != null do
        action.accept(e.key, e.value)
        e = e.next
        if modCount != expected then throw new ConcurrentModificationException()
      i -= 1
  }

  override def replaceAll(function: BiFunction[? >: K, ? >: V, ? <: V]): Unit = synchronized {
    val expected = modCount
    var i = table.length - 1
    while i >= 0 do
      var e = table(i)
      while e != null do
        e.value = Objects.requireNonNull(function.apply(e.key, e.value))
        e = e.next
        if modCount != expected then throw new ConcurrentModificationException()
      i -= 1
  }

  def keySet(): Set[K] = new AbstractSet[K]:
    def iterator(): Iterator[K] = Hashtable.this.synchronized(new Iter[K](_.key))
    def size(): Int = Hashtable.this.size()
    override def contains(o: Any): Boolean = containsKey(o)
    override def remove(o: Any): Boolean = Hashtable.this.remove(o) != null
    override def clear(): Unit = Hashtable.this.clear()

  def values(): Collection[V] = new AbstractCollection[V]:
    def iterator(): Iterator[V] = Hashtable.this.synchronized(new Iter[V](_.value))
    def size(): Int = Hashtable.this.size()
    override def contains(o: Any): Boolean = containsValue(o)
    override def clear(): Unit = Hashtable.this.clear()

  def entrySet(): Set[Map.Entry[K, V]] = new AbstractSet[Map.Entry[K, V]]:
    def iterator(): Iterator[Map.Entry[K, V]] = Hashtable.this.synchronized(new Iter[Map.Entry[K, V]](e => e))
    def size(): Int = Hashtable.this.size()
    override def clear(): Unit = Hashtable.this.clear()
    override def contains(o: Any): Boolean = o match
      case e: Map.Entry[?, ?] =>
        val found = Hashtable.this.synchronized(findEntry(e.getKey()))
        found != null && found.equals(e)
      case _ => false
    override def remove(o: Any): Boolean = contains(o) && {
      Hashtable.this.remove(o.asInstanceOf[Map.Entry[?, ?]].getKey())
      true
    }

  /** Iterates over the buckets from the last, as the JDK's enumerations and iterators. */
  private class Iter[A](extract: Entry[K, V] => A) extends Iterator[A] with Enumeration[A]:
    private var index = table.length
    private var entry: Entry[K, V] = null
    private var lastReturned: Entry[K, V] = null
    private var expectedModCount = modCount

    def hasMoreElements(): Boolean = hasNext()

    def hasNext(): Boolean =
      while entry == null && index > 0 do
        index -= 1
        entry = table(index)
      entry != null

    def nextElement(): A = next()

    def next(): A =
      if modCount != expectedModCount then throw new ConcurrentModificationException()
      if !hasNext() then throw new NoSuchElementException("Hashtable Enumerator")
      val e = entry
      entry = e.next
      lastReturned = e
      extract(e)

    override def remove(): Unit =
      if lastReturned == null then throw new IllegalStateException("Hashtable Enumerator")
      if modCount != expectedModCount then throw new ConcurrentModificationException()
      Hashtable.this.remove(lastReturned.key)
      expectedModCount = modCount
      lastReturned = null

object Hashtable:
  private final class Entry[K, V](val hash: Int, val key: K, var value: V, var next: Entry[K, V])
      extends Map.Entry[K, V]:
    def getKey(): K = key
    def getValue(): V = value

    def setValue(v: V): V =
      if v == null then throw new NullPointerException()
      val old = value
      value = v
      old

    def copyChain(): Entry[K, V] = new Entry(hash, key, value, if next == null then null else next.copyChain())

    override def equals(o: Any): Boolean = o match
      case e: Map.Entry[?, ?] => Objects.equals(key, e.getKey()) && Objects.equals(value, e.getValue())
      case _                  => false

    override def hashCode(): Int = hash ^ Objects.hashCode(value)

    override def toString(): String = s"$key=$value"
