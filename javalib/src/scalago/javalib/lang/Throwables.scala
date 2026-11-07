package scalago.javalib.lang

// Compiled as package scalago.javalib.lang and emitted as java.lang (see build.mill). Code here must
// throw the JDK classes (`java.lang.X`), which link to these definitions.

/** java.lang.Throwable. As on the JVM, the constructor captures the stack (`fillInStackTrace`)
 *  unless `writableStackTrace` is false. The capture is a cheap list of Go program counters
 *  (`backtrace`), symbolized into StackTraceElements only when the trace is first asked for (see
 *  runtime/rt/stacktrace.go).
 */
class Throwable protected (
    private var message: String,
    private var cause: Throwable,
    enableSuppression: scala.Boolean,
    writableStackTrace: scala.Boolean
) extends Object
    with java.io.Serializable:
  def this(message: String, cause: Throwable) = this(message, cause, true, true)
  def this(message: String) = this(message, null, true, true)
  def this(cause: Throwable) = this(if cause == null then null else cause.toString(), cause, true, true)
  def this() = this(null, null, true, true)

  /** Created on the first `addSuppressed`. */
  private var suppressed: Array[Throwable] = null
  /** The stack captured by `fillInStackTrace` (a Go value), until it is symbolized. */
  private var backtrace: Object = null
  /** The stack trace, once symbolized or set (null before). */
  private var stackTrace: Array[StackTraceElement] = null

  if writableStackTrace then fillInStackTrace()

  def getMessage(): String = message
  def getLocalizedMessage(): String = getMessage()
  def getCause(): Throwable = cause

  def initCause(cause: Throwable): Throwable =
    if cause eq this then throw new java.lang.IllegalArgumentException("Self-causation not permitted")
    this.cause = cause
    this

  override def toString(): String =
    val m = getLocalizedMessage()
    val n = getClass().getName()
    if m == null then n else n + ": " + m

  def fillInStackTrace(): Throwable =
    if writableStackTrace then
      backtrace = scalago.runtime.StackTraces.capture()
      stackTrace = null
    this

  def getStackTrace(): Array[StackTraceElement] = ourStackTrace().clone()

  def setStackTrace(stackTrace: Array[StackTraceElement]): Unit =
    val copy = stackTrace.clone()
    var i = 0
    while i < copy.length do
      if copy(i) == null then throw new java.lang.NullPointerException("stackTrace[" + i + "]")
      i += 1
    if writableStackTrace then
      synchronized {
        this.stackTrace = copy
        backtrace = null
      }

  private def ourStackTrace(): Array[StackTraceElement] = synchronized {
    if stackTrace == null then
      stackTrace = Throwable.symbolize(backtrace)
      backtrace = null
    stackTrace
  }

  def addSuppressed(exception: Throwable): Unit =
    if exception eq this then throw new java.lang.IllegalArgumentException("Self-suppression not permitted")
    if exception == null then throw new java.lang.NullPointerException("Cannot suppress a null exception.")
    if enableSuppression then
      synchronized {
        val n = if suppressed == null then 0 else suppressed.length
        val grown = new Array[Throwable](n + 1)
        if n > 0 then java.lang.System.arraycopy(suppressed, 0, grown, 0, n)
        grown(n) = exception
        suppressed = grown
      }

  def getSuppressed(): Array[Throwable] = synchronized {
    if suppressed == null then new Array[Throwable](0) else suppressed.clone()
  }

  /** Prints the stack trace to standard error, in the JVM's format. */
  def printStackTrace(): Unit = printStackTrace(java.lang.System.err)

  /** Prints this Throwable and its stack trace, then those of its suppressed exceptions and its
   *  causes, in the JVM's format: frames in common with the enclosing trace are elided
   *  (`... n more`), and a cycle is printed as `[CIRCULAR REFERENCE: ...]`.
   */
  def printStackTrace(s: java.io.PrintStream): Unit = s.print(stackTraceText())

  def printStackTrace(s: java.io.PrintWriter): Unit = s.print(stackTraceText())

  /** What printStackTrace prints, built first so that it is written in one call (and not
   *  interleaved with other output).
   */
  private def stackTraceText(): String =
    val sb = new java.lang.StringBuilder()
    val seen = new Throwable.IdentitySet
    seen.add(this)
    sb.append(this).append('\n')
    val trace = ourStackTrace()
    var i = 0
    while i < trace.length do
      sb.append("\tat ").append(trace(i)).append('\n')
      i += 1
    printEnclosed(sb, trace, seen, "")
    sb.toString()

  /** Appends the suppressed exceptions and the cause of a Throwable whose trace is `trace`. */
  private def printEnclosed(sb: java.lang.StringBuilder, trace: Array[StackTraceElement], seen: Throwable.IdentitySet,
      prefix: String): Unit =
    val suppressed = getSuppressed()
    var i = 0
    while i < suppressed.length do
      suppressed(i).printEnclosedStackTrace(sb, trace, "Suppressed: ", prefix + "\t", seen)
      i += 1
    val cause = getCause()
    if cause != null then cause.printEnclosedStackTrace(sb, trace, "Caused by: ", prefix, seen)

  private def printEnclosedStackTrace(sb: java.lang.StringBuilder, enclosingTrace: Array[StackTraceElement],
      caption: String, prefix: String, seen: Throwable.IdentitySet): Unit =
    if seen.contains(this) then sb.append(prefix).append(caption).append("[CIRCULAR REFERENCE: ").append(this).append("]\n")
    else
      seen.add(this)
      val trace = ourStackTrace()
      // Frames in common with the enclosing trace, at the bottom.
      var m = trace.length - 1
      var n = enclosingTrace.length - 1
      while m >= 0 && n >= 0 && trace(m) == enclosingTrace(n) do
        m -= 1
        n -= 1
      val inCommon = trace.length - 1 - m
      sb.append(prefix).append(caption).append(this).append('\n')
      var i = 0
      while i <= m do
        sb.append(prefix).append("\tat ").append(trace(i)).append('\n')
        i += 1
      if inCommon != 0 then sb.append(prefix).append("\t... ").append(inCommon).append(" more\n")
      printEnclosed(sb, trace, seen, prefix)

