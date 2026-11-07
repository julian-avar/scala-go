package java.util.concurrent

import java.util.{Collection, Comparator, NavigableSet, SortedSet, TreeSet}

/** java.util.concurrent.ConcurrentSkipListSet: a `TreeSet` under a lock, with snapshot (weakly
 *  consistent) iterators, like ConcurrentSkipListMap. Sub-sets share the lock.
 */
@SerialVersionUID(-2479143111061671589L)
class ConcurrentSkipListSet[E] private (lock: Object, s: NavigableSet[E])
    extends ConcurrentSkipListMap.LockedNavigableSet[E](lock, s) with Cloneable with java.io.Serializable:
  def this() = this(new Object, new TreeSet[E]())
  def this(comparator: Comparator[? >: E]) = this(new Object, new TreeSet[E](comparator))
  def this(c: Collection[? <: E]) =
    this(new Object, new TreeSet[E]())
    addAll(c)
  def this(s: SortedSet[E]) =
    this(new Object, new TreeSet[E](s.comparator()))
    addAll(s)

  override protected def wrap(sub: NavigableSet[E]): NavigableSet[E] = new ConcurrentSkipListSet[E](lock, sub)

  override def clone(): ConcurrentSkipListSet[E] =
    lock.synchronized(new ConcurrentSkipListSet[E](new Object, new TreeSet[E](s)))

  override def removeAll(c: Collection[?]): Boolean =
    var modified = false
    c.forEach(e => if remove(e) then modified = true)
    modified
