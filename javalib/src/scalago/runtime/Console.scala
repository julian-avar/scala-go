package scalago.runtime

/** The process's standard output and error (see runtime/rt/console.go). Standard output is
 *  buffered by the runtime, which flushes it at exit, before writing to standard error, before
 *  reading standard input and around calls into Go code.
 */
object Console:
  private def native: Nothing = throw new UnsupportedOperationException("scala-go native")

  /** Prints `String.valueOf(x)`, then a line separator if `newline` is set: one atomic write. */
  def write(stderr: Boolean, x: Object, newline: Boolean): Unit = native
  def flush(): Unit = native
