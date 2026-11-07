package java.util.concurrent.atomic

import scalago.runtime.Intrinsics

/** java.util.concurrent.atomic.AtomicStampedReference: as in the JDK, an immutable pair in a
 *  volatile field, replaced by compare-and-set.
 */
class AtomicStampedReference[V](initialRef: V, initialStamp: Int):
  @volatile private var pair = new AtomicStampedReference.Pair[V](initialRef, initialStamp)

  def getReference(): V = pair.reference
  def getStamp(): Int = pair.stamp

  def get(stampHolder: Array[Int]): V =
    val current = pair
    stampHolder(0) = current.stamp
    current.reference

  def weakCompareAndSet(expectedReference: V, newReference: V, expectedStamp: Int, newStamp: Int): Boolean =
    compareAndSet(expectedReference, newReference, expectedStamp, newStamp)

  def compareAndSet(expectedReference: V, newReference: V, expectedStamp: Int, newStamp: Int): Boolean =
    val current = pair
    current.refersTo(expectedReference) && expectedStamp == current.stamp &&
    ((current.refersTo(newReference) && newStamp == current.stamp) ||
      Intrinsics.compareAndSet(pair, current, new AtomicStampedReference.Pair(newReference, newStamp)))

  def set(newReference: V, newStamp: Int): Unit =
    val current = pair
    if !current.refersTo(newReference) || newStamp != current.stamp then
      pair = new AtomicStampedReference.Pair(newReference, newStamp)

  def attemptStamp(expectedReference: V, newStamp: Int): Boolean =
    val current = pair
    current.refersTo(expectedReference) &&
    (newStamp == current.stamp ||
      Intrinsics.compareAndSet(pair, current, new AtomicStampedReference.Pair(expectedReference, newStamp)))

object AtomicStampedReference:
  private final class Pair[T](val reference: T, val stamp: Int):
    def refersTo(r: T): Boolean = reference.asInstanceOf[AnyRef] eq r.asInstanceOf[AnyRef]
