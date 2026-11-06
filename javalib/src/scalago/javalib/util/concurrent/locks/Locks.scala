package scalago.javalib.util.concurrent.locks

import scalago.runtime.Locks

trait Lock:
  def lock(): Unit
  def lockInterruptibly(): Unit
  def tryLock(): Boolean
  def unlock(): Unit
  def newCondition(): Condition

trait Condition:
  def await(): Unit
  def awaitUninterruptibly(): Unit
  def awaitNanos(nanosTimeout: Long): Long
  def signal(): Unit
  def signalAll(): Unit

/** A reentrant mutual-exclusion lock: the monitor of a private object (see runtime/rt/sync.go). */
class ReentrantLock(fair: Boolean) extends Object with Lock with java.io.Serializable:
  def this() = this(false)

  private val sync = new Object

  def lock(): Unit = Locks.enter(sync)
  def lockInterruptibly(): Unit =
    if Thread.interrupted() then throw new java.lang.InterruptedException()
    Locks.enter(sync)
  def tryLock(): Boolean = Locks.tryEnter(sync)
  def unlock(): Unit = Locks.exit(sync)
  def newCondition(): Condition = new ReentrantLock.ConditionObject(Locks.newCondition(sync))
  def isHeldByCurrentThread(): Boolean = Locks.heldByCurrent(sync)
  def isLocked(): Boolean = Locks.isLocked(sync)
  def isFair(): Boolean = fair
  override def toString(): String =
    super.toString() + (if isLocked() then "[Locked]" else "[Unlocked]")

object ReentrantLock:
  private final class ConditionObject(handle: Object) extends Object with Condition with java.io.Serializable:
    def await(): Unit = Locks.await(handle, -1L)
    def awaitUninterruptibly(): Unit =
      var done = false
      while !done do
        try
          Locks.await(handle, -1L)
          done = true
        catch case _: java.lang.InterruptedException => ()
    def awaitNanos(nanosTimeout: Long): Long = if nanosTimeout <= 0 then nanosTimeout else Locks.await(handle, nanosTimeout)
    def signal(): Unit = Locks.signal(handle)
    def signalAll(): Unit = Locks.signalAll(handle)
