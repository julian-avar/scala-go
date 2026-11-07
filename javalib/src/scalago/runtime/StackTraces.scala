package scalago.runtime

/** The native side of Throwable's stack traces (see runtime/rt/stacktrace.go). The linker
 *  replaces these methods with Go code. A captured trace is an opaque Go value; its frames are
 *  symbolized on first access.
 */
object StackTraces:
  private def native: Nothing = throw new UnsupportedOperationException("scala-go native")

  /** The call stack of the caller (`Throwable.fillInStackTrace`). */
  def capture(): Object = native
  def depth(trace: Object): Int = native
  def className(trace: Object, i: Int): String = native
  def methodName(trace: Object, i: Int): String = native
  /** null if unknown. */
  def fileName(trace: Object, i: Int): String = native
  /** -1 if unknown. */
  def lineNumber(trace: Object, i: Int): Int = native