object Throwable:
  /** The frames of a captured stack (`StackTraces.capture`), or none for null. */
  private def symbolize(backtrace: Object): Array[StackTraceElement] =
    import scalago.runtime.StackTraces
    val n = if backtrace == null then 0 else StackTraces.depth(backtrace)
    val trace = new Array[StackTraceElement](n)
    var i = 0
    while i < n do
      trace(i) = new StackTraceElement(StackTraces.className(backtrace, i), StackTraces.methodName(backtrace, i),
        StackTraces.fileName(backtrace, i), StackTraces.lineNumber(backtrace, i))
      i += 1
    trace

  /** The Throwables already printed by printStackTrace (compared by identity). */
  private final class IdentitySet:
    private var elems = new Array[Throwable](4)
    private var size = 0
    def contains(t: Throwable): scala.Boolean =
      var i = 0
      while i < size && (elems(i) ne t) do i += 1
      i < size
    def add(t: Throwable): Unit =
      if size == elems.length then
        val grown = new Array[Throwable](size * 2)
        java.lang.System.arraycopy(elems, 0, grown, 0, size)
        elems = grown
      elems(size) = t
      size += 1

class Exception protected (message: String, cause: Throwable, enableSuppression: scala.Boolean, writableStackTrace: scala.Boolean)
    extends Throwable(message, cause, enableSuppression, writableStackTrace):
  def this(message: String, cause: Throwable) = this(message, cause, true, true)
  def this(message: String) = this(message, null, true, true)
  def this(cause: Throwable) = this(if cause == null then null else cause.toString(), cause, true, true)
  def this() = this(null, null, true, true)

class RuntimeException protected (message: String, cause: Throwable, enableSuppression: scala.Boolean, writableStackTrace: scala.Boolean)
    extends Exception(message, cause, enableSuppression, writableStackTrace):
  def this(message: String, cause: Throwable) = this(message, cause, true, true)
  def this(message: String) = this(message, null, true, true)
  def this(cause: Throwable) = this(if cause == null then null else cause.toString(), cause, true, true)
  def this() = this(null, null, true, true)

