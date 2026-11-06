package scalago.runtime

/** The native side of java.lang.Thread (see runtime/rt/thread.go). The linker replaces these
 *  methods with Go code.
 */
object Threads:
  private def native: Nothing = throw new UnsupportedOperationException("scala-go native")

  /** Runs `thread.runOnGoroutine()` on a new goroutine. */
  def start(thread: Object, daemon: Boolean): Unit = native
  /** The current goroutine's Thread, or null if it has none yet. */
  def current(): Object = native
  def attach(thread: Object): Unit = native
  def isMainGoroutine(): Boolean = native
  def interrupt(thread: Object): Unit = native
  def isInterrupted(thread: Object, clear: Boolean): Boolean = native
  def sleep(millis: Long, nanos: Int): Unit = native
  def yieldNow(): Unit = native

/** Compiler intrinsics for javalib. `compareAndSet(field, expected, update)` atomically sets a
 *  `@volatile` field (the first argument must be a field selection) to `update` if it holds
 *  `expected`. The plugin replaces calls with IR `CompareAndSet`.
 */
object Intrinsics:
  private def native: Nothing = throw new UnsupportedOperationException("scala-go intrinsic")

  def compareAndSet(field: Int, expected: Int, update: Int): Boolean = native
  def compareAndSet(field: Long, expected: Long, update: Long): Boolean = native
  def compareAndSet(field: Boolean, expected: Boolean, update: Boolean): Boolean = native
  def compareAndSet(field: AnyRef, expected: AnyRef, update: AnyRef): Boolean = native
