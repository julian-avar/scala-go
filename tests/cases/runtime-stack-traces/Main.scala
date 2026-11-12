import scala.util.control.NoStackTrace

class AppException(message: String, cause: Throwable) extends Exception(message, cause) {
  def this(message: String) = this(message, null)
}

class Quiet extends RuntimeException("quiet") with NoStackTrace

class Widget(n: Int) {
  if (n < 0) throw new IllegalArgumentException("negative")
}

object Main {
  /** The frames of `e`'s stack trace in this file: JVM and Go traces differ elsewhere. */
  def frames(e: Throwable): String =
    e.getStackTrace.filter(_.getFileName == "Main.scala").mkString(" < ")

  def report(what: String)(body: => Any): Unit =
    try {
      body
      println(s"$what: no exception")
    } catch {
      case e: Throwable => println(s"$what: ${e.getClass.getName}: ${frames(e)}")
    }

  def deep(n: Int): Int =
    if (n == 0) throw new IllegalStateException("boom")
    else deep(n - 1) + 1

  def npe(s: String): Int = s.length

  def index(a: Array[Int], i: Int): Int = a(i)

  def divide(a: Int, b: Int): Int = a / b

  def cast(x: Any): String = x.asInstanceOf[String]

  def inTry(): Int =
    try {
      val x = 1
      deep(x)
    } finally {
      println("finally")
    }

  def custom(): Nothing = throw new AppException("app")

  def refill(e: Throwable): Throwable = e.fillInStackTrace()

  def level3(): Unit = throw new IllegalStateException("root")

  def level2(): Unit =
    try level3()
    catch { case e: IllegalStateException => throw new RuntimeException("wrapped", e) }

  def level1(): Unit = level2()

  def main(args: Array[String]): Unit = {
    report("throw")(deep(2))
    try deep(0)
    catch { case e: IllegalStateException => println(e.getStackTrace()(0)) }
    report("lambda")(List(1, 2, 3).map(x => if (x == 2) throw new IllegalArgumentException("two") else x))
    report("null")(npe(null))
    report("index")(index(Array(1, 2), 2))
    report("divide")(divide(1, 0))
    report("cast")(cast(42))
    report("in try")(inTry())
    report("constructor")(new Widget(-1))
    report("custom")(custom())

    val saved = new RuntimeException("saved")
    println(frames(saved))
    println(frames(refill(saved)))

    // No stack trace: NoStackTrace, writableStackTrace = false, ControlThrowables.
    println(new Quiet().getStackTrace.length)
    println(new RuntimeException("x", null, false, false) {}.getStackTrace.length)
    val unwritable = new RuntimeException("x", null, false, false) {}
    unwritable.setStackTrace(Array(new StackTraceElement("A", "b", "A.scala", 1)))
    println(unwritable.getStackTrace.length)

    // getStackTrace and setStackTrace copy.
    val e = new Exception("set")
    e.setStackTrace(Array(new StackTraceElement("pkg.Cls", "run", "Cls.scala", 12), new StackTraceElement("pkg.Cls", "<init>", null, -1)))
    e.getStackTrace()(0) = null
    println(e.getStackTrace.mkString(" < "))
    try e.setStackTrace(Array(null))
    catch { case n: NullPointerException => println(n.getMessage) }

    // StackTraceElement.
    val a = new StackTraceElement("C", "m", "C.scala", 3)
    val b = new StackTraceElement("C", "m", "C.scala", 3)
    println(s"${a == b} ${a.hashCode == b.hashCode} ${a == new StackTraceElement("C", "m", "C.scala", 4)}")
    println(new StackTraceElement("C", "m", "C.scala", -1))
    println(new StackTraceElement("C", "m", null, 7))
    println(new StackTraceElement("C", "m", "C.java", -2))
    println(new StackTraceElement("loader", "mod", "1.0", "C", "m", "C.java", 9))
    println(s"${a.getClassName} ${a.getMethodName} ${a.getFileName} ${a.getLineNumber} ${a.isNativeMethod}")

    // printStackTrace: causes, suppressed exceptions and the frames they have in common.
    try level1()
    catch {
      case e: RuntimeException =>
        e.addSuppressed(new Exception("suppressed"))
        e.printStackTrace(System.out)
    }
    val loop1 = new Exception("loop1")
    val loop2 = new Exception("loop2", loop1)
    loop1.initCause(loop2)
    loop1.setStackTrace(Array.empty)
    loop2.setStackTrace(Array.empty)
    loop1.printStackTrace(System.out)

    // An uncaught exception in a thread goes to its handler.
    val t = new Thread(() => { deep(0); () })
    t.setUncaughtExceptionHandler((th, e) => println(s"${th.getName}: $e: ${frames(e)}"))
    t.start()
    t.join()
  }
}
