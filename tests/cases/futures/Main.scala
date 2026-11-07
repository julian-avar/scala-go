import java.util.concurrent.{CountDownLatch, Executors, TimeoutException}
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.*
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}

/** scala.concurrent on the global ExecutionContext (a ForkJoinPool) and on executors. */
object Main:
  def await[T](f: Future[T]): T = Await.result(f, 30.seconds)

  def show[T](f: Future[T]): String = Try(await(f)) match
    case Success(v) => s"Success($v)"
    case Failure(e) => s"Failure(${e.getClass.getSimpleName}: ${e.getMessage})"

  def main(args: Array[String]): Unit =
    import ExecutionContext.Implicits.global

    // Basics: map, flatMap, for comprehensions, sequence, traverse.
    println(await(Future(21).map(_ * 2)))
    println(await(for a <- Future(1); b <- Future(2); c <- Future(a + b) yield a + b + c))
    println(await(Future.sequence((1 to 10).toList.map(i => Future(i * i)))))
    println(await(Future.traverse(Vector("a", "bb", "ccc"))(s => Future(s.length))))
    println(await(Future.foldLeft((1 to 100).map(i => Future(i)))(0)(_ + _)))
    println(await(Future.reduceLeft((1 to 5).map(i => Future(i)))(_ * _)))

    // Failures: recover, recoverWith, fallbackTo, transform, failed projections.
    val boom = Future[Int](throw new IllegalStateException("boom"))
    println(show(boom))
    println(await(boom.recover { case e: IllegalStateException => -1 }))
    println(await(boom.recoverWith { case _ => Future(-2) }))
    println(await(boom.fallbackTo(Future(-3))))
    println(await(boom.failed.map(_.getMessage)))
    println(await(Future(5).transform(v => v + 1, e => e)))
    println(await(boom.transform {
      case Success(v) => Success(s"ok $v")
      case Failure(e) => Success(s"recovered ${e.getMessage}")
    }))
    println(show(Future(1).filter(_ > 1)))
    println(show(Future(1).collect { case 2 => "two" }))
    println(show(Future(10).zip(Future("x"))))
    println(show(Future(10).zipWith(Future(5))(_ - _)))
    println(show(Future(1).flatMap(_ => boom)))
    println(show(Future.failed[Int](new ArithmeticException("div"))))
    println(show(Future.successful("done")))
    println(show(Future.fromTry(Try("tried"))))

    // Promises.
    val p = Promise[String]()
    val f = p.future.map(_.toUpperCase)
    Future { Thread.sleep(20); p.success("promised") }
    println(await(f))
    println(p.trySuccess("again"))
    println(p.isCompleted)
    val failing = Promise[Int]()
    failing.failure(new RuntimeException("broken promise"))
    println(show(failing.future))

    // firstCompletedOf, find, onComplete callbacks.
    val slow = Future { Thread.sleep(2000); "slow" }
    val fast = Future { Thread.sleep(10); "fast" }
    println(await(Future.firstCompletedOf(List(slow, fast))))
    println(await(Future.find((1 to 20).map(i => Future(i)))(_ % 7 == 0)))
    val latch = new CountDownLatch(3)
    val callbacks = new AtomicInteger
    for i <- 1 to 3 do Future(i).onComplete { r =>
      callbacks.addAndGet(r.get)
      latch.countDown()
    }
    latch.await()
    println(s"callbacks: ${callbacks.get}")
    val done = Promise[Unit]()
    Future(7).foreach(v => done.success(println(s"foreach: $v")))
    await(done.future)

    // Await with a timeout.
    val never = Promise[Int]().future
    try Await.result(never, 50.millis)
    catch case e: TimeoutException => println(s"timeout: ${e.getClass.getName}")
    println(Try(Await.ready(never, 10.millis)).isFailure)
    println(never.value)
    println(Future.successful(3).value)

    // blocking {}: more blocked futures than processors must not deadlock the pool.
    val n = Runtime.getRuntime.availableProcessors * 2 + 4
    val gate = new CountDownLatch(1)
    val blocked = (1 to n).map(i => Future(blocking { gate.await(); i }))
    val opener = Future { Thread.sleep(50); gate.countDown() }
    println(s"blocking: ${await(Future.sequence(blocked)).sum} ${await(opener)}")

    // Many small futures: chained and in parallel.
    var chain = Future(0)
    for _ <- 1 to 10000 do chain = chain.map(_ + 1)
    println(s"chain: ${await(chain)}")
    val chained = (1 to 1000).foldLeft(Future(0))((acc, i) => acc.flatMap(x => Future(x + i)))
    println(s"flatMap chain: ${await(chained)}")
    println(s"parallel: ${await(Future.sequence((1 to 10000).map(i => Future(i.toLong)))).sum}")

    // A custom ExecutionContext on an executor.
    val pool = Executors.newFixedThreadPool(4)
    val ec = ExecutionContext.fromExecutorService(pool)
    val onPool = Future.traverse((1 to 20).toList)(i => Future(Thread.currentThread.getName.startsWith("pool-"))(using ec))(using implicitly, ec)
    println(s"custom ec: ${await(onPool).forall(identity)}")
    val reported = Promise[String]()
    val reporting = ExecutionContext.fromExecutor(pool, e => reported.success(e.getMessage))
    Future(1)(using reporting).onComplete(_ => throw new RuntimeException("reported failure"))(using reporting)
    println(await(reported.future))
    ec.shutdown()
    println(s"shutdown: ${pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)}")

    // ExecutionContext.parasitic runs callbacks on the completing thread.
    println(await(Future(1).map(_ + 1)(using ExecutionContext.parasitic)))
