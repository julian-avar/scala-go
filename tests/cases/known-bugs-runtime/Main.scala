import java.util.concurrent.CountDownLatch

import scalago.go.*

@GoImport("os")
object goos:
  def Executable(): String = native

class Quiet(message: String) extends RuntimeException(message, null, false, false)

/** Known bugs of threads and classes, found by the runtime-* cases. Each line prints what
 *  scala-go does today; the comment says what the JVM does. When one is fixed, this case fails:
 *  move the line to a runtime-* case, which runs on the JVM too.
 */
object Main:
  def attempt(what: String)(body: => Any): Unit =
    val result =
      try String.valueOf(body)
      catch case e: Throwable => s"throws ${e.getClass.getName}${Option(e.getMessage).fold("")(": " + _)}"
    println(s"$what: $result")

  def fail(e: Throwable): Unit = throw e

  /** Waits (up to 500 ms) until `t` is in `state`; returns the state it is in. */
  def awaitState(t: Thread, state: Thread.State): Thread.State =
    val deadline = System.nanoTime() + 500_000_000L
    while t.getState != state && System.nanoTime() < deadline do Thread.sleep(1)
    t.getState

  def daemon(body: => Unit): Thread =
    val t = new Thread(() => body)
    t.setDaemon(true)
    t.start()
    t

  def main(args: Array[String]): Unit =
    if args.sameElements(Array("failing-handler")) then
      Thread.setDefaultUncaughtExceptionHandler((_, _) => fail(new Quiet("from the handler")))
      val t = new Thread(() => fail(new Quiet("from the thread")), "worker")
      t.start()
      t.join()
      println("continued")
      return

    // Priorities are not inherited. JVM: 8 (the creating thread's), 4 (the group's maximum).
    var childPriority = 0
    val parent = new Thread(() =>
      Thread.currentThread().setPriority(8)
      childPriority = new Thread(() => ()).getPriority
    )
    parent.start()
    parent.join()
    attempt("priority of a thread created by a thread of priority 8")(childPriority)
    val group = new ThreadGroup("capped")
    group.setMaxPriority(4)
    attempt("initial priority in a group of maximum priority 4")(new Thread(group, () => ()).getPriority)

    // A ThreadGroup's uncaughtException is not called. JVM: "group handler: grouped in group".
    val handling = new ThreadGroup("handling"):
      override def uncaughtException(t: Thread, e: Throwable): Unit = println(s"group handler: ${t.getName} ${e.getMessage}")
    val grouped = new Thread(handling, () => fail(new Quiet("in group")), "grouped")
    grouped.start()
    grouped.join()
    // JVM: true (the group, when the thread has no handler of its own).
    Thread.setDefaultUncaughtExceptionHandler((_, _) => ())
    val inGroup = new Thread(handling, () => (), "in group")
    attempt("getUncaughtExceptionHandler is the group while a default handler is set")(inGroup.getUncaughtExceptionHandler eq handling)
    Thread.setDefaultUncaughtExceptionHandler(null)

    // An exception thrown by an uncaught-exception handler ends the program with a Go panic.
    // JVM: status 0, prints "Exception: Quiet thrown from the UncaughtExceptionHandler in thread
    // "worker"" and continues.
    attempt("a throwing uncaught-exception handler") {
      val child = new ProcessBuilder(goos.Executable(), "failing-handler").redirectErrorStream(true).start()
      val out = new String(child.getInputStream.readAllBytes(), "UTF-8")
      s"status ${child.waitFor()}, Go panic: ${out.contains("panic: Quiet: from the handler")}"
    }

    // getState reports RUNNABLE for threads blocked other than by LockSupport.park.
    // JVM: TIMED_WAITING, WAITING, TIMED_WAITING, WAITING, BLOCKED.
    val sleeper = daemon(Thread.sleep(60000))
    attempt("state in sleep")(awaitState(sleeper, Thread.State.TIMED_WAITING))
    val lock = new Object
    attempt("state in wait")(awaitState(daemon(lock.synchronized(lock.wait())), Thread.State.WAITING))
    attempt("state in timed wait")(awaitState(daemon(lock.synchronized(lock.wait(60000))), Thread.State.TIMED_WAITING))
    attempt("state in join")(awaitState(daemon(sleeper.join()), Thread.State.WAITING))
    val monitor = new Object
    monitor.synchronized {
      attempt("state entering a held monitor")(awaitState(daemon(monitor.synchronized(())), Thread.State.BLOCKED))
    }

    // Class names. JVM: int[], java.lang.String[][], interface java.lang.Runnable.
    attempt("getTypeName of int[]")(classOf[Array[Int]].getTypeName)
    attempt("getTypeName of String[][]")(classOf[Array[Array[String]]].getTypeName)
    attempt("toString of an interface")(classOf[Runnable].toString)

    // Exception messages. JVM: "timeout value is negative"; no message.
    attempt("Thread.sleep(-1)")(Thread.sleep(-1))
    attempt("Thread.ofPlatform().priority(42)")(Thread.ofPlatform().priority(42))

    // The main thread stays alive after main returns: joining it waits until the timeout.
    // JVM: false.
    val mainThread = Thread.currentThread()
    val returned = new CountDownLatch(1)
    new Thread(() =>
      returned.await()
      mainThread.join(1000)
      println(s"main thread alive after main returned: ${mainThread.isAlive}")
    ).start()
    println("main returns")
    returned.countDown()
