import scala.concurrent.duration.*

import gears.async.*
import gears.async.default.given

import scalago.go.*
import scalago.gears.*

/** gears, unmodified, on goroutines (and on JVM virtual threads: the output is the same). */
object Main:
  def main(args: Array[String]): Unit =
    Async.blocking:
      val a = Future:
        AsyncOperations.sleep(30)
        1
      val b = Future(2)
      println(s"sum: ${a.await + b.await}")

      val squares = (1 to 5).map(i => Future(i * i))
      println(s"squares: ${squares.awaitAll}")

      val fast = Future:
        AsyncOperations.sleep(10)
        "fast"
      val slow = Future:
        AsyncOperations.sleep(2000)
        "slow"
      println(s"race: ${Async.race(fast, slow).await}")

      val sleeper = Future:
        AsyncOperations.sleep(10000)
        "woke up"
      sleeper.cancel()
      println(s"cancelled: ${sleeper.awaitResult.isFailure}")

      println(s"timeout: ${withTimeoutOption(50.millis) { AsyncOperations.sleep(5000); 1 }}")
      println(s"in time: ${withTimeoutOption(5.seconds)(42)}")

      val channel = BufferedChannel[Int](2)
      val producer = Future:
        for i <- 1 to 5 do channel.send(i * 10)
      val received = List.fill(5)(channel.read().toOption.get)
      producer.await
      println(s"channel: $received")

      val failing = Future(throw new IllegalStateException("boom"))
      println(s"failure: ${failing.awaitResult.failed.map(_.getMessage)}")

      // Go channels in gears code: receiving is cancellable.
      val goChannel = GoChan.make[Int]
      val sender = Future:
        for i <- 1 to 3 do goChannel.awaitSend(i)
        goChannel.close()
      val fromGo = Iterator.continually(goChannel.awaitReceive).takeWhile(_.isDefined).map(_.get).toList
      sender.await
      println(s"Go channel: $fromGo")
      val stuck = Future(GoChan.make[Int].awaitReceive)
      stuck.cancel()
      println(s"blocked receive cancelled: ${stuck.awaitResult.isFailure}")
