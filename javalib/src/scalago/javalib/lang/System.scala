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
  @static private val separator: String = SystemProperties.current.getProperty("line.separator")

  @static def lineSeparator(): String = separator

  /** The environment, as an unmodifiable map (`getenv(name)` is native). */
  @static def getenv(): java.util.Map[String, String] =
    val kv = Process.environment()
    val env = new java.util.HashMap[String, String]()
    var i = 0
    while i + 1 < kv.length do
      env.put(kv(i), kv(i + 1))
      i += 2
    java.util.Collections.unmodifiableMap(env)

  private def checkKey(key: String): Unit =
    if key == null then throw new java.lang.NullPointerException("key can't be null")
    if key.isEmpty() then throw new java.lang.IllegalArgumentException("key can't be empty")

  /** The properties: as on the JDK, a Properties object that programs may change or replace,
   *  initially the platform's (`Process.properties`).
   */
  private object SystemProperties:
    @volatile var current: java.util.Properties = defaults()

    def defaults(): java.util.Properties =
      val props = new java.util.Properties()
      val kv = Process.properties()
      var i = 0
      while i + 1 < kv.length do
        props.setProperty(kv(i), kv(i + 1))
        i += 2
      props

  @static def getProperties(): java.util.Properties = SystemProperties.current

  @static def setProperties(props: java.util.Properties): Unit =
    SystemProperties.current = if props == null then SystemProperties.defaults() else props

  @static def getProperty(key: String): String =
    checkKey(key)
    SystemProperties.current.getProperty(key)

  @static def getProperty(key: String, default: String): String =
    val value = getProperty(key)
    if value == null then default else value

  @static def setProperty(key: String, value: String): String =
    checkKey(key)
    if value == null then throw new java.lang.NullPointerException()
    SystemProperties.current.setProperty(key, value).asInstanceOf[String]

  @static def clearProperty(key: String): String =
    checkKey(key)
    SystemProperties.current.remove(key).asInstanceOf[String]
