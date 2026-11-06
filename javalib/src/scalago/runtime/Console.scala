package scalago.runtime

/** Entry points into the Go runtime. The linker replaces these methods with native code; the
 *  bodies here only run if the class is used on the JVM by mistake.
 */
object Console:
  def writeStdout(s: String): Unit = throw new UnsupportedOperationException("scala-go native")
  def writeStderr(s: String): Unit = throw new UnsupportedOperationException("scala-go native")
  def flush(): Unit = throw new UnsupportedOperationException("scala-go native")
