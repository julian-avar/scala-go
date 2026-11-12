import java.lang.ref.WeakReference

// Limitations and differences of the runtime (docs/_docs/user/limitations.md, "Language and
// runtime" and "Concurrency"; differences.md): each line prints what scala-go does today; the
// comment says what the JVM does. When one changes, this case fails: move the line to a case that
// runs on the JVM too (if it now matches), and update the docs.
class Finalizable:
  override def finalize(): Unit = Main.finalized = true

object Main:
  @volatile var finalized = false

  def attempt(what: String)(body: => Any): Unit =
    val result =
      try String.valueOf(body)
      catch case e: Throwable => s"throws ${e.getClass.getName}${Option(e.getMessage).fold("")(": " + _)}"
    println(s"$what: $result")

  @native def nativeMethod(): Int

  def main(args: Array[String]): Unit =
    // Classpath resources are not embedded. JVM: true
    attempt("resource found")(classOf[Main.type].getResourceAsStream("/Main.class") != null)
    // JVM: throws ClassNotFoundException
    attempt("Class.forName of a missing class")(Class.forName("no.such.Class").getName)

    // Weak references hold their referents strongly; finalizers never run. JVM: true, 0, true
    // (after a collection, nothing else refers to the referents)
    Finalizable()
    val weak = WeakReference[AnyRef](new Array[Byte](1 << 20))
    val map = java.util.WeakHashMap[AnyRef, Int]()
    map.put(new Array[Byte](1 << 20), 1)
    for _ <- 1 to 5 do
      Runtime.getRuntime.gc()
      Thread.sleep(20)
    attempt("weak reference cleared")(weak.get == null)
    attempt("WeakHashMap size")(map.size)
    attempt("finalized")(finalized)

    // Parallel streams run sequentially. JVM: true (on more than one processor)
    val threads = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
    java.util.stream.Stream.iterate(0, _ + 1).limit(10000).parallel()
      .forEach(_ => threads.add(Thread.currentThread().getName))
    attempt("parallel stream used other threads")(threads.size > 1 || Runtime.getRuntime.availableProcessors == 1)

    // Arrays of references do not know their element type. JVM: [Ljava.lang.String; for each
    attempt("Array[String] class")(Array("a").getClass.getName)
    attempt("Arrays.copyOf class")(java.util.Arrays.copyOf(Array("a"), 1).getClass.getName)
    attempt("toArray class")(java.util.List.of("a").toArray(new Array[String](0)).getClass.getName)
    attempt("ClassTag newArray class")(scala.reflect.classTag[String].newArray(1).getClass.getName)

    // Boxes have no identity. JVM: false, true
    val big1: AnyRef = Integer.valueOf(1000)
    val big2: AnyRef = Integer.valueOf(1000)
    attempt("Integer.valueOf(1000) eq Integer.valueOf(1000)")(big1 eq big2)
    val nan: Any = Double.NaN
    attempt("same boxed NaN == itself")(nan == nan)

    // JVM: Main$$$Lambda... (a hidden class name)
    val f: Int => Int = _ + 1
    attempt("lambda toString")(f.toString)

    // JVM: throws UnsatisfiedLinkError
    attempt("@native method")(nativeMethod())

    // Exception messages. JVM: the helpful NullPointerException message
    val nullString: String = null
    attempt("NPE message")(nullString.length)

    // JVM system properties are unset. JVM: false, false
    attempt("java.version is null")(System.getProperty("java.version") == null)
    attempt("java.specification.version is null")(System.getProperty("java.specification.version") == null)
