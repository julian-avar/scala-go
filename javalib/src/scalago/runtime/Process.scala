package scalago.runtime

/** The native side of java.lang.System's properties and environment, and of java.lang.Runtime
 *  (see runtime/rt/process.go and console.go).
 */
object Process:
  private def native: Nothing = throw new UnsupportedOperationException("scala-go native")

  /** The platform's system properties, as alternating names and values. */
  def properties(): Array[String] = native
  /** The environment as alternating names and values. */
  def environment(): Array[String] = native

  /** Runs the shutdown hooks, flushes standard output and exits. */
  def exit(status: Int): Unit = native
  /** Flushes standard output and exits, without running the shutdown hooks. */
  def halt(status: Int): Unit = native
  /** Registers a Thread (its runtime state) to start at exit: 0 if done, 1 if already registered, 2 if exiting. */
  def addShutdownHook(threadState: Object): Int = native
  /** 0 if removed, 1 if it was not registered, 2 if exiting. */
  def removeShutdownHook(threadState: Object): Int = native

  def availableProcessors(): Int = native
  def totalMemory(): Long = native
  def freeMemory(): Long = native
  def gc(): Unit = native
