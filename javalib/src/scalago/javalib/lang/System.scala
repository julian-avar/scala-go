package scalago.javalib.lang

import scala.annotation.static

import java.io.{BufferedInputStream, FileDescriptor, FileInputStream, InputStream, PrintStream}

import scalago.runtime.Process

/** java.lang.System: the standard streams and the properties. The other methods
 *  (`currentTimeMillis`, `arraycopy`, `exit`, `getenv`, ...) are implemented natively by the linker.
 */
final class System private ()

object System:
  /** The console streams print straight into the runtime's output buffer (see
   *  `java.io.PrintStream.console`).
   */
  @static var out: PrintStream = PrintStream.console(false)
  @static var err: PrintStream = PrintStream.console(true)
  @static var in: InputStream = new BufferedInputStream(new FileInputStream(FileDescriptor.in))

  @static def setOut(stream: PrintStream): Unit = out = stream
  @static def setErr(stream: PrintStream): Unit = err = stream
  @static def setIn(stream: InputStream): Unit = in = stream

  /** As in the JDK, the line separator is fixed at startup: setting the property does not change it. */
  @static private val separator: String = Process.getProperty("line.separator")

  @static def lineSeparator(): String = separator

  private def checkKey(key: String): Unit =
    if key == null then throw new java.lang.NullPointerException("key can't be null")
    if key.isEmpty() then throw new java.lang.IllegalArgumentException("key can't be empty")

  @static def getProperty(key: String): String =
    checkKey(key)
    Process.getProperty(key)

  @static def getProperty(key: String, default: String): String =
    val value = getProperty(key)
    if value == null then default else value

  @static def setProperty(key: String, value: String): String =
    checkKey(key)
    if value == null then throw new java.lang.NullPointerException()
    Process.setProperty(key, value)

  @static def clearProperty(key: String): String =
    checkKey(key)
    Process.setProperty(key, null)
