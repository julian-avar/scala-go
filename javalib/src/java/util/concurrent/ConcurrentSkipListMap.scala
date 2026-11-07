package java.util.concurrent

import java.util.{AbstractCollection, AbstractMap, AbstractSet, ArrayList, Collection, Comparator, Iterator, Map,
  NavigableMap, NavigableSet, NoSuchElementException, SortedMap, SortedSet, TreeMap, TreeSet}
import java.util.function.{BiConsumer, BiFunction, Consumer, Function, Predicate}

/** java.util.concurrent.ConcurrentSkipListMap: a sorted map that threads share without external
 *  locking. Not a lock-free skip list as in the JDK: a `TreeMap` under a lock, which every view
 *  (sub-maps, key sets, ...) shares. The observable contract is the JDK's: sorted iteration, no
 *  null keys or values, immutable snapshot entries from the navigation methods, and weakly
 *  consistent iterators that never throw ConcurrentModificationException (they iterate over a
 *  snapshot taken when they are created).
 */
@SerialVersionUID(-8627078645895051609L)
class ConcurrentSkipListMap[K, V] private (private val lock: Object, private val m: NavigableMap[K, V])
    extends AbstractMap[K, V] with ConcurrentNavigableMap[K, V] with Cloneable with java.io.Serializable:
  import ConcurrentSkipListMap.*

  def this() = this(new Object, new TreeMap[K, V]())
  def this(comparator: Comparator[? >: K]) = this(new Object, new TreeMap[K, V](comparator))
  def this(m: Map[? <: K, ? <: V]) =
    this(new Object, new TreeMap[K, V]())
    putAll(m)
  def this(m: SortedMap[K, ? <: V]) =
    this(new Object, new TreeMap[K, V](m.comparator()))
    putAll(m)

  private inline def locked[T](inline body: T): T = lock.synchronized(body)

  private def view(sub: NavigableMap[K, V]): ConcurrentSkipListMap[K, V] = new ConcurrentSkipListMap(lock, sub)

  private def nonNull(x: Any): Unit = if x == null then throw new NullPointerException()

  override def clone(): ConcurrentSkipListMap[K, V] = locked(new ConcurrentSkipListMap(new Object, new TreeMap[K, V](m)))

  // ---- Map ----

  override def size(): Int = locked(m.size())
  override def isEmpty(): Boolean = locked(m.isEmpty())
  override def containsKey(key: Any): Boolean = { nonNull(key); locked(m.containsKey(key)) }
  override def containsValue(value: Any): Boolean = { nonNull(value); locked(m.containsValue(value)) }
  override def get(key: Any): V = { nonNull(key); locked(m.get(key)) }
  override def getOrDefault(key: Any, defaultValue: V): V =
    val v = get(key)
    if v == null then defaultValue else v
  override def put(key: K, value: V): V = { nonNull(key); nonNull(value); locked(m.put(key, value)) }
  override def remove(key: Any): V = { nonNull(key); locked(m.remove(key)) }
  override def clear(): Unit = locked(m.clear())
  override def putAll(map: Map[? <: K, ? <: V]): Unit = map.forEach((k, v) => put(k, v))

  override def putIfAbsent(key: K, value: V): V =
    nonNull(key); nonNull(value)
    locked {
      val old = m.get(key)
      if old == null then m.put(key, value)
      old
    }

  override def remove(key: Any, value: Any): Boolean =
    nonNull(key)
    value != null && locked {
      val old = m.get(key)
      if old != null && old.equals(value) then
        m.remove(key)
        true
      else false
    }

  override def replace(key: K, oldValue: V, newValue: V): Boolean =
    nonNull(key); nonNull(oldValue); nonNull(newValue)
    locked {
      val old = m.get(key)
      if old != null && old.equals(oldValue) then
        m.put(key, newValue)
        true
      else false
    }

  override def replace(key: K, value: V): V =
    nonNull(key); nonNull(value)
    locked(if m.containsKey(key) then m.put(key, value) else null.asInstanceOf[V])

  override def computeIfAbsent(key: K, f: Function[? >: K, ? <: V]): V =
    nonNull(key); nonNull(f)
    locked {
      val old = m.get(key)
      if old != null then old
      else
        val v = f.apply(key)
        if v != null then m.put(key, v)
        v
    }

  override def computeIfPresent(key: K, f: BiFunction[? >: K, ? >: V, ? <: V]): V =
    nonNull(key); nonNull(f)
    locked {
      val old = m.get(key)
      if old == null then old
      else
        val v = f.apply(key, old)
        if v == null then m.remove(key) else m.put(key, v)
        v
    }

  override def compute(key: K, f: BiFunction[? >: K, ? >: V, ? <: V]): V =
    nonNull(key); nonNull(f)
    locked {
      val v = f.apply(key, m.get(key))
      if v == null then m.remove(key) else m.put(key, v)
      v
    }

  override def merge(key: K, value: V, f: BiFunction[? >: V, ? >: V, ? <: V]): V =
    nonNull(key); nonNull(value); nonNull(f)
    locked {
      val old = m.get(key)
      val v = if old == null then value else f.apply(old, value)
      if v == null then m.remove(key) else m.put(key, v)
      v
    }

  override def forEach(action: BiConsumer[? >: K, ? >: V]): Unit =
    nonNull(action)
    snapshotEntries().forEach(e => action.accept(e.getKey(), e.getValue()))

  override def replaceAll(f: BiFunction[? >: K, ? >: V, ? <: V]): Unit =
    nonNull(f)
    locked(m.replaceAll { (k, v) =>
      val r = f.apply(k, v)
      nonNull(r)
      r
    })

  // ---- NavigableMap ----

  def comparator(): Comparator[? >: K] = m.comparator()
  def firstKey(): K = locked(m.firstKey())
  def lastKey(): K = locked(m.lastKey())
  def lowerKey(key: K): K = { nonNull(key); locked(m.lowerKey(key)) }
  def floorKey(key: K): K = { nonNull(key); locked(m.floorKey(key)) }
  def ceilingKey(key: K): K = { nonNull(key); locked(m.ceilingKey(key)) }
  def higherKey(key: K): K = { nonNull(key); locked(m.higherKey(key)) }
  def lowerEntry(key: K): Map.Entry[K, V] = { nonNull(key); locked(snapshot(m.lowerEntry(key))) }
  def floorEntry(key: K): Map.Entry[K, V] = { nonNull(key); locked(snapshot(m.floorEntry(key))) }
  def ceilingEntry(key: K): Map.Entry[K, V] = { nonNull(key); locked(snapshot(m.ceilingEntry(key))) }
  def higherEntry(key: K): Map.Entry[K, V] = { nonNull(key); locked(snapshot(m.higherEntry(key))) }
  override def firstEntry(): Map.Entry[K, V] = locked(snapshot(m.firstEntry()))
  override def lastEntry(): Map.Entry[K, V] = locked(snapshot(m.lastEntry()))
  override def pollFirstEntry(): Map.Entry[K, V] = locked(snapshot(m.pollFirstEntry()))
  override def pollLastEntry(): Map.Entry[K, V] = locked(snapshot(m.pollLastEntry()))

  def subMap(fromKey: K, fromInclusive: Boolean, toKey: K, toInclusive: Boolean): ConcurrentNavigableMap[K, V] =
    nonNull(fromKey); nonNull(toKey)
    locked(view(m.subMap(fromKey, fromInclusive, toKey, toInclusive)))
  def headMap(toKey: K, inclusive: Boolean): ConcurrentNavigableMap[K, V] =
    nonNull(toKey)
    locked(view(m.headMap(toKey, inclusive)))
  def tailMap(fromKey: K, inclusive: Boolean): ConcurrentNavigableMap[K, V] =
    nonNull(fromKey)
    locked(view(m.tailMap(fromKey, inclusive)))
  def subMap(fromKey: K, toKey: K): ConcurrentNavigableMap[K, V] = subMap(fromKey, true, toKey, false)
  def headMap(toKey: K): ConcurrentNavigableMap[K, V] = headMap(toKey, false)
  def tailMap(fromKey: K): ConcurrentNavigableMap[K, V] = tailMap(fromKey, true)
  def descendingMap(): ConcurrentNavigableMap[K, V] = locked(view(m.descendingMap()))

  override def keySet(): NavigableSet[K] = navigableKeySet()
  def navigableKeySet(): NavigableSet[K] = locked(new LockedNavigableSet[K](lock, m.navigableKeySet()))
  def descendingKeySet(): NavigableSet[K] = locked(new LockedNavigableSet[K](lock, m.descendingKeySet()))
  override def values(): Collection[V] = locked(new LockedCollection[V](lock, m.values()))
  def entrySet(): java.util.Set[Map.Entry[K, V]] = new EntrySet

  private def snapshotEntries(): ArrayList[Map.Entry[K, V]] = locked {
    val es = new ArrayList[Map.Entry[K, V]](m.size())
    m.entrySet().forEach(e => es.add(snapshot(e)))
    es
  }

  private final class EntrySet extends AbstractSet[Map.Entry[K, V]]:
    def size(): Int = ConcurrentSkipListMap.this.size()
    override def isEmpty(): Boolean = ConcurrentSkipListMap.this.isEmpty()
    override def contains(o: Any): Boolean = o match
      case e: Map.Entry[?, ?] =>
        e.getKey() != null && locked {
          val v = m.get(e.getKey())
          v != null && v.equals(e.getValue())
        }
      case _ => false
    override def remove(o: Any): Boolean = o match
      case e: Map.Entry[?, ?] => ConcurrentSkipListMap.this.remove(e.getKey(), e.getValue())
      case _                  => false
    override def clear(): Unit = ConcurrentSkipListMap.this.clear()
    def iterator(): Iterator[Map.Entry[K, V]] =
      new SnapshotIterator[Map.Entry[K, V]](snapshotEntries(), e => ConcurrentSkipListMap.this.remove(e.getKey(), e.getValue()))

