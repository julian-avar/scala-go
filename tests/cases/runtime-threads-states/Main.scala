import java.util.concurrent.CountDownLatch
import java.util.concurrent.locks.LockSupport

/** Thread states while sleeping, waiting, joining, parked and blocked on a monitor; priorities of
 *  new threads (the creator's, capped by the group's maximum); messages of invalid arguments; the
 *  main thread ending with `main`.
 */
object Main:
  /** Waits (up to 5 s) until `t` is in `state`; the state it is in. */
  def awaitState(t: Thread, state: Thread.State): Thread.State =
    val deadline = System.nanoTime() + 5_000_000_000L
    while t.getState != state && System.nanoTime() < deadline do Thread.sleep(1)
    t.getState

  def daemon(body: => Unit): Thread =
    val t = new Thread(() => body)
    t.setDaemon(true)
    t.start()
    t

  def attempt(what: String)(body: => Any): Unit =
    val result =
      try String.valueOf(body)
      catch case e: Throwable => s"throws ${e.getClass.getName}${Option(e.getMessage).fold("")(": " + _)}"
    println(s"$what: $result")

  def main(args: Array[String]): Unit =
    println("-- states")
    val sleeper = daemon(Thread.sleep(60000))
    println(s"sleep: ${awaitState(sleeper, Thread.State.TIMED_WAITING)}")
    val lock = new Object
    println(s"wait: ${awaitState(daemon(lock.synchronized(lock.wait())), Thread.State.WAITING)}")
    println(s"timed wait: ${awaitState(daemon(lock.synchronized(lock.wait(60000))), Thread.State.TIMED_WAITING)}")
    println(s"join: ${awaitState(daemon(sleeper.join()), Thread.State.WAITING)}")
    println(s"timed join: ${awaitState(daemon(sleeper.join(60000)), Thread.State.TIMED_WAITING)}")
    val monitor = new Object
    monitor.synchronized {
      println(s"held monitor: ${awaitState(daemon(monitor.synchronized(())), Thread.State.BLOCKED)}")
    }
    println(s"park: ${awaitState(daemon(LockSupport.park()), Thread.State.WAITING)}")
    val short = new Thread(() => ())
    println(s"new: ${short.getState}")
    short.start()
    short.join()
    println(s"terminated: ${short.getState}")

    println("-- priorities")
    var childPriority = 0
    val parent = new Thread(() =>
      Thread.currentThread().setPriority(8)
      childPriority = new Thread(() => ()).getPriority
    )
    parent.start()
    parent.join()
    println(s"created by a thread of priority 8: $childPriority")
    val group = new ThreadGroup("capped")
    group.setMaxPriority(4)
    println(s"in a group of maximum priority 4: ${new Thread(group, () => ()).getPriority}")
    val capped = new Thread(group, () => ())
    capped.setPriority(9)
    println(s"set to 9 in that group: ${capped.getPriority}")
    println(s"main: ${Thread.currentThread().getPriority}")

    println("-- messages")
    attempt("Thread.sleep(-1)")(Thread.sleep(-1))
    attempt("Thread.sleep(0, 1000000)")(Thread.sleep(0, 1000000))
    attempt("wait(-1)")(lock.synchronized(lock.wait(-1)))
    attempt("Thread.ofPlatform().priority(42)")(Thread.ofPlatform().priority(42))

    println("-- main thread")
    val mainThread = Thread.currentThread()
    val returned = new CountDownLatch(1)
    new Thread(() =>
      returned.await()
      mainThread.join(5000)
      println(s"main thread after main returned: alive=${mainThread.isAlive} ${mainThread.getState}")
    ).start()
    println("main returns")
    returned.countDown()