class Error protected (message: String, cause: Throwable, enableSuppression: scala.Boolean, writableStackTrace: scala.Boolean)
    extends Throwable(message, cause, enableSuppression, writableStackTrace):
  def this(message: String, cause: Throwable) = this(message, cause, true, true)
  def this(message: String) = this(message, null, true, true)
  def this(cause: Throwable) = this(if cause == null then null else cause.toString(), cause, true, true)
  def this() = this(null, null, true, true)

class IllegalArgumentException(message: String, cause: Throwable) extends RuntimeException(message, cause):
  def this(message: String) = this(message, null)
  def this(cause: Throwable) = this(if cause == null then null else cause.toString(), cause)
  def this() = this(null, null)

class IllegalStateException(message: String, cause: Throwable) extends RuntimeException(message, cause):
  def this(message: String) = this(message, null)
  def this(cause: Throwable) = this(if cause == null then null else cause.toString(), cause)
  def this() = this(null, null)

class UnsupportedOperationException(message: String, cause: Throwable) extends RuntimeException(message, cause):
  def this(message: String) = this(message, null)
  def this(cause: Throwable) = this(if cause == null then null else cause.toString(), cause)
  def this() = this(null, null)

class ArithmeticException(message: String) extends RuntimeException(message):
  def this() = this(null)

class NullPointerException(message: String) extends RuntimeException(message):
  def this() = this(null)

class ClassCastException(message: String) extends RuntimeException(message):
  def this() = this(null)

class NegativeArraySizeException(message: String) extends RuntimeException(message):
  def this() = this(null)

class ArrayStoreException(message: String) extends RuntimeException(message):
  def this() = this(null)

class NumberFormatException(message: String) extends IllegalArgumentException(message):
  def this() = this(null)

class IndexOutOfBoundsException(message: String) extends RuntimeException(message):
  def this() = this(null)
  def this(index: Int) = this("Index out of range: " + index)

class ArrayIndexOutOfBoundsException(message: String) extends IndexOutOfBoundsException(message):
  def this() = this(null)
  def this(index: Int) = this("Array index out of range: " + index)

class StringIndexOutOfBoundsException(message: String) extends IndexOutOfBoundsException(message):
  def this() = this(null)
  def this(index: Int) = this("String index out of range: " + index)

class CloneNotSupportedException(message: String) extends Exception(message):
  def this() = this(null)

class InterruptedException(message: String) extends Exception(message):
  def this() = this(null)

class ReflectiveOperationException(message: String, cause: Throwable) extends Exception(message, cause):
  def this(message: String) = this(message, null)
  def this() = this(null, null)

class ClassNotFoundException(message: String) extends ReflectiveOperationException(message):
  def this() = this(null)

class AssertionError(message: String, cause: Throwable) extends Error(message, cause):
  def this(detail: Object) =
    this(String.valueOf(detail), if detail.isInstanceOf[Throwable] then detail.asInstanceOf[Throwable] else null)
  def this() = this(null, null)

class StackOverflowError(message: String) extends Error(message):
  def this() = this(null)

class OutOfMemoryError(message: String) extends Error(message):
  def this() = this(null)

class IllegalMonitorStateException(message: String) extends RuntimeException(message):
  def this() = this(null)

class IllegalThreadStateException(message: String) extends IllegalArgumentException(message):
  def this() = this(null)

class LinkageError(message: String, cause: Throwable) extends Error(message, cause):
  def this(message: String) = this(message, null)
  def this() = this(null, null)

class NoClassDefFoundError(message: String) extends LinkageError(message):
  def this() = this(null)

class IncompatibleClassChangeError(message: String) extends LinkageError(message):
  def this() = this(null)

class NoSuchMethodError(message: String) extends IncompatibleClassChangeError(message):
  def this() = this(null)

class NoSuchFieldError(message: String) extends IncompatibleClassChangeError(message):
  def this() = this(null)
