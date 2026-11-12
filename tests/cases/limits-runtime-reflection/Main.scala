import scala.reflect.ClassTag
import scala.reflect.Selectable.reflectiveSelectable

// Limitations (docs/_docs/user/limitations.md, "Language and runtime" and "Concurrency"): runtime
// reflection, APIs with no Go counterpart, ClassTag type tests, structural types, TrieMap and
// atomic field updaters. Each use is a link error. When one starts
// to link, this case fails: move the use to a case that runs it, and update the docs.
object Main:
  class Point(@volatile var x: Int)

  def matches[T: ClassTag](a: Any): Boolean = a match
    case _: T => true
    case _    => false

  def main(args: Array[String]): Unit =
    val c = classOf[Point]
    // Reflection
    println(c.getMethods.length)
    println(c.getDeclaredFields.length)
    println(c.getConstructors.length)
    println(c.getMethod("x").invoke(Point(1)))
    println(c.getAnnotations.length)
    println(java.lang.reflect.Proxy.newProxyInstance(null, Array(classOf[Runnable]), null))
    println(new java.io.ObjectOutputStream(new java.io.ByteArrayOutputStream()))
    println(ClassLoader.getSystemClassLoader.loadClass("Main"))
    println(java.lang.invoke.MethodHandles.lookup())
    // No Go counterpart: serialization, JMX, SecurityManager, JNI
    println(new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(Array.emptyByteArray)))
    println(java.lang.management.ManagementFactory.getRuntimeMXBean)
    println(java.lang.management.ManagementFactory.getPlatformMBeanServer)
    println(System.getSecurityManager)
    System.loadLibrary("z")
    // Class
    println(c.isInstance(Point(1)))
    println(c.cast(Point(1)))
    println(c.getSuperclass)
    println(c.getInterfaces.length)
    println(c.isInterface)
    println(c.isEnum)
    println(c.getEnumConstants)
    println(c.getCanonicalName)
    // Type tests with a ClassTag
    println(matches[String]("a"))
    // Structural types
    val s: { def x: Int } = Point(1)
    println(s.x)
    // TrieMap, atomic field updaters
    println(scala.collection.concurrent.TrieMap(1 -> 2))
    println(java.util.concurrent.atomic.AtomicIntegerFieldUpdater.newUpdater(c, "x"))
    println(java.util.concurrent.atomic.AtomicReferenceFieldUpdater.newUpdater(classOf[Main.type], classOf[String], "s"))
