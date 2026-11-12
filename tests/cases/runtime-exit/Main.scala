import java.util.concurrent.{CountDownLatch, CyclicBarrier, TimeUnit}

/** Runtime.exit from another thread while main waits: the shutdown hooks run (concurrently,
 *  registered and removed as on the JVM), other threads stop, pending `finally` blocks are skipped,
 *  standard output is flushed (also a last line without a newline), and the status is the one
 *  given (exit-status.txt).
 */
object Main:
  @volatile var hookFindings = ""

  def attempt(body: => Any): String =
    try String.valueOf(body)
    catch case e: Exception => s"${e.getClass.getName}: ${e.getMessage}"

  def main(args: Array[String]): Unit =
    println(s"args: ${args.length}")
    val rt = Runtime.getRuntime

    // Two hooks meet at a barrier: they run at the same time, each in its own thread.
    val barrier = new CyclicBarrier(2)
    val first = new Thread(() =>
      barrier.await(10, TimeUnit.SECONDS): Unit
      println(s"hooks ran concurrently; in the other hook: $hookFindings")
      print("last output, without a newline")
    )
    val second = new Thread(() =>
      // During shutdown, hooks can be neither added nor removed.
      hookFindings = s"add ${attempt(rt.addShutdownHook(new Thread(() => ())))}, remove ${attempt(rt.removeShutdownHook(first))}"
      barrier.await(10, TimeUnit.SECONDS)
      ()
    )
    rt.addShutdownHook(first)
    rt.addShutdownHook(second)

    // Registration errors, and a removed hook that does not run.
    println(s"add again: ${attempt(rt.addShutdownHook(first))}")
    val never = new CountDownLatch(1)
    val running = new Thread(() => never.await())
    running.setDaemon(true)
    running.start()
    println(s"add a running thread: ${attempt(rt.addShutdownHook(running))}")
    println(s"add null: ${try rt.addShutdownHook(null) catch case e: NullPointerException => "NullPointerException"}")
    val removed = new Thread(() => println("the removed hook ran"))
    rt.addShutdownHook(removed)
    println(s"remove: ${rt.removeShutdownHook(removed)} ${rt.removeShutdownHook(removed)}")

    // A non-daemon thread that never ends: exit stops it.
    new Thread(() =>
      never.await()
      println("the blocked thread ended")
    ).start()

    val exiter = new Thread(() =>
      try
        println("exiting from another thread")
        rt.exit(42)
      finally println("finally in the exiting thread")
    )
    try
      exiter.start()
      never.await()
    finally println("finally in main")
