package scalago.javalib.lang

import scala.annotation.static

import java.util.concurrent.ThreadFactory

import scalago.runtime.Threads

/** java.lang.Thread on a goroutine. A goroutine that did not start as a Thread (the main one,
 *  or one started by Go code) gets a Thread object the first time it asks for it. Every thread
 *  is goroutine-backed, so virtual threads are threads like the others (daemons, unnamed by
 *  default, `isVirtual`), and Go's scheduler multiplexes all of them.
 */
class Thread private (group: ThreadGroup, target: Runnable, private var name: String, attached: scala.Boolean,
    virtual: scala.Boolean, inheritThreadLocals: scala.Boolean) extends Object with Runnable:
  def this() = this(null, null, Thread.nextName(), false, false, true)
  def this(target: Runnable) = this(null, target, Thread.nextName(), false, false, true)
  def this(name: String) = this(null, null, Thread.checkName(name), false, false, true)
  def this(target: Runnable, name: String) = this(null, target, Thread.checkName(name), false, false, true)
  def this(group: ThreadGroup, target: Runnable) = this(group, target, Thread.nextName(), false, false, true)
  def this(group: ThreadGroup, name: String) = this(group, null, Thread.checkName(name), false, false, true)
  def this(group: ThreadGroup, target: Runnable, name: String) =
    this(group, target, Thread.checkName(name), false, false, true)
  def this(group: ThreadGroup, target: Runnable, name: String, stackSize: scala.Long) =
    this(group, target, Thread.checkName(name), false, false, true)
  def this(group: ThreadGroup, target: Runnable, name: String, stackSize: scala.Long, inherit: scala.Boolean) =
    this(group, target, Thread.checkName(name), false, false, inherit)

  /** The runtime's state of this thread (javalib internal: `LockSupport` parks on it). */
  final val native: Object = Threads.newState(this)

  private val id: scala.Long = if attached && Threads.isMainGoroutine() then 1L else Thread.nextId()
  private val threadGroup: ThreadGroup =
    if virtual then null
    else if group != null then group
    else if attached then ThreadGroup.main
    else Thread.currentThread().getThreadGroup()
  private var daemon: scala.Boolean = virtual || (!attached && Thread.currentThread().isDaemon())
  private var priority: Int = Thread.NORM_PRIORITY
  private var handler: Thread.UncaughtExceptionHandler = null
  /** 0: new, 1: alive, 2: terminated. */
  @volatile private var state: Int = if attached then 1 else 0
  /** WAITING or TIMED_WAITING while parked (`LockSupport`), else null. */
  @volatile private var parkState: Thread.State = null

  /** The object a parked thread is blocked on (`LockSupport.getBlocker`; javalib internal). */
  @volatile var parkBlocker: Object = null

  /** ThreadLocalRandom's state of this thread (javalib internal; only the thread itself uses it). */
  var threadLocalRandomSeed: scala.Long = 0L
  var threadLocalRandomProbe: Int = 0
  var threadLocalRandomSecondarySeed: Int = 0

  /** ThreadLocal values (see ThreadLocal.Values), created on first use. */
  private[lang] var threadLocals: ThreadLocal.Values = null
  /** InheritableThreadLocal values, copied from the creating thread. */
  private[lang] var inheritableThreadLocals: ThreadLocal.Values =
    if attached || !inheritThreadLocals then null else ThreadLocal.inheritedValues(Thread.currentThread())

  private[lang] def wasStarted: scala.Boolean = state != 0

  override def run(): Unit = if target != null then target.run()

  def start(): Unit =
    synchronized {
      if state != 0 then throw new java.lang.IllegalThreadStateException()
      state = 1
    }
    Threads.start(native, daemon)

  /** The body of the thread's goroutine (called by the runtime). */
  def runOnGoroutine(): Unit =
    try run()
    catch case e: java.lang.Throwable => uncaught(e)
    finally
      threadLocals = null
      inheritableThreadLocals = null
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

  def join(millis: scala.Long, nanos: Int): Unit =
    if millis < 0 then throw new java.lang.IllegalArgumentException("timeout value is negative")
    if nanos < 0 || nanos > 999999 then throw new java.lang.IllegalArgumentException("nanosecond timeout value out of range")
    join(if nanos > 0 && millis < scala.Long.MaxValue then millis + 1 else millis)

  /** Waits for at most `duration` (rounded up to milliseconds); whether the thread terminated. */
  def join(duration: java.time.Duration): scala.Boolean =
    val nanos = Thread.saturatedNanos(duration)
    if state == 0 then throw new java.lang.IllegalThreadStateException("Thread not started")
    if nanos > 0 then join(math.max(1L, (nanos + 999999L) / 1000000L))
    !isAlive()

  def isAlive(): scala.Boolean = state == 1
  def getName(): String = name
  def setName(newName: String): Unit = name = Thread.checkName(newName)
  def isDaemon(): scala.Boolean = daemon
  def setDaemon(on: scala.Boolean): Unit =
    if virtual && !on then throw new java.lang.IllegalArgumentException("'false' not legal for virtual threads")
    if state != 0 then throw new java.lang.IllegalThreadStateException()
    daemon = on
  def getPriority(): Int = priority
  def setPriority(newPriority: Int): Unit =
    if newPriority < Thread.MIN_PRIORITY || newPriority > Thread.MAX_PRIORITY then throw new java.lang.IllegalArgumentException()
    if !virtual then
      priority = if threadGroup != null then math.min(newPriority, threadGroup.getMaxPriority()) else newPriority
  @deprecated def getId(): scala.Long = id
  final def threadId(): scala.Long = id
  final def isVirtual(): scala.Boolean = virtual
  def getThreadGroup(): ThreadGroup = if state == 2 then null else if virtual then ThreadGroup.virtualThreads else threadGroup
  @deprecated def checkAccess(): Unit = ()
  def interrupt(): Unit = Threads.interrupt(native)
  def isInterrupted(): scala.Boolean = Threads.isInterrupted(native, false)
  def getUncaughtExceptionHandler(): Thread.UncaughtExceptionHandler =
    if state == 2 then null
    else if handler != null then handler
    else if Thread.defaultHandler != null then Thread.defaultHandler
    else getThreadGroup()
  def setUncaughtExceptionHandler(h: Thread.UncaughtExceptionHandler): Unit = handler = h

  def getState(): Thread.State = state match
    case 0 => Thread.State.NEW
    case 2 => Thread.State.TERMINATED
    case _ =>
      val parked = parkState
      if parked != null then parked else Thread.State.RUNNABLE

  /** Parks the current thread (this one) for `nanos` (forever if negative): `LockSupport`'s
   *  native side (javalib internal).
   */
  def parkCurrent(nanos: scala.Long): Unit =
    parkState = if nanos < 0 then Thread.State.WAITING else Thread.State.TIMED_WAITING
    try Threads.park(native, nanos)
    finally parkState = null

  override def toString(): String =
    if virtual then
      "VirtualThread[#" + id + (if name.isEmpty then "" else "," + name) + "]/" + (if state == 2 then "terminated" else "runnable")
    else
      val group = getThreadGroup()
      "Thread[#" + id + "," + name + "," + priority + "," + (if group != null then group.getName() else "") + "]"

