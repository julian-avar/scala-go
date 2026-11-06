package scalago.gears

import java.util.concurrent.CancellationException

import gears.async.*

import scalago.go.*

/** Go channels in gears code: blocking a gears computation is blocking its goroutine, and
 *  cancelling the computation interrupts the operation.
 */
extension [T](channel: GoChan[T])
  /** Receives the next value (`None` once the channel is closed), cancellably. */
  def awaitReceive(using Async): Option[T] = JvmAsyncOperations.jvmInterruptible(channel.receive())

  /** Sends a value, cancellably. */
  def awaitSend(value: T)(using Async): Unit = JvmAsyncOperations.jvmInterruptible(channel.send(value))

/** Go's `context` package, as far as cancellation needs it. */
@GoImport("context")
object context:
  def Background(): Context = native
  def WithCancel(parent: Context): (Context, () => Unit) = native

  trait Context:
    def Done(): GoChan[Any]
    def Err(): GoError

/** Runs `body` until it completes, or until the Go context `ctx` is done, which cancels it (and
 *  throws `CancellationException`). This is how exported Scala code honours the
 *  `context.Context` of its Go callers.
 */
def withGoContext[T](ctx: context.Context)(body: Async ?=> T)(using Async): T =
  Async.group:
    val work = Future(body)
    val done = Future(ctx.Done().awaitReceive)
    Async.select(
      work.handle(_.get),
      done.handle(_ => throw CancellationException(s"Go context done: ${ctx.Err().Error()}"))
    )
