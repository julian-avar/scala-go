import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, ExecutionException}
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.*
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

/** scala.concurrent beyond the `scala-futures` case: Promise completion methods, andThen, delegate,
  * never and unit, wrapped fatal exceptions, Channel, SyncVar, Deadline and
  * ExecutionContext.execute. */
object Main:
  given ExecutionContext = ExecutionContext.global

  def await[T](f: Future[T]): T = Await.result(f, 30.seconds)

  def show[T](f: Future[T]): String = Try(await(f)) match
    case Success(v) => s"Success($v)"
    case Failure(e) => s"Failure(${e.getClass.getName}: ${e.getMessage})"

  def main(args: Array[String]): Unit =
    promises()
    combinators()
    failures()
    primitives()
    contexts()

  def promises(): Unit =
    println("-- Promise")
    val p = Promise[Int]()
    println(s"${p.isCompleted} ${p.future.value}")
    p.completeWith(Future(41).map(_ + 1))
    println(await(p.future))
    println(s"${p.tryComplete(Success(0))} ${p.trySuccess(0)} ${p.tryFailure(RuntimeException())} ${p.future.value}")
    println(try { p.success(1); "completed twice" } catch case e: IllegalStateException => s"IllegalStateException: ${e.getMessage}")
    println(show(Promise.successful("s").future) + " " + show(Promise.failed[Int](ArithmeticException("f")).future))
    println(show(Promise.fromTry(Try("t")).future))
    val q = Promise[String]()
    q.complete(Failure(IllegalArgumentException("completed with failure")))
    println(show(q.future))
    // Many threads race to complete one promise: exactly one wins.
    val race = Promise[Int]()
    val wins = AtomicInteger()
    val latch = CountDownLatch(16)
    for i <- 1 to 16 do Future { if race.trySuccess(i) then wins.incrementAndGet(); latch.countDown() }
    latch.await()
    println(s"winners: ${wins.get} completed: ${race.isCompleted}")

  def combinators(): Unit =
    println("-- combinators")
    val log = ConcurrentLinkedQueue[String]()
    val f = Future(10)
      .andThen { case Success(v) => log.add(s"first $v") }
      .andThen { case r => log.add(s"third $r") }
    println(s"${await(f)} ${log.asScala.toList}")
    println(await(Future.delegate(Future(5).map(_ * 3))))
    println(show(Future.delegate(throw IllegalStateException("thrown by delegate"))))
    println(s"${await(Future.unit)} ${Future.unit.isCompleted} ${Future.never.isCompleted} ${Future.never.value}")
    println(Try(Await.result(Future.never, 20.millis)).failed.map(_.getClass.getName))
    println(await(Future.sequence(List(Future(1), Future(2)))(using implicitly, summon[ExecutionContext])))
    println(await(Future.sequence(Vector(Future("a"), Future("b"))).map(_.mkString)))
    println(await(Future.traverse(Set(1, 2, 3))(i => Future(i * i))).toList.sorted)
    println(await(Future(1).flatMap(x => Future(x + 1)).flatMap(x => Future.successful(x * 10))))
    println(await(Future(Future(7)).flatten))
    println(await(for x <- Future(2); y <- Future(3) if x < y yield x * y))
    println(show(for x <- Future(5); y <- Future(3) if x < y yield x * y))
    println(await(Future.firstCompletedOf(List(Future.never, Future.successful("only one completes")))))
    val zipped = Future(1).zip(Future("one")).zip(Future(1.0))
    println(await(zipped))
    println(await(Future.foldLeft(List(Future(1), Future(2), Future(3)))(List.empty[Int])((acc, x) => x :: acc)))

  def failures(): Unit =
    println("-- failures")
    // Fatal-to-the-thread exceptions are wrapped in an ExecutionException.
    println(show(Future(throw InterruptedException("interrupted"))))
    println(Try(await(Future(throw InterruptedException("cause"))))
      .failed.map(e => s"${e.getClass.getSimpleName} caused by ${e.getCause.getClass.getSimpleName}").get)
    println(show(Future(throw NotImplementedError("not yet"))))
    val e = Future.failed[Int](RuntimeException("original"))
    println(show(e.recoverWith { case ex => Future.failed(IllegalStateException(s"wrapped ${ex.getMessage}")) }))
    println(show(e.transformWith {
      case Success(v)  => Future.successful(v)
      case Failure(ex) => Future.successful(-ex.getMessage.length)
    }))
    println(show(e.zip(Future(1))) + " " + show(Future(1).zip(e)))
    println(show(Future.sequence(List(Future(1), e, Future(3)))))
    println(show(Future.find(List(e, Future(4)))(_ > 3)))
    println(show(Future.firstCompletedOf(List(e))))
    println(show(e.failed.map(_.getMessage)))
    println(show(Future(1).failed))
    println(Await.ready(e, 1.second).value.map(_.isFailure))

  def primitives(): Unit =
    println("-- Channel, SyncVar, Deadline")
    val ch = Channel[Int]()
    val producer = Future { for i <- 1 to 5 do ch.write(i * i) }
    println(List.fill(5)(ch.read).sum)
    await(producer)
    val sv = SyncVar[String]()
    Future { Thread.sleep(10); sv.put("put by another thread") }
    println(sv.take())
    println(s"${sv.isSet} ${sv.get(10)}")
    sv.put("again")
    println(s"${sv.isSet} ${sv.take(1000)}")
    val deadline = 10.seconds.fromNow
    println(s"${deadline.hasTimeLeft()} ${deadline.isOverdue()} ${(deadline - 20.seconds).isOverdue()}")
    println(s"${Deadline.now < deadline} ${deadline.timeLeft <= 10.seconds}")

  def contexts(): Unit =
    println("-- ExecutionContext")
    val done = CountDownLatch(3)
    val total = AtomicInteger()
    for i <- 1 to 3 do summon[ExecutionContext].execute(() => { total.addAndGet(i); done.countDown() })
    done.await()
    println(total.get)
    val reported = Promise[String]()
    val ec = ExecutionContext.fromExecutor(null, t => reported.trySuccess(t.getMessage))
    ec.execute(() => throw RuntimeException("reported to the handler"))
    println(await(reported.future))
    println(await(Future(Thread.currentThread.getName.nonEmpty)(using ExecutionContext.parasitic)))
    val order = ConcurrentLinkedQueue[Int]()
    val parasitic = Future.successful(1).map { x => order.add(x); x }(using ExecutionContext.parasitic)
    order.add(2)
    println(s"${order.asScala.toList} ${await(parasitic)}")
    val blockingSum = Future.traverse((1 to 8).toList)(i => Future(blocking { Thread.sleep(5); i }))
    println(await(blockingSum).sum)
    println(Await.result(Future(Duration.Inf.toString), Duration.Inf))
