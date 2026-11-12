// Adapted from Scala Native's javalib (Apache-2.0, see javalib/LICENSE-scala-native.md).

package java.io

import java.nio.charset.Charset

import scalago.runtime.Console

class PrintStream(
    _out: OutputStream,
    autoFlush: Boolean,
    _charset: Charset
) extends FilterOutputStream(_out)
    with Appendable
    with Closeable {

  def this(out: OutputStream) =
    this(out, false, null: Charset)

  def this(out: OutputStream, autoFlush: Boolean) =
    this(out, autoFlush, null: Charset)

  def this(out: OutputStream, autoFlush: Boolean, encoding: String) =
    this(out, autoFlush, IOUtils.charsetForName(encoding))

  def this(file: File) =
    this(new BufferedOutputStream(new FileOutputStream(file)))

  def this(file: File, csn: String) =
    this(new BufferedOutputStream(new FileOutputStream(file)), false, csn)

  /** @since JDK 10 */
  def this(file: File, charset: Charset) =
    this(new BufferedOutputStream(new FileOutputStream(file)), false, charset)

  def this(fileName: String) =
    this(new File(fileName))

  def this(fileName: String, csn: String) =
    this(new File(fileName), csn)

  /** @since JDK 10 */
  def this(fileName: String, charset: Charset) =
    this(new File(fileName), charset)

  if (_out == null) throw new NullPointerException("Null output stream")

  private val charsetUsed =
    if (_charset == null) Charset.defaultCharset() else _charset

  /* We pass `this` as the output stream for the encoding writer so that
   * we can apply auto-flushing. Note that this will flush() more often
   * than required by the spec. It appears to be consistent with how the
   * JDK behaves.
   * scala-go: created on first use, so that the console streams (which
   * print directly) never create it.
   */
  private var encoderWriter: OutputStreamWriter = null

  private def encoder: OutputStreamWriter = {
    if (encoderWriter == null)
      encoderWriter = new OutputStreamWriter(this, charsetUsed)
    encoderWriter
  }

  private var closing: Boolean = false
  private[io] var closed: Boolean = false
  private var errorFlag: Boolean = false

  override def flush(): Unit = synchronized {
    ensureOpenAndTrapIOExceptions(out.flush())
  }

  override def close(): Unit = synchronized {
    trapIOExceptions {
      if (!closing) {
        closing = true
        if (encoderWriter != null) encoderWriter.close()
        flush()
        closed = true
        out.close()
      }
    }
  }

  def checkError(): Boolean = {
    if (closed) {
      /* Just check the error flag.
       * Common sense would tell us to look at the underlying writer's
       * checkError() result too (like we do in the not closed case below).
       * But the JDK does not behave like that. So we don't either.
       */
      errorFlag
    } else {
      flush()
      /* If the underlying writer is also a PrintStream, we also check its
       * checkError() result. This is not clearly specified by the JavaDoc,
       * but, experimentally, the JDK seems to behave that way.
       */
      errorFlag || (out match {
        case out: PrintStream => out.checkError()
        case _                => false
      })
    }
  }

  protected def setError(): Unit = errorFlag = true
  protected def clearError(): Unit = errorFlag = false

  /* Note that calling directly the write() methods will happily bypass the
   * potential lone high surrogate that is buffered in the underlying
   * OutputStreamWriter. This means that the following sequence of operations:
   *
   *   ps.print('\ud83d') // high surrogate of PILE OF POO
   *   ps.write('a')
   *   ps.print('\udca9') // low surrogate of PILE OF POO
   *
   * will result in the following bytes being emitted to the underlying stream:
   *
   *   a💩
   *
   * i.e., first the 'a', then the PILE OF POO.
   *
   * This is consistent with the behavior of the JDK.
   */

  override def write(b: Int): Unit = synchronized {
    ensureOpenAndTrapIOExceptions {
      out.write(b)
      if (autoFlush && b == '\n')
        flush()
    }

  }

  override def write(buf: Array[Byte], off: Int, len: Int): Unit =
    synchronized {
      ensureOpenAndTrapIOExceptions {
        out.write(buf, off, len)
        if (autoFlush)
          flush()
      }
    }

  def print(b: Boolean): Unit = printString(String.valueOf(b))
  def print(c: Char): Unit = printString(String.valueOf(c))
  def print(i: Int): Unit = printString(String.valueOf(i))
  def print(l: Long): Unit = printString(String.valueOf(l))
  def print(f: Float): Unit = printString(String.valueOf(f))
  def print(d: Double): Unit = printString(String.valueOf(d))
  def print(s: String): Unit = printString(if (s == null) "null" else s)
  def print(obj: AnyRef): Unit = printString(String.valueOf(obj))

  private def printString(s: String): Unit = synchronized {
    ensureOpenAndTrapIOExceptions {
      encoder.write(s)
      encoder.flushBuffer()
    }
  }

  def print(s: Array[Char]): Unit = synchronized {
    ensureOpenAndTrapIOExceptions {
      encoder.write(s)
      encoder.flushBuffer()
    }
  }

  def println(): Unit = synchronized {
    ensureOpenAndTrapIOExceptions {
      encoder.write(System.lineSeparator())
      encoder.flushBuffer()
      if (autoFlush)
        flush()
    }
  }

  def println(b: Boolean): Unit = synchronized { print(b); println() }
  def println(c: Char): Unit = synchronized { print(c); println() }
  def println(i: Int): Unit = synchronized { print(i); println() }
  def println(l: Long): Unit = synchronized { print(l); println() }
  def println(f: Float): Unit = synchronized { print(f); println() }
  def println(d: Double): Unit = synchronized { print(d); println() }
  def println(s: Array[Char]): Unit = synchronized { print(s); println() }
  def println(s: String): Unit = synchronized { print(s); println() }
  def println(obj: AnyRef): Unit = synchronized { print(obj); println() }

  def printf(fmt: String, args: Array[Object]): PrintStream =
    format(fmt, args)

  def printf(l: java.util.Locale, fmt: String, args: Array[Object]): PrintStream =
    format(l, fmt, args)

  def format(fmt: String, args: Array[Object]): PrintStream =
    format(java.util.Locale.getDefault(), fmt, args)

  // scala-go: formats into a String, printed with one write.
  def format(l: java.util.Locale, fmt: String, args: Array[Object]): PrintStream = {
    print(String.format(l, fmt, args*))
    this
  }

  def append(csq: CharSequence): PrintStream = {
    print(if (csq == null) "null" else csq.toString)
    this
  }

  def append(csq: CharSequence, start: Int, end: Int): PrintStream = {
    val csq1 = if (csq == null) "null" else csq
    print(csq1.subSequence(start, end).toString)
    this
  }

  def append(c: Char): PrintStream = {
    print(c)
    this
  }

  @inline private def trapIOExceptions(body: => Unit): Unit = {
    try {
      body
    } catch {
      case _: IOException => setError()
    }
  }

  @inline private def ensureOpenAndTrapIOExceptions(
      body: => Unit
  ): Unit = {
    if (closed) setError()
    else trapIOExceptions(body)
  }

  /** @since JDK 18 */
  def charset(): Charset = charsetUsed

  /** @since JDK 14 */
  def writeBytes(buf: Array[Byte]): Unit =
    this.write(buf, 0, buf.length)

}

