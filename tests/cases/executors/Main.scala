import java.util.concurrent.*
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}

import scala.jdk.CollectionConverters.*

/** java.util.concurrent executors: thread pools, futures, invokeAll/invokeAny, scheduling,
 *  shutdown, ForkJoinPool with RecursiveTask, and completion services. Timings are coarse and the
 *  output does not depend on them.
 */
object Main:
  def callable[T](body: => T): Callable[T] = () => body

  def fib(n: Int): Long = if n < 2 then n else fib(n - 1) + fib(n - 2)

  class Fib(n: Int) extends RecursiveTask[java.lang.Long]:
    protected def compute(): java.lang.Long =
      if n < 15 then fib(n)
      else
        val a = new Fib(n - 1)
        a.fork()
        val b = new Fib(n - 2)
        b.compute() + a.join()

  class SumAction(array: Array[Long], from: Int, to: Int, total: AtomicLong) extends RecursiveAction:
    protected def compute(): Unit =
      if to - from <= 1000 then
        var s = 0L
        for i <- from until to do s += array(i)
        total.addAndGet(s)
      else
        val mid = (from + to) / 2
        ForkJoinTask.invokeAll(new SumAction(array, from, mid, total), new SumAction(array, mid, to, total))

  def main(args: Array[String]): Unit =
    // Fixed pool: submit, get, invokeAll, invokeAny.
    val fixed = Executors.newFixedThreadPool(4)
    val futures = (1 to 10).map(i => fixed.submit(callable(i * i)))
    println(s"fixed: ${futures.map(_.get).sum}")
    val all = fixed.invokeAll((1 to 5).map(i => callable(s"task$i")).asJava)
    println(s"invokeAll: ${all.asScala.map(_.get).mkString(",")} done=${all.asScala.forall(_.isDone)}")
    println(s"invokeAny: ${fixed.invokeAny(List(callable("only")).asJava)}")
    val failed = fixed.submit(callable[Int](throw new IllegalArgumentException("bad")))
    try failed.get()
    catch case e: ExecutionException => println(s"ExecutionException: ${e.getCause}")
    println(s"state: ${failed.state()} ${futures.head.state()} ${futures.head.resultNow()}")
    val slow = fixed.submit(callable { Thread.sleep(5000); 1 })
    try slow.get(20, TimeUnit.MILLISECONDS)
    catch case e: TimeoutException => println("get timed out")
    println(s"cancel: ${slow.cancel(true)} ${slow.isCancelled} ${slow.isDone}")
    try slow.get()
    catch case e: CancellationException => println("CancellationException")
    val ran = new AtomicInteger
    val r = fixed.submit((() => ran.incrementAndGet(): Unit): Runnable)
    println(s"runnable result: ${r.get} ran=${ran.get}")

    // Shutdown: queued tasks still run, new ones are rejected.
    val counter = new AtomicInteger
    for _ <- 1 to 100 do fixed.execute(() => counter.incrementAndGet())
    fixed.shutdown()
    println(s"isShutdown: ${fixed.isShutdown}")
    try fixed.execute(() => ())
    catch case e: RejectedExecutionException => println("rejected after shutdown")
    println(s"terminated: ${fixed.awaitTermination(10, TimeUnit.SECONDS)} ${fixed.isTerminated} count=${counter.get}")

    // shutdownNow interrupts running tasks and returns the queued ones.
    val single = Executors.newSingleThreadExecutor()
    val started = new CountDownLatch(1)
    val interrupted = new CountDownLatch(1)
    single.execute { () =>
      started.countDown()
      try Thread.sleep(10000)
      catch case _: InterruptedException => interrupted.countDown()
    }
    for _ <- 1 to 3 do single.execute(() => ())
    started.await()
    val pending = single.shutdownNow()
    println(s"shutdownNow: pending=${pending.size} interrupted=${interrupted.await(10, TimeUnit.SECONDS)}")
    println(s"single terminated: ${single.awaitTermination(10, TimeUnit.SECONDS)}")

    // A single-thread executor runs tasks in order.
    val ordered = Executors.newSingleThreadExecutor()
    val order = new java.util.concurrent.ConcurrentLinkedQueue[Integer]()
    (1 to 20).foreach(i => ordered.execute(() => order.add(i)))
    ordered.shutdown()
    ordered.awaitTermination(10, TimeUnit.SECONDS)
    println(s"ordered: ${order.asScala.mkString(",")}")

    // Cached pool and a custom thread factory.
    val names = new ConcurrentHashMap[String, java.lang.Boolean]()
    val cached = Executors.newCachedThreadPool { (r: Runnable) =>
      val t = new Thread(r, "worker")
      t.setDaemon(true)
      t
    }
    val tasks = (1 to 8).map(_ => cached.submit(callable(names.put(Thread.currentThread.getName, true))))
    tasks.foreach(_.get)
    println(s"cached thread names: ${names.keySet.asScala.toList.sorted}")
    cached.shutdown()

    // Virtual threads.
    val virtual = Executors.newVirtualThreadPerTaskExecutor()
    val vt = (1 to 100).map(i => virtual.submit(callable(Thread.currentThread.isVirtual)))
    println(s"virtual: ${vt.forall(_.get)}")
    virtual.close()
    println(s"virtual closed: ${virtual.isTerminated}")

    // Scheduled executor: delays, fixed rate, fixed delay, cancellation.
    val scheduler = Executors.newScheduledThreadPool(2)
    val delayed = scheduler.schedule(callable("delayed"), 30, TimeUnit.MILLISECONDS)
    println(s"schedule: ${delayed.get}")
    val ticks = new CountDownLatch(5)
    val rate = scheduler.scheduleAtFixedRate(() => ticks.countDown(), 0, 10, TimeUnit.MILLISECONDS)
    println(s"fixed rate ticked: ${ticks.await(10, TimeUnit.SECONDS)}")
    rate.cancel(false)
    println(s"fixed rate cancelled: ${rate.isCancelled}")
    val delays = new CountDownLatch(3)
    val withDelay = scheduler.scheduleWithFixedDelay(() => delays.countDown(), 5, 5, TimeUnit.MILLISECONDS)
    println(s"fixed delay ticked: ${delays.await(10, TimeUnit.SECONDS)}")
    withDelay.cancel(true)
    val never = scheduler.schedule(callable("never"), 1, TimeUnit.HOURS)
    println(s"cancel scheduled: ${never.cancel(false)} ${never.getDelay(TimeUnit.MINUTES) > 50}")
    scheduler.shutdown()
    println(s"scheduler terminated: ${scheduler.awaitTermination(10, TimeUnit.SECONDS)}")

    // ExecutorCompletionService returns results as they complete.
    val pool = Executors.newFixedThreadPool(3)
    val ecs = new ExecutorCompletionService[Integer](pool)
    for i <- 1 to 6 do ecs.submit(callable(Integer.valueOf(i)))
    println(s"completion service: ${(1 to 6).map(_ => ecs.take().get.intValue).sum}")
    pool.shutdown()

    // ForkJoinPool: RecursiveTask, RecursiveAction, the common pool.
    val fj = new ForkJoinPool(4)
    println(s"fib(27) = ${fj.invoke(new Fib(27))}")
    val numbers = Array.tabulate(100000)(_.toLong)
    val total = new AtomicLong
    fj.invoke(new SumAction(numbers, 0, numbers.length, total))
    println(s"sum = ${total.get}")
    println(s"submit: ${fj.submit(callable("fj")).get}")
    fj.shutdown()
    println(s"fj terminated: ${fj.awaitTermination(10, TimeUnit.SECONDS)}")
    println(s"common: ${ForkJoinPool.commonPool().invoke(new Fib(20))} ${ForkJoinPool.commonPool().getParallelism >= 1}")
    println(s"adapt: ${ForkJoinTask.adapt(callable("adapted")).invoke()}")

    // Executors.callable and FutureTask.
    val ft = new FutureTask[String](callable("future task"))
    new Thread(ft).start()
    println(ft.get)
    println(Executors.callable(() => (), "result").call())
