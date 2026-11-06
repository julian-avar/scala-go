import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong, AtomicReference}

import scala.collection.mutable

/** Constructed by several threads at once: must be constructed exactly once. */
object Slow:
  val construction: Int = Main.constructions.incrementAndGet()
  Thread.sleep(20)
  val value = 42

class Lazy:
  lazy val x: Int =
    Main.lazyEvaluations.incrementAndGet()
    Thread.sleep(20)
    7

class BlockingQueue:
  private val items = mutable.Queue.empty[Int]
  def put(x: Int): Unit = synchronized {
    items.enqueue(x)
    notifyAll()
  }
  def take(): Int = synchronized {
    while items.isEmpty do wait()
    items.dequeue()
  }

class Flag:
  @volatile var stop = false

class Account(private var balance: Int):
  /** `return` from inside `synchronized` must release the monitor. */
  def withdraw(amount: Int): Boolean = synchronized {
    if amount > balance then return false
    balance -= amount
    true
  }
  def current: Int = synchronized(balance)

object Main:
  val constructions = new AtomicInteger
  val lazyEvaluations = new AtomicInteger

  def runAll(n: Int)(body: Int => Unit): Unit =
    val threads = (1 to n).map(i => new Thread(() => body(i)))
    threads.foreach(_.start())
    threads.foreach(_.join())

  def main(args: Array[String]): Unit =
    println(s"current thread: ${Thread.currentThread().getName}")

    val lock = new Object
    var count = 0
    runAll(8)(_ => for _ <- 1 to 1000 do lock.synchronized { count += 1 })
    println(s"synchronized count: $count")

    val atomicInt = new AtomicInteger
    val atomicLong = new AtomicLong
    runAll(8) { _ =>
      for _ <- 1 to 1000 do
        atomicInt.incrementAndGet()
        atomicLong.addAndGet(2)
    }
    println(s"atomics: ${atomicInt.get} $atomicLong")

    val first = new AtomicReference[String]
    runAll(8)(i => first.compareAndSet(null, "set"))
    val once = new AtomicBoolean
    val winners = new AtomicInteger
    runAll(8)(_ => if once.compareAndSet(false, true) then winners.incrementAndGet())
    println(s"compareAndSet: ${first.get} winners=${winners.get} ${once.get}")

    runAll(8)(_ => assert(Slow.value == 42))
    println(s"Slow constructed ${constructions.get} time(s)")

    val l = new Lazy
    runAll(8)(_ => assert(l.x == 7))
    println(s"lazy val evaluated ${lazyEvaluations.get} time(s)")

    val queue = new BlockingQueue
    var sum = 0
    val consumer = new Thread(() => for _ <- 1 to 100 do sum += queue.take())
    consumer.start()
    for i <- 1 to 100 do queue.put(i)
    consumer.join()
    println(s"wait/notify sum: $sum")

    val account = new Account(100)
    runAll(10)(_ => account.withdraw(30))
    println(s"balance: ${account.current}")

    val flag = new Flag
    var spins = 0L
    val spinner = new Thread(() => while !flag.stop do spins += 1)
    spinner.start()
    Thread.sleep(10)
    flag.stop = true
    spinner.join()
    println(s"volatile flag stopped the spinner: ${!spinner.isAlive}")

    val sleeper = new Thread(() =>
      try
        Thread.sleep(10000)
        println("not interrupted")
      catch case _: InterruptedException => println(s"interrupted, status cleared: ${!Thread.currentThread().isInterrupted}")
    )
    sleeper.start()
    sleeper.interrupt()
    sleeper.join()

    Thread.currentThread().interrupt()
    println(s"interrupted(): ${Thread.interrupted()} ${Thread.interrupted()}")

    val named = new Thread(() => println(s"in ${Thread.currentThread().getName}"), "worker")
    named.start()
    named.join()

    def fail(): Unit = throw new RuntimeException("boom")
    val failing = new Thread(() => fail(), "failing")
    failing.setUncaughtExceptionHandler((t, e) => println(s"uncaught in ${t.getName}: ${e.getMessage}"))
    failing.start()
    failing.join()

    val notOwner =
      try
        lock.notify()
        "no exception"
      catch case e: IllegalMonitorStateException => "IllegalMonitorStateException"
    println(s"notify without the monitor: $notOwner")

    // The program ends when this non-daemon thread does.
    val late = new Thread(() =>
      Thread.sleep(30)
      println("non-daemon thread finished after main")
    )
    late.start()
    println("main done")
