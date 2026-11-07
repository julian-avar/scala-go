package scalago.javalib.lang

import scala.annotation.static

import scalago.runtime.Threads

/** java.lang.Thread on a goroutine. A goroutine that did not start as a Thread (the main one,
 *  or one started by Go code) gets a Thread object the first time it asks for it.
 */
class Thread private (target: Runnable, private var name: String, attached: scala.Boolean) extends Object with Runnable:
  def this() = this(null, Thread.nextName(), false)
  def this(target: Runnable) = this(target, Thread.nextName(), false)
  def this(name: String) = this(null, name, false)
  def this(target: Runnable, name: String) = this(target, name, false)

  private val id: scala.Long = if attached && Threads.isMainGoroutine() then 1L else Thread.nextId()
  private var daemon: scala.Boolean = !attached && Thread.currentThread().isDaemon()
  private var priority: Int = Thread.NORM_PRIORITY
  private var handler: Thread.UncaughtExceptionHandler = null
  /** 0: new, 1: alive, 2: terminated. */
  @volatile private var state: Int = if attached then 1 else 0

  override def run(): Unit = if target != null then target.run()

  def start(): Unit =
    synchronized {
      if state != 0 then throw new java.lang.IllegalThreadStateException()
      state = 1
    }
    Threads.start(this, daemon)

  /** The body of the thread's goroutine (called by the runtime). */
  def runOnGoroutine(): Unit =
    try run()
    catch case e: java.lang.Throwable => uncaught(e)
    finally
      synchronized {
        state = 2
        notifyAll()
      }

  private def uncaught(e: java.lang.Throwable): Unit =
    val h = if handler != null then handler else Thread.defaultHandler
    if h != null then h.uncaughtException(this, e)
    else
      java.lang.System.err.print("Exception in thread \"" + name + "\" ")
      e.printStackTrace(java.lang.System.err)

  def join(): Unit = synchronized {
    while isAlive() do wait()
  }

  def join(millis: scala.Long): Unit =
    if millis < 0 then throw new java.lang.IllegalArgumentException("timeout value is negative")
    if millis == 0 then join()
    else
      synchronized {
        val deadline = java.lang.System.nanoTime() + millis * 1000000L
        var remaining = millis
        while isAlive() && remaining > 0 do
          wait(remaining)
          remaining = (deadline - java.lang.System.nanoTime()) / 1000000L
      }

  def isAlive(): scala.Boolean = state == 1
  def getName(): String = name
  def setName(newName: String): Unit =
    if newName == null then throw new java.lang.NullPointerException("name cannot be null")
    name = newName
  def isDaemon(): scala.Boolean = daemon
  def setDaemon(on: scala.Boolean): Unit =
    if state != 0 then throw new java.lang.IllegalThreadStateException()
    daemon = on
  def getPriority(): Int = priority
  def setPriority(newPriority: Int): Unit =
    if newPriority < Thread.MIN_PRIORITY || newPriority > Thread.MAX_PRIORITY then throw new java.lang.IllegalArgumentException()
    priority = newPriority
  def getId(): scala.Long = id
  def threadId(): scala.Long = id
  def isVirtual(): scala.Boolean = false
  def interrupt(): Unit = Threads.interrupt(this)
  def isInterrupted(): scala.Boolean = Threads.isInterrupted(this, false)
  def getUncaughtExceptionHandler(): Thread.UncaughtExceptionHandler = handler
  def setUncaughtExceptionHandler(h: Thread.UncaughtExceptionHandler): Unit = handler = h

  override def toString(): String = "Thread[#" + id + "," + name + "," + priority + ",main]"

object Thread:
  @static final val MIN_PRIORITY = 1
  @static final val NORM_PRIORITY = 5
  @static final val MAX_PRIORITY = 10

  trait UncaughtExceptionHandler:
    def uncaughtException(t: Thread, e: java.lang.Throwable): Unit

  private var threadNumber = 0
  private var lastId = 1L
  @volatile private var defaultHandler: UncaughtExceptionHandler = null

  private def nextName(): String = synchronized {
    val n = threadNumber
    threadNumber += 1
    "Thread-" + n
  }

  private def nextId(): scala.Long = synchronized {
    lastId += 1
    lastId
  }

  @static def currentThread(): Thread =
    val current = Threads.current()
    if current != null then current.asInstanceOf[Thread]
    else
      val t = new Thread(null, if Threads.isMainGoroutine() then "main" else nextName(), true)
      Threads.attach(t)
      t

  @static def sleep(millis: scala.Long): Unit = Threads.sleep(millis, 0)
  @static def sleep(millis: scala.Long, nanos: Int): Unit = Threads.sleep(millis, nanos)
  @static def `yield`(): Unit = Threads.yieldNow()
  @static def onSpinWait(): Unit = Threads.yieldNow()
  @static def interrupted(): scala.Boolean = Threads.isInterrupted(currentThread(), true)
  @static def getDefaultUncaughtExceptionHandler(): UncaughtExceptionHandler = defaultHandler
  @static def setDefaultUncaughtExceptionHandler(h: UncaughtExceptionHandler): Unit = defaultHandler = h