object PrintStream {

  /** System.out and System.err (for javalib's System). */
  def console(stderr: Boolean): PrintStream =
    new ConsolePrintStream(stderr)

  /** scala-go: the console streams print straight into the runtime's
   *  output buffer: each print or println is one atomic write, with no
   *  encoder and no lock. The runtime flushes standard output at exit,
   *  before writing to standard error or reading standard input, and
   *  around calls into Go code, rather than after each line as the JDK's
   *  autoflushing System.out does.
   */
  private final class ConsolePrintStream(stderr: Boolean)
      extends PrintStream(
        new FileOutputStream(
          if (stderr) FileDescriptor.err else FileDescriptor.out
        ),
        false,
        null: Charset
      ) {

    private def put(x: Object): Unit =
      if (closed) setError() else Console.write(stderr, x, false)

    private def line(x: Object): Unit =
      if (closed) setError() else Console.write(stderr, x, true)

    override def print(b: Boolean): Unit = put(Boolean.box(b))
    override def print(c: Char): Unit = put(Char.box(c))
    override def print(i: Int): Unit = put(Int.box(i))
    override def print(l: Long): Unit = put(Long.box(l))
    override def print(f: Float): Unit = put(Float.box(f))
    override def print(d: Double): Unit = put(Double.box(d))
    override def print(s: String): Unit = put(s)
    override def print(obj: AnyRef): Unit = put(obj)
    override def print(s: Array[Char]): Unit = put(new String(s))

    override def println(): Unit = line("")
    override def println(b: Boolean): Unit = line(Boolean.box(b))
    override def println(c: Char): Unit = line(Char.box(c))
    override def println(i: Int): Unit = line(Int.box(i))
    override def println(l: Long): Unit = line(Long.box(l))
    override def println(f: Float): Unit = line(Float.box(f))
    override def println(d: Double): Unit = line(Double.box(d))
    override def println(s: Array[Char]): Unit = line(new String(s))
    override def println(s: String): Unit = line(s)
    override def println(obj: AnyRef): Unit = line(obj)

    override def flush(): Unit = Console.flush()
  }
}
