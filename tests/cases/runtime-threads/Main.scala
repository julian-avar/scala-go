import java.util.concurrent.{CountDownLatch, LinkedBlockingQueue}
import java.util.concurrent.locks.LockSupport

/** java.lang.Thread: the main thread, names, ids, priorities, daemon status, states, interrupts,
 *  timed joins, start/join memory visibility, and the end of the program (non-daemon threads it
 *  waits for, daemon threads it does not).
 */
class Box(var value: Int)

object Main:
  def attempt(body: => Any): String =
    try String.valueOf(body)
    catch case e: Exception => e.getClass.getName + Option(e.getMessage).fold("")(": " + _)

  def kind(body: => Any): String =
    try String.valueOf(body)
    catch case e: Exception => e.getClass.getName

  def started(body: => Unit): Thread =
    val t = new Thread(() => body)
    t.start()
    t

  /** Waits (up to 10 s) until `t` is in `state`; returns the state it is in. */
  def awaitState(t: Thread, state: Thread.State): Thread.State =
    val deadline = System.nanoTime() + 10_000_000_000L
    while t.getState != state && System.nanoTime() < deadline do Thread.sleep(1)
    t.getState

  def main(args: Array[String]): Unit =
    val main = Thread.currentThread()
    println(s"main: ${main.getName} daemon=${main.isDaemon} priority=${main.getPriority} group=${main.getThreadGroup.getName} " +
      s"alive=${main.isAlive} ${main.getState} virtual=${main.isVirtual} same=${Thread.currentThread() eq main}")

    // Names: Thread-0, Thread-1, ... for threads without one.
    val a = new Thread(() => ())
    val b = new Thread(() => ())
    val named = new Thread(() => (), "named")
    println(s"names: ${a.getName} ${b.getName} ${named.getName}")
    named.setName("renamed")
    println(s"setName: ${named.getName}, null name: ${attempt(new Thread(() => (), null: String))}")

    // Ids: distinct, positive, as seen by the thread itself.
    var seenId = 0L
    val idThread = started { seenId = Thread.currentThread().threadId }
    idThread.join()
    val ids = Seq(main, a, b, named, idThread).map(_.threadId)
    println(s"ids: distinct=${ids.distinct.size == ids.size} positive=${ids.forall(_ > 0)} " +
      s"getId=${idThread.getId == idThread.threadId} seen=${seenId == idThread.threadId}")

    // Priorities: stored and checked, capped by the group's maximum.
    a.setPriority(Thread.MAX_PRIORITY)
    println(s"priorities: ${Thread.MIN_PRIORITY} ${Thread.NORM_PRIORITY} ${Thread.MAX_PRIORITY} new=${b.getPriority} set=${a.getPriority}")
    println(s"out of range: ${attempt(a.setPriority(11))} ${attempt(a.setPriority(0))}")
    val group = new ThreadGroup("capped")
    group.setMaxPriority(4)
    val inGroup = new Thread(group, () => (), "in-group")
    inGroup.setPriority(9)
    println(s"group: ${group.getName} parent=${group.getParent.getName} max=${group.getMaxPriority} " +
      s"thread=${inGroup.getThreadGroup.getName} priority=${inGroup.getPriority}")
    val built = Thread.ofPlatform().name("built").priority(3).unstarted(() => ())
    println(s"builder: ${built.getName} ${built.getPriority} ${kind(Thread.ofPlatform().priority(42))}")

    // Daemon status: inherited from the creating thread; fixed once started.
    val daemon = new Thread(() =>
      val child = new Thread(() => ())
      println(s"child of a daemon thread is a daemon: ${child.isDaemon}")
    )
    println(s"default daemon: ${daemon.isDaemon}")
    daemon.setDaemon(true)
    daemon.start()
    daemon.join()
    println(s"start twice: ${attempt(daemon.start())}")

    // run() runs the body in the calling thread.
    new Thread(() => println(s"run() runs in ${Thread.currentThread().getName}")).run()

    // Virtual threads: unnamed daemons by default.
    val virtual = Thread.ofVirtual().unstarted(() => ())
    println(s"virtual: name='${virtual.getName}' daemon=${virtual.isDaemon} ${attempt(virtual.setDaemon(false))}")

    // States.
    val gate = new CountDownLatch(1)
    val waiter = started(gate.await())
    println(s"NEW: ${new Thread(() => ()).getState}, latch await: ${awaitState(waiter, Thread.State.WAITING)}")
    val timedParker = started(LockSupport.parkNanos(60_000_000_000L))
    println(s"parkNanos: ${awaitState(timedParker, Thread.State.TIMED_WAITING)}")
    LockSupport.unpark(timedParker)
    timedParker.join()
    gate.countDown()
    waiter.join()
    println(s"after join: ${waiter.getState} alive=${waiter.isAlive}")

    // Timed joins of a thread that keeps running.
    val release = new CountDownLatch(1)
    val running = started(release.await())
    running.join(20)
    println(s"setDaemon of a running thread: ${attempt(running.setDaemon(true))}")
    println(s"join(20): alive=${running.isAlive} join(Duration)=${running.join(java.time.Duration.ofMillis(20))}")
    println(s"join of an unstarted thread: ${attempt(new Thread(() => ()).join(java.time.Duration.ofMillis(1)))}")
    release.countDown()
    running.join()
    println(s"negative sleep: ${kind(Thread.sleep(-1))}, negative join: ${attempt(running.join(-1))}")

    // Interrupts: the flag is sticky until something throws InterruptedException or reads it with
    // Thread.interrupted, so these do not depend on when the interrupt arrives.
    def interrupted(what: String)(body: => Unit): Unit =
      var result = ""
      val t = new Thread(() =>
        try
          body
          result = s"returned, flag=${Thread.currentThread().isInterrupted}"
        catch case e: InterruptedException => result = s"InterruptedException, flag=${Thread.currentThread().isInterrupted}"
      )
      t.start()
      t.interrupt()
      t.join()
      println(s"interrupt $what: $result")
    interrupted("sleep")(Thread.sleep(60000))
    interrupted("wait") { val lock = new Object; lock.synchronized(lock.wait()) }
    interrupted("timed wait") { val lock = new Object; lock.synchronized(lock.wait(60000)) }
    interrupted("join") {
      val forever = new Thread(() => new CountDownLatch(1).await())
      forever.setDaemon(true)
      forever.start()
      forever.join()
    }
    interrupted("park") { while !Thread.currentThread().isInterrupted do LockSupport.park() }
    interrupted("queue take")(new LinkedBlockingQueue[Int]().take())
    interrupted("latch await")(new CountDownLatch(1).await())
    interrupted("lockInterruptibly") {
      val lock = new java.util.concurrent.locks.ReentrantLock
      val holder = new CountDownLatch(1)
      started { lock.lock(); holder.countDown() }
      holder.await()
      lock.lockInterruptibly()
    }
    val self = Thread.currentThread()
    self.interrupt()
    println(s"self: ${self.isInterrupted} ${self.isInterrupted} ${Thread.interrupted()} ${Thread.interrupted()} ${self.isInterrupted}")
    Thread.`yield`()
    Thread.onSpinWait()

    // Memory visibility: Thread.start and join order plain fields.
    val box = new Box(1)
    val boxes = new Array[Box](4)
    val workers = (0 until 4).map(i => started { boxes(i) = new Box(box.value + i) })
    workers.foreach(_.join())
    println(s"published through start and join: ${boxes.map(_.value).mkString(",")}")
    val polled = new Box(0)
    val writer = started { polled.value = 99 }
    while writer.isAlive do Thread.`yield`()
    println(s"published through isAlive: ${polled.value}")

    // The end: main returns first; a non-daemon thread then starts another, and the program
    // waits for both. A sleeping daemon thread does not hold it up.
    val sleeper = new Thread(() =>
      Thread.sleep(60000)
      println("the daemon thread woke up")
    )
    sleeper.setDaemon(true)
    sleeper.start()
    val mainReturned = new CountDownLatch(1)
    started {
      mainReturned.await()
      println("first thread, after main returned")
      started {
        Thread.sleep(20)
        println("second thread, started by the first")
      }
    }
    println("main returns")
    mainReturned.countDown()
