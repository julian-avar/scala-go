package scalago.javalib.lang.ref

/** References are strong: Go's garbage collector has no weak-reference hook that fits here, and
 *  code using them (caches) remains correct, only less eager to release memory.
 */
abstract class Reference[T](private var referent: T):
  def get(): T = referent
  def clear(): Unit = referent = null.asInstanceOf[T]
  def refersTo(obj: T): Boolean = referent.asInstanceOf[AnyRef] eq obj.asInstanceOf[AnyRef]

class WeakReference[T](referent: T) extends Reference[T](referent):
  def this(referent: T, queue: ReferenceQueue[? >: T]) = this(referent)

class SoftReference[T](referent: T) extends Reference[T](referent):
  def this(referent: T, queue: ReferenceQueue[? >: T]) = this(referent)

class ReferenceQueue[T]
