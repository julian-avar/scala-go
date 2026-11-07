package scalago.runtime

/** The native side of java.lang.Thread and LockSupport (see
 *  runtime/rt/thread.go). The linker replaces these methods with Go code. A Thread's `state` is
 *  the runtime's object for it (`newState`).
 */
object Threads:
  private def native: Nothing = throw new UnsupportedOperationException("scala-go native")

  def newState(thread: Object): Object = native
  /** Runs `thread.runOnGoroutine()` on a new goroutine. */
  def start(state: Object, daemon: Boolean): Unit = native
  /** The current goroutine's Thread, or null if it has none yet. */
  def current(): Object = native
  def attach(state: Object): Unit = native
  def isMainGoroutine(): Boolean = native
  def interrupt(state: Object): Unit = native
  def isInterrupted(state: Object, clear: Boolean): Boolean = native
  def sleep(millis: Long, nanos: Int): Unit = native
  def yieldNow(): Unit = native
  /** Whether the current thread holds the monitor of `obj`. */
  def holdsLock(obj: Object): Boolean = native
  /** The number of running threads started as Threads. */
  def activeCount(): Int = native

  /** Parks the current thread (whose state is given) for `nanos`, or until unparked if negative. */
  def park(state: Object, nanos: Long): Unit = native
  def unpark(state: Object): Unit = native