object ConcurrentSkipListMap:
  private def snapshot[K, V](e: Map.Entry[K, V]): Map.Entry[K, V] =
    if e == null then null else new AbstractMap.SimpleImmutableEntry[K, V](e.getKey(), e.getValue())

  /** Iterates over `elements`; `remove()` removes the last element through `removeElement`. */
  private[concurrent] final class SnapshotIterator[E](elements: ArrayList[E], removeElement: E => Unit) extends Iterator[E]:
    private var i = 0
    private var canRemove = false
    def hasNext(): Boolean = i < elements.size()
    def next(): E =
      if i >= elements.size() then throw new NoSuchElementException()
      i += 1
      canRemove = true
      elements.get(i - 1)
    override def remove(): Unit =
      if !canRemove then throw new IllegalStateException()
      canRemove = false
      removeElement(elements.get(i - 1))

  /** A view (`values()`, ...) of a collection guarded by `lock`. */
  private[concurrent] class LockedCollection[E](lock: Object, c: Collection[E]) extends AbstractCollection[E]:
    protected inline def locked[T](inline body: T): T = lock.synchronized(body)
    def size(): Int = locked(c.size())
    override def isEmpty(): Boolean = locked(c.isEmpty())
    override def contains(o: Any): Boolean = o != null && locked(c.contains(o))
    override def remove(o: Any): Boolean = o != null && locked(c.remove(o))
    override def clear(): Unit = locked(c.clear())
    override def removeIf(filter: Predicate[? >: E]): Boolean = locked(c.removeIf(filter))
    override def toArray(): Array[AnyRef] = locked(c.toArray())
    override def toArray[T <: AnyRef](a: Array[T]): Array[T] = locked(c.toArray(a))
    protected def snapshotElements(): ArrayList[E] = locked(new ArrayList[E](c))
    def iterator(): Iterator[E] = new SnapshotIterator[E](snapshotElements(), e => locked(c.remove(e)))
    override def forEach(action: Consumer[? >: E]): Unit = snapshotElements().forEach(action)

  /** A navigable set (key sets of the map, and ConcurrentSkipListSet) guarded by `lock`. */
  private[concurrent] class LockedNavigableSet[E](lock: Object, s: NavigableSet[E])
      extends LockedCollection[E](lock, s) with NavigableSet[E]:
    protected def wrap(sub: NavigableSet[E]): NavigableSet[E] = new LockedNavigableSet[E](lock, sub)

    override def add(e: E): Boolean =
      if e == null then throw new NullPointerException()
      locked(s.add(e))
    def comparator(): Comparator[? >: E] = s.comparator()
    def first(): E = locked(s.first())
    def last(): E = locked(s.last())
    def lower(e: E): E = locked(s.lower(e))
    def floor(e: E): E = locked(s.floor(e))
    def ceiling(e: E): E = locked(s.ceiling(e))
    def higher(e: E): E = locked(s.higher(e))
    def pollFirst(): E = locked(s.pollFirst())
    def pollLast(): E = locked(s.pollLast())
    def descendingSet(): NavigableSet[E] = locked(wrap(s.descendingSet()))
    def descendingIterator(): Iterator[E] = descendingSet().iterator()
    def subSet(fromElement: E, fromInclusive: Boolean, toElement: E, toInclusive: Boolean): NavigableSet[E] =
      locked(wrap(s.subSet(fromElement, fromInclusive, toElement, toInclusive)))
    def headSet(toElement: E, inclusive: Boolean): NavigableSet[E] = locked(wrap(s.headSet(toElement, inclusive)))
    def tailSet(fromElement: E, inclusive: Boolean): NavigableSet[E] = locked(wrap(s.tailSet(fromElement, inclusive)))
    // NavigableSet results, as ConcurrentSkipListSet's in the JDK (with bridges for SortedSet's).
    def subSet(fromElement: E, toElement: E): NavigableSet[E] = subSet(fromElement, true, toElement, false)
    def headSet(toElement: E): NavigableSet[E] = headSet(toElement, false)
    def tailSet(fromElement: E): NavigableSet[E] = tailSet(fromElement, true)

    override def equals(o: Any): Boolean =
      (o.asInstanceOf[AnyRef] eq this) || (o match
        case other: java.util.Set[?] =>
          val es = snapshotElements()
          es.size() == other.size() && other.containsAll(es)
        case _ => false)

    override def hashCode(): Int =
      var h = 0
      snapshotElements().forEach(e => h += e.hashCode())
      h
