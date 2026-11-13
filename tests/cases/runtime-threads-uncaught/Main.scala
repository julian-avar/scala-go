/** Uncaught exceptions in threads other than main: printed as `Exception in thread "<name>"`
 *  without a handler (on the current System.err), or given to the thread's handler, else the
 *  default handler. The program continues, and exits with status 0.
 */
class Quiet(message: String) extends RuntimeException(message, null, false, false)

object Main:
  def run(t: Thread): Unit =
    t.start()
    t.join()

  def fail(e: Throwable): Unit = throw e

  def thrower(name: String): Thread = new Thread(() => fail(new Quiet(s"from $name")), name)

  def main(args: Array[String]): Unit =
    // No handler: printed on standard error (here without frames: Quiet has no stack trace).
    run(thrower("plain"))
    println("main continues")

    // Printed on the System.err of the moment.
    val captured = new java.io.ByteArrayOutputStream
    val err = System.err
    System.setErr(new java.io.PrintStream(captured, true))
    try run(thrower("redirected"))
    finally System.setErr(err)
    println(s"captured: ${captured.toString.trim}")

    // The thread's own handler.
    val own = thrower("own")
    own.setUncaughtExceptionHandler((t, e) => println(s"own handler: ${t.getName} ${e.getMessage} same thread: ${t eq Thread.currentThread()}"))
    println(s"getUncaughtExceptionHandler is set: ${own.getUncaughtExceptionHandler != null}")
    run(own)

    // A handler given by a builder.
    run(Thread.ofPlatform().name("built").uncaughtExceptionHandler((t, e) => println(s"builder's handler: ${t.getName} ${e.getMessage}"))
      .unstarted(() => fail(new Quiet("from built"))))

    // The default handler, for threads without their own; a thread's handler still wins.
    Thread.setDefaultUncaughtExceptionHandler((t, e) => println(s"default handler: ${t.getName} ${e.getMessage}"))
    println(s"getDefaultUncaughtExceptionHandler is set: ${Thread.getDefaultUncaughtExceptionHandler != null}")
    run(thrower("defaulted"))
    val preferred = thrower("preferred")
    preferred.setUncaughtExceptionHandler((t, e) => println(s"thread's handler before the default: ${t.getName}"))
    run(preferred)

    // Errors too, and a virtual thread.
    run(new Thread(() => fail(new AssertionError("an Error")), "error"))
    Thread.ofVirtual().start(() => fail(new Quiet("from a virtual thread"))).join()

    // A group's uncaughtException comes between the thread's handler and the default one; the
    // group is what getUncaughtExceptionHandler returns when the thread has no handler.
    val handling = new ThreadGroup("handling"):
      override def uncaughtException(t: Thread, e: Throwable): Unit = println(s"group handler: ${t.getName} ${e.getMessage}")
    run(new Thread(handling, () => fail(new Quiet("in group")), "grouped"))
    val inGroup = new Thread(handling, () => (), "in group")
    println(s"handler of a thread without one is its group: ${inGroup.getUncaughtExceptionHandler eq handling}")

    // An exception thrown by a handler is reported and ignored.
    val throwing = thrower("throwing handler")
    throwing.setUncaughtExceptionHandler((_, _) => fail(new Quiet("from the handler")))
    run(throwing)
    println("after the throwing handler")

    Thread.setDefaultUncaughtExceptionHandler(null)

    // A thread that ended with an exception is terminated.
    val ended = thrower("ended")
    ended.setUncaughtExceptionHandler((_, _) => ())
    run(ended)
    println(s"after an uncaught exception: ${ended.getState} alive=${ended.isAlive}")
    println("main returns")
