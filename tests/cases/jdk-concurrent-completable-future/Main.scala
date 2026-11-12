import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

import scala.jdk.CollectionConverters.*
import scala.jdk.FutureConverters.*

/** java.util.concurrent.CompletableFuture: async stages on the common pool and on executors,
 *  composition, exceptions, timeouts, and conversions to and from Scala futures.
 */
object Main:
  def supply[T](body: => T): Supplier[T] = () => body

  def describe(f: CompletableFuture[?]): String =
    try s"value ${f.join()}"
    catch
      case e: CompletionException  => s"CompletionException(${e.getCause})"
      case e: CancellationException => "cancelled"

  def main(args: Array[String]): Unit =
    val f = CompletableFuture.supplyAsync(supply(20)).thenApply(_ + 1).thenApply(_ * 2)
    println(f.get())
    println(CompletableFuture.supplyAsync(supply("a")).thenCompose(s => CompletableFuture.supplyAsync(supply(s + "b"))).join())
    println(CompletableFuture.supplyAsync[Integer](supply(3)).thenCombine[Integer, Integer](CompletableFuture.supplyAsync[Integer](supply(4)), (a, b) => a * b).join())
    val accepted = new AtomicInteger
    CompletableFuture.supplyAsync(supply(5)).thenAccept(v => accepted.set(v)).join()
    println(s"thenAccept: ${accepted.get}")
    println(CompletableFuture.runAsync(() => accepted.incrementAndGet()).thenRun(() => accepted.incrementAndGet()).thenApply(_ => accepted.get).join())

    // allOf, anyOf.
    val parts = (1 to 10).map(i => CompletableFuture.supplyAsync(supply(i * i)))
    CompletableFuture.allOf(parts*).join()
    println(s"allOf: ${parts.map(_.join().intValue).sum}")
    val never = new CompletableFuture[String]()
    println(s"anyOf: ${CompletableFuture.anyOf(never, CompletableFuture.completedFuture("first")).join()}")

    // Exceptions.
    val failing = CompletableFuture.supplyAsync[Integer](supply(throw new IllegalStateException("boom")))
    println(describe(failing))
    println(failing.exceptionally(e => -1).join())
    println(failing.handle((v, e) => if e != null then s"handled ${e.getCause.getMessage}" else s"value $v").join())
    println(CompletableFuture.supplyAsync(supply(1)).handle((v, e) => s"handled $v $e").join())
    val seen = new AtomicInteger
    println(describe(failing.whenComplete((v, e) => if e != null then seen.incrementAndGet())))
    println(s"whenComplete saw failure: ${seen.get}")
    println(describe(CompletableFuture.supplyAsync(supply(1)).thenApply[Integer](_ => throw new ArithmeticException("in stage"))))
    try failing.get()
    catch case e: ExecutionException => println(s"get: ${e.getCause}")
    println(s"isCompletedExceptionally: ${failing.isCompletedExceptionally} ${failing.state()}")
    println(describe(CompletableFuture.failedFuture[String](new RuntimeException("failed future"))))
    println(failing.exceptionallyCompose(e => CompletableFuture.completedFuture(99)).join())

    // Manual completion, cancellation, obtrude.
    val manual = new CompletableFuture[String]()
    println(s"complete: ${manual.complete("one")} ${manual.complete("two")} ${manual.join()} ${manual.getNow("none")}")
    val cancelled = new CompletableFuture[String]()
    println(s"cancel: ${cancelled.cancel(true)} ${cancelled.isCancelled} ${describe(cancelled)}")
    val pending = new CompletableFuture[String]()
    println(s"getNow: ${pending.getNow("absent")} ${pending.isDone} dependents=${pending.thenApply(_.length).getNumberOfDependents}")
    pending.completeExceptionally(new RuntimeException("late"))
    println(describe(pending))

    // Timeouts.
    val slow = new CompletableFuture[String]()
    println(describe(slow.orTimeout(20, TimeUnit.MILLISECONDS)))
    println(new CompletableFuture[String]().completeOnTimeout("default", 20, TimeUnit.MILLISECONDS).join())
    try new CompletableFuture[String]().get(20, TimeUnit.MILLISECONDS)
    catch case e: TimeoutException => println("get timed out")
    val delayed = CompletableFuture.supplyAsync(supply("delayed"), CompletableFuture.delayedExecutor(20, TimeUnit.MILLISECONDS))
    println(delayed.join())

    // On an executor, and asynchronous variants.
    val pool = Executors.newFixedThreadPool(3)
    val onPool = CompletableFuture.supplyAsync(supply(Thread.currentThread.getName.startsWith("pool-")), pool)
    println(s"executor: ${onPool.join()}")
    val asyncChain = CompletableFuture.supplyAsync[Integer](supply(1), pool)
      .thenApplyAsync[Integer](x => x + 1, pool)
      .thenComposeAsync[Integer](x => CompletableFuture.supplyAsync[Integer](supply(x * 10), pool), pool)
      .thenCombineAsync[Integer, Integer](CompletableFuture.completedFuture[Integer](5), (a, b) => a + b, pool)
    println(s"async chain: ${asyncChain.join()}")
    val many = (1 to 1000).map(i => CompletableFuture.supplyAsync(supply(i.toLong), pool).thenApplyAsync(_ * 2))
    println(s"many: ${CompletableFuture.allOf(many*).thenApply(_ => many.map(_.join().longValue).sum).join()}")
    pool.shutdown()
    println(s"pool terminated: ${pool.awaitTermination(10, TimeUnit.SECONDS)}")

    // toCompletableFuture and minimalCompletionStage, conversions with Scala futures.
    val stage = CompletableFuture.completedStage("stage")
    println(stage.toCompletableFuture.join())
    println(CompletableFuture.completedFuture(1).minimalCompletionStage().thenApply(_ + 1).toCompletableFuture.join())
    import scala.concurrent.{Await, ExecutionContext, Future}
    import scala.concurrent.duration.*
    given ExecutionContext = ExecutionContext.global
    val fromScala = Future(6 * 7).asJava.toCompletableFuture
    println(s"from Scala: ${fromScala.join()}")
    println(s"to Scala: ${Await.result(CompletableFuture.supplyAsync(supply("cf")).asScala.map(_ + "!"), 10.seconds)}")
