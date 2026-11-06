package scalago.gearsrt

import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.duration.FiniteDuration

import gears.async.{Cancellable, Scheduler}

/** gears' scheduler on goroutines. The plugin emits this object as `gears.async.VThreadScheduler`,
 *  replacing gears' own (which needs `Thread.ofVirtual` and a `VarHandle`) in the linked program.
 *  The rest of gears, `VThreadSupport` included, is used as published: a virtual thread is a
 *  goroutine-backed `java.lang.Thread`, and suspending one blocks its goroutine.
 */
object VThreadScheduler extends Scheduler:
  override def execute(body: Runnable): Unit =
    val thread = new Thread(body)
    thread.setDaemon(true) // as virtual threads are
    thread.start()

  override def schedule(delay: FiniteDuration, body: Runnable): Cancellable = ScheduledRunnable(delay, body)

  private class ScheduledRunnable(delay: FiniteDuration, body: Runnable) extends Cancellable:
    /** Cleared by whichever comes first: running the body or cancelling. */
    private val pending = AtomicBoolean(true)

    private val thread = new Thread(() =>
      try Thread.sleep(delay.toMillis)
      catch case _: InterruptedException => () // cancelled
      if pending.getAndSet(false) then body.run()
    )
    thread.setDaemon(true)
    thread.start()

    override def cancel(): Unit =
      if pending.getAndSet(false) then thread.interrupt()
