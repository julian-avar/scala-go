package java.util.concurrent.locks

import scalago.javalib.lang.Thread as GoThread
import scalago.runtime.Threads

/** java.util.concurrent.locks.LockSupport on goroutines: each Thread has a permit (a one-slot Go
 *  channel, see runtime/rt/thread.go). `park` returns when the permit is available (consuming
 *  it), when the thread is interrupted (leaving its status set), after the timeout, or
 *  spuriously, as on the JVM.
 */
object LockSupport:
  // javalib's Thread is java.lang.Thread at run time (the plugin renames it), so the casts are free.
  private def goThread(t: Thread): GoThread = t.asInstanceOf[GoThread]

  def getBlocker(t: Thread): Object = goThread(t).parkBlocker

  def setCurrentBlocker(blocker: Object): Unit = GoThread.currentThread().parkBlocker = blocker

  def park(): Unit = GoThread.currentThread().parkCurrent(-1L)

  def park(blocker: Object): Unit =
    val t = GoThread.currentThread()
    t.parkBlocker = blocker
    try t.parkCurrent(-1L)
    finally t.parkBlocker = null

  def parkNanos(nanos: Long): Unit = if nanos > 0 then GoThread.currentThread().parkCurrent(nanos)

  def parkNanos(blocker: Object, nanos: Long): Unit =
    if nanos > 0 then
      val t = GoThread.currentThread()
      t.parkBlocker = blocker
      try t.parkCurrent(nanos)
      finally t.parkBlocker = null

  /** Parks until `deadline`, in milliseconds since the epoch. */
  def parkUntil(deadline: Long): Unit = parkNanos(untilNanos(deadline))

  def parkUntil(blocker: Object, deadline: Long): Unit = parkNanos(blocker, untilNanos(deadline))

  def unpark(thread: Thread): Unit = if thread != null then Threads.unpark(goThread(thread).native)

  private[locks] def getThreadId(thread: Thread): Long = thread.threadId()

  private def untilNanos(deadline: Long): Long =
    val millis = deadline - System.currentTimeMillis()
    if millis <= 0 then 0L
    else if millis >= Long.MaxValue / 1000000L then Long.MaxValue
    else millis * 1000000L
