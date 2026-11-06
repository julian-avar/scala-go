package scalago.runtime

/** The native side of java.util.concurrent.locks (see runtime/rt/sync.go): a ReentrantLock is
 *  the monitor of a private object, and its Conditions are wait sets of that monitor.
 */
object Locks:
  private def native: Nothing = throw new UnsupportedOperationException("scala-go native")

  def enter(lock: Object): Unit = native
  def exit(lock: Object): Unit = native
  def tryEnter(lock: Object): Boolean = native
  def heldByCurrent(lock: Object): Boolean = native
  def isLocked(lock: Object): Boolean = native
  def newCondition(lock: Object): Object = native
  /** Waits for a signal, or `nanos` (if >= 0); the remaining nanoseconds (<= 0 on timeout). */
  def await(condition: Object, nanos: Long): Long = native
  def signal(condition: Object): Unit = native
  def signalAll(condition: Object): Unit = native
