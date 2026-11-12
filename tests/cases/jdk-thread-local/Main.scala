import java.util.concurrent.{ConcurrentHashMap, CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

import scala.util.DynamicVariable

/** ThreadLocal, InheritableThreadLocal, scala.util.DynamicVariable, thread builders, virtual
 *  threads, thread states, and Runtime (with a shutdown hook).
 */
object Main:
  val counter = ThreadLocal.withInitial[Integer](() => 0)
  val name = new ThreadLocal[String]
  val inherited = new InheritableThreadLocal[String]:
    override def childValue(parent: String): String = if parent == null then null else parent + "+child"

  def main(args: Array[String]): Unit =
    println(s"initial: ${counter.get} ${name.get}")
    counter.set(41)
    counter.set(counter.get + 1)
    name.set("main")
    println(s"main: ${counter.get} ${name.get}")

    // Each thread has its own values.
    val results = new ConcurrentHashMap[Integer, String]()
    val threads = (1 to 8).map { i =>
      new Thread(() =>
        for _ <- 1 to i do counter.set(counter.get + 1)
        name.set(s"t$i")
        Thread.`yield`()
        results.put(i, s"${name.get}:${counter.get}"): Unit
      )
    }
    threads.foreach(_.start())
    threads.foreach(_.join())
    println((1 to 8).map(results.get(_)).mkString(" "))
    println(s"main unchanged: ${counter.get} ${name.get}")

    // remove() goes back to the initial value.
    counter.remove()
    name.remove()
    println(s"after remove: ${counter.get} ${name.get}")

    // Many thread locals in one thread (the table grows), removed in another order.
    val many = (1 to 100).map(i => ThreadLocal.withInitial[Integer](() => i))
    many.foreach(t => t.set(t.get * 2))
    many.filter(_.get % 3 == 0).foreach(_.remove())
    println(s"many: ${many.map(_.get.intValue).sum}")

    // InheritableThreadLocal: children start with childValue of the parent's value.
    inherited.set("parent")
    val seen = new Array[String](2)
    val child = new Thread(() =>
      seen(0) = inherited.get
      val grandchild = new Thread(() => seen(1) = inherited.get)
      grandchild.start()
      grandchild.join()
    )
    child.start()
    child.join()
    println(s"inherited: ${seen.mkString(", ")} parent still ${inherited.get}")
    val noInherit = Thread.ofPlatform().inheritInheritableThreadLocals(false).unstarted(() => seen(0) = inherited.get)
    noInherit.start()
    noInherit.join()
    println(s"not inherited: ${seen(0)}")

    // DynamicVariable (an InheritableThreadLocal) and Console.withOut.
    val dyn = new DynamicVariable[Int](1)
    dyn.withValue(2) {
      val t = new Thread(() => println(s"dynamic in child: ${dyn.value}"))
      t.start()
      t.join()
      println(s"dynamic: ${dyn.value}")
    }
    println(s"dynamic after: ${dyn.value}")
    val out = new java.io.ByteArrayOutputStream
    Console.withOut(out)(println("captured"))
    println(s"withOut: ${out.toString.trim}")

    // Thread pools reuse threads, and their thread locals.
    val pool = Executors.newFixedThreadPool(2)
    val perThread = ThreadLocal.withInitial[AtomicInteger](() => new AtomicInteger)
    val done = new CountDownLatch(20)
    val total = new AtomicInteger
    for _ <- 1 to 20 do pool.execute { () =>
      total.incrementAndGet()
      perThread.get.incrementAndGet()
      done.countDown()
    }
    done.await()
    pool.shutdown()
    pool.awaitTermination(10, TimeUnit.SECONDS)
    println(s"pool: ${total.get}")

    // Thread builders, virtual threads, states.
    val factory = Thread.ofPlatform().name("worker-", 1).daemon(true).factory()
    val w1 = factory.newThread(() => ())
    val w2 = factory.newThread(() => ())
    println(s"factory names: ${w1.getName} ${w2.getName} daemon=${w1.isDaemon}")
    val vt = Thread.ofVirtual().name("virtual").start(() => ())
    vt.join()
    println(s"virtual: ${vt.getName} ${vt.isVirtual} ${vt.isDaemon} ${vt.getState}")
    val started = Thread.startVirtualThread(() => name.set("in virtual"))
    started.join()
    println(s"startVirtualThread: ${started.isVirtual} ${Thread.currentThread.isVirtual}")
    val gate = new Object
    val waiting = new Thread(() => gate.synchronized(gate.wait()))
    println(s"state: ${waiting.getState}")
    waiting.start()
    val parked = new Thread(() => java.util.concurrent.locks.LockSupport.park())
    parked.start()
    while parked.getState != Thread.State.WAITING do Thread.sleep(1)
    println(s"parked: ${parked.getState}")
    java.util.concurrent.locks.LockSupport.unpark(parked)
    parked.join()
    gate.synchronized(gate.notifyAll())
    waiting.join()
    println(s"terminated: ${waiting.getState} ${parked.getState} ${waiting.isAlive}")
    println(s"holdsLock: ${Thread.holdsLock(gate)} ${gate.synchronized(Thread.holdsLock(gate))}")
    val sleeper = new Thread(() =>
      try Thread.sleep(java.time.Duration.ofSeconds(10))
      catch case _: InterruptedException => println("sleep interrupted")
    )
    sleeper.start()
    sleeper.interrupt()
    println(s"join(Duration): ${sleeper.join(java.time.Duration.ofSeconds(10))} ${sleeper.isAlive}")

    // Runtime.
    val rt = Runtime.getRuntime
    println(s"processors: ${rt.availableProcessors > 0}")
    println(s"memory: ${rt.totalMemory > 0} ${rt.freeMemory >= 0} ${rt.maxMemory >= rt.totalMemory}")
    rt.gc()
    rt.addShutdownHook(new Thread(() => println("shutdown hook ran")))
