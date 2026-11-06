package java.io

trait Serializable

trait Closeable extends java.lang.AutoCloseable

trait Flushable:
  def flush(): Unit

class IOException(message: String, cause: Throwable) extends Exception(message, cause):
  def this(message: String) = this(message, null)
  def this(cause: Throwable) = this(if cause == null then null else cause.toString(), cause)
  def this() = this(null, null)

/** Writes to the process's standard output or standard error. */
class PrintStream(isStderr: Boolean) extends Object with Closeable with Flushable with Appendable:
  private def write(s: String): Unit =
    if isStderr then scalago.runtime.Console.writeStderr(s) else scalago.runtime.Console.writeStdout(s)

  def print(s: String): Unit = write(if s == null then "null" else s)
  def print(obj: Object): Unit = write(String.valueOf(obj))
  def print(b: Boolean): Unit = write(String.valueOf(b))
  def print(c: Char): Unit = write(String.valueOf(c))
  def print(i: Int): Unit = write(String.valueOf(i))
  def print(l: Long): Unit = write(String.valueOf(l))
  def print(f: Float): Unit = write(String.valueOf(f))
  def print(d: Double): Unit = write(String.valueOf(d))
  def print(s: Array[Char]): Unit = write(new String(s))

  def println(): Unit = write("\n")
  def println(s: String): Unit = { print(s); println() }
  def println(obj: Object): Unit = { print(obj); println() }
  def println(b: Boolean): Unit = { print(b); println() }
  def println(c: Char): Unit = { print(c); println() }
  def println(i: Int): Unit = { print(i); println() }
  def println(l: Long): Unit = { print(l); println() }
  def println(f: Float): Unit = { print(f); println() }
  def println(d: Double): Unit = { print(d); println() }
  def println(s: Array[Char]): Unit = { print(s); println() }

  def append(csq: CharSequence): PrintStream = { print(String.valueOf(csq)); this }
  def append(csq: CharSequence, start: Int, end: Int): PrintStream =
    print(String.valueOf(if csq == null then "null" else csq.subSequence(start, end)))
    this
  def append(c: Char): PrintStream = { print(c); this }

  def flush(): Unit = scalago.runtime.Console.flush()
  def close(): Unit = flush()
