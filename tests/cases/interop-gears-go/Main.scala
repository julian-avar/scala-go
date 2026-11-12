import scala.util.Try

import gears.async.*
import gears.async.default.given

import scalago.go.*
import scalago.gears.*

@GoImport("example.com/feed")
object feed:
  def Numbers(n: Int): GoChan[GoInt] = native
  def Never(): GoChan[GoInt] = native
  def Square(x: Int): Int = native
  def Cancelled(): context.Context = native

// gears on goroutines with Go code (docs/_docs/user/concurrency.md, "gears", "Go channels", "Go
// contexts"): Futures calling Go, channels that Go code feeds, Go contexts cancelling gears code.
object Main:
  def failure(t: Try[?]): String = t.failed.map(e => e.getClass.getSimpleName).getOrElse("no failure")

  def main(args: Array[String]): Unit =
    Async.blocking:
      // Futures calling Go code concurrently.
      val squares = (1 to 8).map(i => Future(feed.Square(i)))
      println(s"squares: ${squares.awaitAll.sum}")

      // A Go goroutine feeds a channel that gears code drains with awaitReceive.
      val numbers = feed.Numbers(5)
      val drained = Iterator.continually(numbers.awaitReceive).takeWhile(_.isDefined).map(_.get.toLong).toList
      println(s"from Go: $drained")
      val sum = Future:
        val c = feed.Numbers(100)
        var total = 0L
        var next = c.awaitReceive
        while next.isDefined do
          total += next.get.toLong
          next = c.awaitReceive
        total
      println(s"in a Future: ${sum.await}")

      // Cancelling a Future blocked on a Go channel; racing a receive against a timeout.
      val stuck = Future(feed.Never().awaitReceive)
      stuck.cancel()
      println(s"cancelled receive: ${failure(stuck.awaitResult)}")
      val raced = Async.race(Future(feed.Never().awaitReceive.map(_.toLong.toString)), Future { AsyncOperations.sleep(20); Some("timeout") })
      println(s"race: ${raced.await}")

      // Go contexts: cancelled while running, already cancelled, never cancelled.
      val (ctx, cancel) = context.WithCancel(context.Background())
      val running = Future(Try(withGoContext(ctx) { AsyncOperations.sleep(10000); "too late" }))
      AsyncOperations.sleep(20)
      cancel()
      println(s"cancelled context: ${failure(running.await)} ${ctx.Err().Error()}")
      println(s"already cancelled: ${failure(Try(withGoContext(feed.Cancelled()) { AsyncOperations.sleep(10000) }))}")
      println(s"background: ${withGoContext(context.Background())(feed.Square(9))}")
