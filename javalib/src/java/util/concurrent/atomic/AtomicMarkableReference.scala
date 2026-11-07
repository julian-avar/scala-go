package java.util.concurrent.atomic

import scalago.runtime.Intrinsics

/** java.util.concurrent.atomic.AtomicMarkableReference: as in the JDK, an immutable pair in a
 *  volatile field, replaced by compare-and-set.
 */
class AtomicMarkableReference[V](initialRef: V, initialMark: Boolean):
  @volatile private var pair = new AtomicMarkableReference.Pair[V](initialRef, initialMark)

  def getReference(): V = pair.reference
  def isMarked(): Boolean = pair.mark

  def get(markHolder: Array[Boolean]): V =
    val current = pair
    markHolder(0) = current.mark
    current.reference

  def weakCompareAndSet(expectedReference: V, newReference: V, expectedMark: Boolean, newMark: Boolean): Boolean =
    compareAndSet(expectedReference, newReference, expectedMark, newMark)

  def compareAndSet(expectedReference: V, newReference: V, expectedMark: Boolean, newMark: Boolean): Boolean =
    val current = pair
    current.refersTo(expectedReference) && expectedMark == current.mark &&
    ((current.refersTo(newReference) && newMark == current.mark) ||
      Intrinsics.compareAndSet(pair, current, new AtomicMarkableReference.Pair(newReference, newMark)))

  def set(newReference: V, newMark: Boolean): Unit =
    val current = pair
    if !current.refersTo(newReference) || newMark != current.mark then
      pair = new AtomicMarkableReference.Pair(newReference, newMark)

  def attemptMark(expectedReference: V, newMark: Boolean): Boolean =
    val current = pair
    current.refersTo(expectedReference) &&
    (newMark == current.mark ||
      Intrinsics.compareAndSet(pair, current, new AtomicMarkableReference.Pair(expectedReference, newMark)))

object AtomicMarkableReference:
  private final class Pair[T](val reference: T, val mark: Boolean):
    def refersTo(r: T): Boolean = reference.asInstanceOf[AnyRef] eq r.asInstanceOf[AnyRef]
