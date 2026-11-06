package scalago.javalib.lang

// Compiled as package scalago.javalib.lang and emitted as java.lang (see build.mill). Code here must
// throw the JDK classes (`java.lang.X`), which link to these definitions.

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

  private var suppressed: Array[Throwable] = new Array[Throwable](0)

  def getMessage(): String = message
  def getLocalizedMessage(): String = getMessage()
  def getCause(): Throwable = cause

  def initCause(cause: Throwable): Throwable =
    this.cause = cause
    this

  override def toString(): String =
    val m = getLocalizedMessage()
    val n = getClass().getName()
    if m == null then n else n + ": " + m

  def printStackTrace(): Unit = System.err.println(this)

  def getStackTrace(): Array[StackTraceElement] = new Array[StackTraceElement](0)
  def setStackTrace(stackTrace: Array[StackTraceElement]): Unit = ()
  def fillInStackTrace(): Throwable = this

  def addSuppressed(exception: Throwable): Unit =
    if enableSuppression then
      val grown = new Array[Throwable](suppressed.length + 1)
      java.lang.System.arraycopy(suppressed, 0, grown, 0, suppressed.length)
      grown(suppressed.length) = exception
      suppressed = grown

  def getSuppressed(): Array[Throwable] =
    val copy = new Array[Throwable](suppressed.length)
    java.lang.System.arraycopy(suppressed, 0, copy, 0, suppressed.length)
    copy

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