object Thread:
  @static final val MIN_PRIORITY = 1
  @static final val NORM_PRIORITY = 5
  @static final val MAX_PRIORITY = 10

  trait UncaughtExceptionHandler:
    def uncaughtException(t: Thread, e: java.lang.Throwable): Unit

  final class State private (name: String, ordinal: Int) extends Enum[State](name, ordinal)

  object State:
    @static val NEW: State = new State("NEW", 0)
    @static val RUNNABLE: State = new State("RUNNABLE", 1)
    @static val BLOCKED: State = new State("BLOCKED", 2)
    @static val WAITING: State = new State("WAITING", 3)
    @static val TIMED_WAITING: State = new State("TIMED_WAITING", 4)
    @static val TERMINATED: State = new State("TERMINATED", 5)

    @static def values(): Array[State] = Array(NEW, RUNNABLE, BLOCKED, WAITING, TIMED_WAITING, TERMINATED)
    @static def valueOf(name: String): State =
      values().find(_.name() == name).getOrElse(throw new java.lang.IllegalArgumentException("No enum constant java.lang.Thread.State." + name))

  /** Builders of threads (`Thread.ofPlatform()`, `Thread.ofVirtual()`). */
  trait Builder:
    def name(name: String): Builder
    def name(prefix: String, start: scala.Long): Builder
    def inheritInheritableThreadLocals(inherit: scala.Boolean): Builder
    def uncaughtExceptionHandler(ueh: UncaughtExceptionHandler): Builder
    def unstarted(task: Runnable): Thread
    def start(task: Runnable): Thread
    def factory(): ThreadFactory

  object Builder:
    trait OfPlatform extends Builder:
      override def name(name: String): OfPlatform
      override def name(prefix: String, start: scala.Long): OfPlatform
      override def inheritInheritableThreadLocals(inherit: scala.Boolean): OfPlatform
      override def uncaughtExceptionHandler(ueh: UncaughtExceptionHandler): OfPlatform
      def group(group: ThreadGroup): OfPlatform
      def daemon(on: scala.Boolean): OfPlatform
      def daemon(): OfPlatform = daemon(true)
      def priority(priority: Int): OfPlatform
      def stackSize(stackSize: scala.Long): OfPlatform

    trait OfVirtual extends Builder:
      override def name(name: String): OfVirtual
      override def name(prefix: String, start: scala.Long): OfVirtual
      override def inheritInheritableThreadLocals(inherit: scala.Boolean): OfVirtual
      override def uncaughtExceptionHandler(ueh: UncaughtExceptionHandler): OfVirtual

  /** The state both builders share; `platform` selects the kind of thread. */
  private final class ThreadBuilder(platform: scala.Boolean) extends Builder.OfPlatform with Builder.OfVirtual:
    private var namePrefix: String = null
    private var counter: scala.Long = -1L
    private var inherit = true
    private var ueh: UncaughtExceptionHandler = null
    private var threadGroup: ThreadGroup = null
    private var daemonOn: Int = -1 // -1: inherited
    private var prio: Int = 0
    private val counterLock = new Object

    def name(name: String): ThreadBuilder =
      namePrefix = java.util.Objects.requireNonNull(name)
      counter = -1L
      this
    def name(prefix: String, start: scala.Long): ThreadBuilder =
      if start < 0 then throw new java.lang.IllegalArgumentException("'start' is negative")
      namePrefix = java.util.Objects.requireNonNull(prefix)
      counter = start
      this
    def inheritInheritableThreadLocals(inherit: scala.Boolean): ThreadBuilder =
      this.inherit = inherit
      this
    def uncaughtExceptionHandler(ueh: UncaughtExceptionHandler): ThreadBuilder =
      this.ueh = java.util.Objects.requireNonNull(ueh)
      this
    def group(group: ThreadGroup): ThreadBuilder =
      threadGroup = java.util.Objects.requireNonNull(group)
      this
    def daemon(on: scala.Boolean): ThreadBuilder =
      daemonOn = if on then 1 else 0
      this
    override def daemon(): ThreadBuilder = daemon(true)
    def priority(priority: Int): ThreadBuilder =
      if priority < MIN_PRIORITY || priority > MAX_PRIORITY then throw new java.lang.IllegalArgumentException("Thread priority out of range")
      prio = priority
      this
    def stackSize(stackSize: scala.Long): ThreadBuilder =
      if stackSize < 0 then throw new java.lang.IllegalArgumentException("Negative thread stack size")
      this

    private def nextName(prefix: String, count: scala.Long): String =
      if prefix == null then (if platform then Thread.nextName() else "")
      else if count < 0 then prefix
      else prefix + count

    private def newThread(task: Runnable, threadName: String): Thread =
      java.util.Objects.requireNonNull(task)
      val t = new Thread(threadGroup, task, threadName, false, !platform, inherit)
      if daemonOn >= 0 then t.setDaemon(daemonOn == 1)
      if prio != 0 then t.setPriority(prio)
      if ueh != null then t.setUncaughtExceptionHandler(ueh)
      t

    def unstarted(task: Runnable): Thread =
      val threadName = nextName(namePrefix, counter)
      if counter >= 0 then counter += 1
      newThread(task, threadName)
    def start(task: Runnable): Thread =
      val t = unstarted(task)
      t.start()
      t

    /** Thread-safe, as factories must be: a copy of the settings with its own counter. */
    def factory(): ThreadFactory =
      val settings = new ThreadBuilder(platform)
      settings.namePrefix = namePrefix
      settings.counter = counter
      settings.inherit = inherit
      settings.ueh = ueh
      settings.threadGroup = threadGroup
      settings.daemonOn = daemonOn
      settings.prio = prio
      new ThreadFactory:
        // javalib's Thread and Runnable are java.lang's at run time (the plugin renames them).
        def newThread(task: java.lang.Runnable): java.lang.Thread =
          val threadName = settings.counterLock.synchronized {
            val n = settings.nextName(settings.namePrefix, settings.counter)
            if settings.counter >= 0 then settings.counter += 1
            n
          }
          settings.newThread(task.asInstanceOf[Runnable], threadName).asInstanceOf[java.lang.Thread]

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

  private def checkName(name: String): String =
    if name == null then throw new java.lang.NullPointerException("'name' is null")
    name

  private def saturatedNanos(duration: java.time.Duration): scala.Long =
    try duration.toNanos
    catch case _: java.lang.ArithmeticException => if duration.isNegative then scala.Long.MinValue else scala.Long.MaxValue

  @static def currentThread(): Thread =
    val current = Threads.current()
    if current != null then current.asInstanceOf[Thread]
    else
      val t = new Thread(null, null, if Threads.isMainGoroutine() then "main" else nextName(), true, false, false)
      Threads.attach(t.native)
      t

  @static def ofPlatform(): Builder.OfPlatform = new ThreadBuilder(true)
  @static def ofVirtual(): Builder.OfVirtual = new ThreadBuilder(false)
  @static def startVirtualThread(task: Runnable): Thread = ofVirtual().start(task)

  @static def sleep(millis: scala.Long): Unit = Threads.sleep(millis, 0)
  @static def sleep(millis: scala.Long, nanos: Int): Unit = Threads.sleep(millis, nanos)
  @static def sleep(duration: java.time.Duration): Unit =
    val nanos = saturatedNanos(duration)
    if nanos >= 0 then Threads.sleep(nanos / 1000000L, (nanos % 1000000L).toInt)
  @static def `yield`(): Unit = Threads.yieldNow()
  @static def onSpinWait(): Unit = Threads.yieldNow()
  @static def interrupted(): scala.Boolean = Threads.isInterrupted(currentThread().native, true)
  @static def holdsLock(obj: Object): scala.Boolean = Threads.holdsLock(obj)
  @static def activeCount(): Int = Threads.activeCount()
  @static def getDefaultUncaughtExceptionHandler(): UncaughtExceptionHandler = defaultHandler
  @static def setDefaultUncaughtExceptionHandler(h: UncaughtExceptionHandler): Unit = defaultHandler = h
