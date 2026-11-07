package java.util.concurrent

import java.util.{AbstractSet, Collection, Iterator, Objects}
import java.util.function.{Consumer, Predicate}

/** java.util.concurrent.CopyOnWriteArraySet: a CopyOnWriteArrayList without duplicates, as in
 *  the JDK (iteration in insertion order, over a snapshot).
 */
@SerialVersionUID(5457747651344034263L)
class CopyOnWriteArraySet[E] private (al: CopyOnWriteArrayList[E]) extends AbstractSet[E] with java.io.Serializable:
  def this() = this(new CopyOnWriteArrayList[E]())
  def this(c: Collection[? <: E]) =
    this(new CopyOnWriteArrayList[E]())
    al.addAllAbsent(c)

  def size(): Int = al.size()
  override def isEmpty(): Boolean = al.isEmpty()
  override def contains(o: Any): Boolean = al.contains(o)
  override def toArray(): Array[AnyRef] = al.toArray()
  override def toArray[T <: AnyRef](a: Array[T]): Array[T] = al.toArray(a)
  override def clear(): Unit = al.clear()
  override def remove(o: Any): Boolean = al.remove(o)
  override def add(e: E): Boolean = al.addIfAbsent(e)
  override def containsAll(c: Collection[?]): Boolean = al.containsAll(c)
  override def addAll(c: Collection[? <: E]): Boolean = al.addAllAbsent(c) > 0
  override def removeAll(c: Collection[?]): Boolean = al.removeAll(c)
  override def retainAll(c: Collection[?]): Boolean = al.retainAll(c)
  def iterator(): Iterator[E] = al.iterator()
  override def removeIf(filter: Predicate[? >: E]): Boolean = al.removeIf(filter)
  override def forEach(action: Consumer[? >: E]): Unit = al.forEach(action)

  override def equals(o: Any): Boolean =
    (o.asInstanceOf[AnyRef] eq this) || (o match
      case s: java.util.Set[?] =>
        val es = al.toArray()
        es.length == s.size() && es.forall(e => s.contains(e))
      case _ => false)

  override def hashCode(): Int = al.toArray().foldLeft(0)((h, e) => h + Objects.hashCode(e))
