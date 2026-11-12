import java.util.concurrent.*
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import java.util.concurrent.locks.*

/** java.util.concurrent.locks and the synchronizers built on them (AbstractQueuedSynchronizer):
 *  ReentrantLock and conditions, read-write and stamped locks, semaphores, latches, barriers,
 *  phasers, exchangers, and LockSupport. Results are printed after joining.
 */
object Main:
  def thread(body: => Unit): Thread = new Thread(() => body)

  def runAll(n: Int)(body: Int => Unit): Unit =
    val threads = (0 until n).map(i => new Thread(() => body(i)))
    threads.foreach(_.start())
    threads.foreach(_.join())

  /** A bounded buffer with two conditions, as in the Condition javadoc. */
  class BoundedBuffer(capacity: Int):
    private val lock = new ReentrantLock()
    private val notFull = lock.newCondition()
    private val notEmpty = lock.newCondition()
    private val items = new Array[Int](capacity)
    private var putIndex, takeIndex, count = 0
    def put(x: Int): Unit =
      lock.lock()
      try
        while count == capacity do notFull.await()
        items(putIndex) = x
        putIndex = (putIndex + 1) % capacity
        count += 1
        notEmpty.signal()
      finally lock.unlock()
    def take(): Int =
      lock.lock()
      try
        while count == 0 do notEmpty.await()
        val x = items(takeIndex)
        takeIndex = (takeIndex + 1) % capacity
        count -= 1
        notFull.signal()
        x
      finally lock.unlock()

  def main(args: Array[String]): Unit =
    // ReentrantLock: mutual exclusion, reentrancy, tryLock, fairness, introspection.
    val lock = new ReentrantLock()
    var shared = 0
    runAll(8)(_ => for _ <- 1 to 5000 do
      lock.lock()
      try shared += 1
      finally lock.unlock()
    )
    println(s"ReentrantLock: $shared")
    lock.lock(); lock.lock()
    println(s"hold count: ${lock.getHoldCount} ${lock.isHeldByCurrentThread} ${lock.isLocked}")
    val other = new AtomicInteger(-1)
    val t = new Thread(() => other.set(if lock.tryLock() then 1 else 0))
    t.start(); t.join()
    println(s"tryLock from another thread: ${other.get}")
    lock.unlock(); lock.unlock()
    println(s"unlocked: ${lock.isLocked} ${lock.tryLock(10, TimeUnit.MILLISECONDS)}")
    lock.unlock()
    try lock.unlock()
    catch case e: IllegalMonitorStateException => println("IllegalMonitorStateException")
    val fair = new ReentrantLock(true)
    println(s"fair: ${fair.isFair} ${fair.toString.endsWith("[Unlocked]")}")

    // Conditions: a bounded buffer with producers and consumers.
    val buffer = new BoundedBuffer(5)
    val sum = new AtomicLong
    val consumers = (1 to 3).map(_ => new Thread(() => for _ <- 1 to 1000 do sum.addAndGet(buffer.take())))
    consumers.foreach(_.start())
    runAll(3)(_ => for i <- 1 to 1000 do buffer.put(i))
    consumers.foreach(_.join())
    println(s"bounded buffer: ${sum.get}")
    val cond = lock.newCondition()
    lock.lock()
    try println(s"awaitNanos timeout: ${cond.awaitNanos(TimeUnit.MILLISECONDS.toNanos(10)) <= 0} ${cond.await(1, TimeUnit.MILLISECONDS)}")
    finally lock.unlock()

    // Interrupting a thread blocked on a lock and on a condition.
    lock.lock()
    val interruptedLock = new AtomicInteger
    val blocked = thread {
      try lock.lockInterruptibly()
      catch case _: InterruptedException => interruptedLock.incrementAndGet()
    }
    blocked.start()
    while !lock.hasQueuedThread(blocked) do Thread.sleep(1)
    println(s"queued: ${lock.getQueueLength} ${lock.hasQueuedThreads}")
    blocked.interrupt()
    blocked.join()
    lock.unlock()
    val awaiting = thread {
      lock.lock()
      try cond.await()
      catch case _: InterruptedException => interruptedLock.incrementAndGet()
      finally lock.unlock()
    }
    awaiting.start()
    while { lock.lock(); try !lock.hasWaiters(cond) finally lock.unlock() } do Thread.sleep(1)
    awaiting.interrupt()
    awaiting.join()
    println(s"interrupted: ${interruptedLock.get}")

    // ReentrantReadWriteLock: concurrent readers, exclusive writers, downgrading.
    val rw = new ReentrantReadWriteLock()
    var value = 0
    val maxReaders = new AtomicInteger
    val readers = new AtomicInteger
    runAll(8) { i =>
      for _ <- 1 to 500 do
        if i % 4 == 0 then
          rw.writeLock().lock()
          try value += 1
          finally rw.writeLock().unlock()
        else
          rw.readLock().lock()
          try
            val n = readers.incrementAndGet()
            maxReaders.accumulateAndGet(n, math.max)
            readers.decrementAndGet()
          finally rw.readLock().unlock()
    }
    println(s"ReentrantReadWriteLock: $value ${maxReaders.get >= 1}")
    rw.writeLock().lock()
    rw.readLock().lock()
    rw.writeLock().unlock()
    println(s"downgraded: ${rw.getReadLockCount} ${rw.isWriteLocked} ${rw.getReadHoldCount}")
    rw.readLock().unlock()

    // StampedLock: optimistic reads, read and write stamps, conversion.
    val sl = new StampedLock()
    // Optimistic reads race with the writer by design: volatile, so that Go's race detector agrees.
    class Point:
      @volatile var x = 0.0
      @volatile var y = 0.0
    val p = new Point
    runAll(4) { i =>
      for _ <- 1 to 1000 do
        if i == 0 then
          val s = sl.writeLock()
          try { p.x += 1; p.y += 1 }
          finally sl.unlockWrite(s)
        else
          var stamp = sl.tryOptimisticRead()
          var cx = p.x
          var cy = p.y
          if !sl.validate(stamp) then
            stamp = sl.readLock()
            try { cx = p.x; cy = p.y }
            finally sl.unlockRead(stamp)
    }
    println(s"StampedLock: ${p.x} ${p.y}")
    val rs = sl.readLock()
    val ws = sl.tryConvertToWriteLock(rs)
    println(s"converted: ${ws != 0} ${sl.isWriteLocked}")
    sl.unlockWrite(ws)
    val viewLock = sl.asWriteLock()
    viewLock.lock(); viewLock.unlock()
    println(s"views: ${sl.isWriteLocked} ${sl.isReadLocked}")

    // Semaphore limits concurrency.
    val sem = new Semaphore(3)
    val inside = new AtomicInteger
    val maxInside = new AtomicInteger
    runAll(10)(_ => for _ <- 1 to 50 do
      sem.acquire()
      try
        maxInside.accumulateAndGet(inside.incrementAndGet(), math.max)
        Thread.`yield`()
        inside.decrementAndGet()
      finally sem.release()
    )
    println(s"Semaphore: max inside ${maxInside.get <= 3} permits=${sem.availablePermits} ${sem.tryAcquire(4)} ${sem.tryAcquire(3, 10, TimeUnit.MILLISECONDS)} ${sem.drainPermits()}")

    // CountDownLatch.
    val ready = new CountDownLatch(5)
    val start = new CountDownLatch(1)
    val ran = new AtomicInteger
    val workers = (1 to 5).map(_ => thread { ready.countDown(); start.await(); ran.incrementAndGet() })
    workers.foreach(_.start())
    ready.await()
    println(s"CountDownLatch: ready ${ready.getCount} ran ${ran.get}")
    start.countDown()
    workers.foreach(_.join())
    println(s"released: ${ran.get} ${new CountDownLatch(1).await(10, TimeUnit.MILLISECONDS)}")

    // CyclicBarrier with a barrier action, over several generations.
    val trips = new AtomicInteger
    val barrier = new CyclicBarrier(4, () => trips.incrementAndGet())
    runAll(4)(_ => for _ <- 1 to 10 do barrier.await())
    println(s"CyclicBarrier: ${trips.get} broken=${barrier.isBroken}")
    val timedOut = new CyclicBarrier(2)
    try timedOut.await(10, TimeUnit.MILLISECONDS)
    catch case _: TimeoutException => println(s"barrier timeout, broken=${timedOut.isBroken}")
    try timedOut.await()
    catch case _: BrokenBarrierException => println("BrokenBarrierException")

    // Phaser: dynamic parties over phases.
    val phaser = new Phaser(1)
    val phases = new ConcurrentLinkedQueue[Integer]()
    val parties = (1 to 3).map { _ =>
      phaser.register()
      thread {
        for _ <- 1 to 3 do phases.add(phaser.arriveAndAwaitAdvance())
        phaser.arriveAndDeregister()
      }
    }
    parties.foreach(_.start())
    for _ <- 1 to 3 do phaser.arriveAndAwaitAdvance()
    parties.foreach(_.join())
    println(s"Phaser: phase=${phaser.getPhase} parties=${phaser.getRegisteredParties} arrivals=${phases.size}")

    // Exchanger.
    val exchanger = new Exchanger[String]()
    val got = new Array[String](1)
    val partner = new Thread(() => got(0) = exchanger.exchange("from thread"))
    partner.start()
    val mine = exchanger.exchange("from main")
    partner.join()
    println(s"Exchanger: $mine / ${got(0)}")

    // LockSupport: a permit given before park makes park return at once.
    LockSupport.unpark(Thread.currentThread())
    LockSupport.park()
    val before = System.nanoTime()
    LockSupport.parkNanos(5000000L)
    println(s"LockSupport: ${System.nanoTime() - before >= 0}")
    val parker = new Thread(() =>
      LockSupport.park("blocker")
      println(s"unparked, interrupted=${Thread.currentThread.isInterrupted}")
    )
    parker.start()
    while LockSupport.getBlocker(parker) == null do Thread.sleep(1)
    println(s"blocker: ${LockSupport.getBlocker(parker)}")
    parker.interrupt()
    parker.join()
