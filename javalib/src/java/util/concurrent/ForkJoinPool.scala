package java.util.concurrent

import scala.annotation.static

import java.util.{ArrayList, Collection, List as JList}
import java.util.concurrent.locks.{LockSupport, ReentrantLock}
import java.util.function.Predicate

import scalago.runtime.Intrinsics

/** java.util.concurrent.ForkJoinPool on goroutines.
 *
 *  The JDK's pool is a large lock-free design built for platform threads. Here every worker is a
 *  goroutine-backed `ForkJoinWorkerThread`, and Go's scheduler already multiplexes goroutines over
 *  processors, so this pool keeps the JDK's structure and API but not its machinery:
 *
 *  - Each worker owns a work queue (a deque under a spin lock): `fork()` and tasks submitted from
 *    the worker go there; the owner takes them LIFO (FIFO in async mode), idle workers steal FIFO.
 *    Tasks submitted from other threads go to a shared lock-free queue.
 *  - Workers are created on demand, up to the parallelism, and park when they find no work (after
 *    registering as idle, then scanning once more, so that no signal is lost). A worker idle for
 *    the keep-alive time (60 s by default) exits. Submitting work wakes an idle worker, or starts
 *    a new one.
 *  - Blocking is compensated, as in the JDK: a worker blocked in `managedBlock` (Scala's
 *    `blocking {}`) or in `join` does not count against the parallelism, so another worker can
 *    run queued tasks (up to `maximumPoolSize` workers).
 *  - `join` helps: a worker joining a task runs it if it is still in its own queue, and otherwise
 *    runs other queued tasks until it completes, before blocking.
 */
class ForkJoinPool(
    parallelism: Int,
    factory: ForkJoinPool.ForkJoinWorkerThreadFactory,
    handler: Thread.UncaughtExceptionHandler,
    asyncMode: Boolean,
    corePoolSize: Int,
    maximumPoolSize: Int,
    minimumRunnable: Int,
    saturate: Predicate[? >: ForkJoinPool],
    keepAliveTime: Long,
    unit: TimeUnit
) extends AbstractExecutorService:
  import ForkJoinPool.*

  def this(parallelism: Int, factory: ForkJoinPool.ForkJoinWorkerThreadFactory, handler: Thread.UncaughtExceptionHandler,
      asyncMode: Boolean) =
    this(parallelism, factory, handler, asyncMode, 0, ForkJoinPool.MaxCap, 1, null, ForkJoinPool.DefaultKeepAliveMillis,
      TimeUnit.MILLISECONDS)
  def this(parallelism: Int) = this(parallelism, ForkJoinPool.defaultFactory(), null, false)
  def this() = this(math.min(ForkJoinPool.MaxCap, Runtime.getRuntime().availableProcessors()))

  if parallelism <= 0 || parallelism > MaxCap || maximumPoolSize < parallelism || keepAliveTime <= 0L then
    throw new IllegalArgumentException()
  if factory == null || unit == null then throw new NullPointerException()

  private[concurrent] val ueh: Thread.UncaughtExceptionHandler = handler
  private val keepAliveNanos = math.max(unit.toNanos(keepAliveTime), TimeUnit.MILLISECONDS.toNanos(1L))
  private val maxWorkers = math.min(MaxCap, math.max(parallelism, maximumPoolSize))
  @volatile private var targetParallelism = parallelism
  private[concurrent] val isCommon = ForkJoinPool.constructingCommon
  private val workerNamePrefix: String =
    if isCommon then "ForkJoinPool.commonPool-worker-" else "ForkJoinPool-" + ForkJoinPool.nextPoolId() + "-worker-"

  /** Tasks submitted by threads that are not workers of this pool. */
  private val submissions = new ConcurrentLinkedQueue[ForkJoinTask[?]]
  @volatile private var submissionCount = 0

  /** Registration, idle workers and termination: rare operations, under one lock. */
  private val lock = new ReentrantLock
  private val terminated = lock.newCondition()
  /** The workers' queues, copied on write (so stealing reads them without locking). */
  @volatile private var queues: Array[WorkQueue] = new Array[WorkQueue](0)
  /** Idle (parked) workers: a lock-free stack, most recent on top. A node is current while its
   *  worker is idle and the node is the worker's latest; others are skipped and dropped.
   */
  @volatile private var idleTop: IdleNode = null
  @volatile private var workerCount = 0
  /** Workers woken or started that are still looking for a task. While one is, pushing a task
   *  wakes nobody: it will find the task, and wake another worker if it sees more (as Go's
   *  scheduler does with spinning threads). Without this, a chain of tasks (a future's
   *  callbacks) would hop between workers, each hop a wake-up.
   */
  @volatile private var spinning = 0
  @volatile private var idleCount = 0
  /** Workers blocked in managedBlock or join, which do not count against the parallelism. */
  @volatile private var blockedCount = 0
  @volatile private var runState = Running
  @volatile private var stealCount = 0L
  private val workerNumbers = new java.util.concurrent.atomic.AtomicInteger

  // ---- Submission --------------------------------------------------------------------------

  /** Queues `task`: on the current worker's queue if it belongs to this pool, else shared. */
  private[concurrent] final def push(task: ForkJoinTask[?], external: Boolean): Unit =
    if task == null then throw new NullPointerException()
    if runState != Running then throw new RejectedExecutionException()
    Thread.currentThread() match
      case wt: ForkJoinWorkerThread if !external && (wt.pool eq this) => wt.workQueue.push(task)
      case _ =>
        submissions.offer(task)
        Intrinsics.getAndAdd(submissionCount, 1)
        // Submissions racing with shutdown: the pool must still run or reject them.
        if runState != Running && submissions.remove(task) then
          Intrinsics.getAndAdd(submissionCount, -1)
          throw new RejectedExecutionException()
    signalWork()

  /** `fork()` in worker `w` of this pool. */
  private[concurrent] final def pushLocal(w: WorkQueue, task: ForkJoinTask[?]): Unit =
    w.push(task)
    signalWork()

  private[concurrent] final def isStopping: Boolean = runState >= Stop

  /** A task for worker `w` (`ForkJoinTask.pollTask`). */
  private[concurrent] final def pollTaskFor(w: WorkQueue): ForkJoinTask[?] = scan(w)

  private def pollSubmissionInternal(): ForkJoinTask[?] =
    if submissionCount == 0 then null
    else
      val t = submissions.poll()
      if t != null then Intrinsics.getAndAdd(submissionCount, -1)
      t

  def invoke[T](task: ForkJoinTask[T]): T =
    push(task, false)
    task.join()

  def execute(task: ForkJoinTask[?]): Unit = push(task, false)

  override def execute(task: Runnable): Unit =
    if task == null then throw new NullPointerException()
    val job = task match
      case t: ForkJoinTask[?] => t
      case _                  => new ForkJoinTask.RunnableExecuteAction(task)
    push(job, false)

  def submit[T](task: ForkJoinTask[T]): ForkJoinTask[T] =
    push(task, false)
    task

  override def submit[T](task: Callable[T]): ForkJoinTask[T] =
    val t = new ForkJoinTask.AdaptedCallable[T](task)
    push(t, false)
    t

  override def submit[T](task: Runnable, result: T): ForkJoinTask[T] =
    val t = new ForkJoinTask.AdaptedRunnable[T](task, result)
    push(t, false)
    t

  override def submit(task: Runnable): ForkJoinTask[?] =
    if task == null then throw new NullPointerException()
    val t: ForkJoinTask[?] = task match
      case f: ForkJoinTask[?] => f
      case _                  => new ForkJoinTask.AdaptedRunnableAction(task)
    push(t, false)
    t

  /** Like `execute`, without waking a worker: the task runs when a worker looks for work. */
  def lazySubmit[T](task: ForkJoinTask[T]): ForkJoinTask[T] =
    submit(task)

  override def invokeAll[T](tasks: Collection[? <: Callable[T]]): JList[Future[T]] =
    val futures = new ArrayList[Future[T]](tasks.size())
    try
      tasks.forEach { c =>
        val f = new ForkJoinTask.AdaptedInterruptibleCallable[T](c)
        futures.add(f)
        push(f, false)
      }
      futures.forEach(_.asInstanceOf[ForkJoinTask[?]].quietlyJoin())
      futures
    catch
      case t: Throwable =>
        futures.forEach(f => ForkJoinTask.cancelIgnoringExceptions(f))
        throw t

  override protected[concurrent] def newTaskFor[T](runnable: Runnable, value: T): RunnableFuture[T] =
    new ForkJoinTask.AdaptedRunnable[T](runnable, value)

  override protected[concurrent] def newTaskFor[T](callable: Callable[T]): RunnableFuture[T] =
    new ForkJoinTask.AdaptedCallable[T](callable)

  // ---- Workers -----------------------------------------------------------------------------

  private def activeLimitReached: Boolean = workerCount - blockedCount >= targetParallelism

  /** Wakes an idle worker, or starts one if the pool is below its parallelism. */
  private[concurrent] final def signalWork(): Unit =
    if spinning == 0 && Intrinsics.compareAndSet(spinning, 0, 1) then
      if idleCount > 0 then
        val w = popIdle()
        if w != null then
          LockSupport.unpark(w.owner) // it starts spinning
          return
      if !tryAddWorker() then Intrinsics.getAndAdd(spinning, -1)

  /** Takes the most recently idle worker off the idle stack (it is no longer idle), or null. */
  private def popIdle(): WorkQueue =
    while true do
      val top = idleTop
      if top == null then return null
      if Intrinsics.compareAndSet(idleTop, top, top.next) && (top.worker.idleNode eq top) && unidle(top.worker) then
        return top.worker
    null

  /** Pushes `w` on the idle stack, dropping the dead nodes on top. */
  private def pushIdle(w: WorkQueue): Unit =
    val node = new IdleNode(w)
    w.idleNode = node
    w.idle = true
    Intrinsics.getAndAdd(idleCount, 1)
    var pushed = false
    while !pushed do
      var top = idleTop
      while top != null && ((top.worker.idleNode ne top) || !top.worker.idle) && Intrinsics.compareAndSet(idleTop, top, top.next) do
        top = idleTop
      node.next = top
      pushed = Intrinsics.compareAndSet(idleTop, top, node)

  /** Makes `w` not idle; false if somebody else (a signal) already did. */
  private def unidle(w: WorkQueue): Boolean =
    if Intrinsics.compareAndSet(w.idle, true, false) then
      Intrinsics.getAndAdd(idleCount, -1)
      true
    else false

  /** Starts a worker (which starts spinning) if the pool is below its parallelism. */
  private def tryAddWorker(): Boolean =
    var started = false
    lock.lock()
    try
      if runState == Running && !activeLimitReached && workerCount < maxWorkers then
        workerCount += 1
        started = true
    finally lock.unlock()
    started && {
      var ok = false
      try
        val t = factory.newThread(this)
        if t != null then
          t.workQueue.spinning = true
          t.start()
          ok = true
      finally
        if !ok then
          lock.lock()
          try
            workerCount -= 1
            tryTerminate()
          finally lock.unlock()
      ok
    }

  private[concurrent] final def nextWorkerThreadName(): String = workerNamePrefix + workerNumbers.incrementAndGet()

  private[concurrent] final def registerWorker(w: WorkQueue): Unit =
    lock.lock()
    try
      val qs = queues
      val copy = java.util.Arrays.copyOf(qs, qs.length + 1)
      copy(qs.length) = w
      w.poolIndex = qs.length
      queues = copy
    finally lock.unlock()

  private[concurrent] final def deregisterWorker(wt: ForkJoinWorkerThread, ex: Throwable): Unit =
    val w = wt.workQueue
    if w.spinning then
      w.spinning = false
      Intrinsics.getAndAdd(spinning, -1)
    lock.lock()
    try
      val qs = queues
      val i = qs.indexWhere(_ eq w)
      if i >= 0 then
        val copy = new Array[WorkQueue](qs.length - 1)
        System.arraycopy(qs, 0, copy, 0, i)
        System.arraycopy(qs, i + 1, copy, i, qs.length - i - 1)
        for j <- i until copy.length do copy(j).poolIndex = j
        queues = copy
      unidle(w)
      workerCount -= 1
      stealCount += w.stolen
    finally lock.unlock()
    // Tasks left in its queue (it failed, or the pool stopped) go to the others.
    var t = w.poll()
    while t != null do
      if runState >= Stop then ForkJoinTask.cancelIgnoringExceptions(t)
      else
        submissions.offer(t)
        Intrinsics.getAndAdd(submissionCount, 1)
      t = w.poll()
    if runState < Stop then signalWork()
    lock.lock()
    try tryTerminate()
    finally lock.unlock()
    if ex != null then ForkJoinTask.rethrow(ex)

  /** The worker loop: runs tasks until the pool stops or the worker stays idle too long. */
  private[concurrent] final def runWorker(w: WorkQueue): Unit =
    while runState < Stop do
      val t = scan(w)
      if w.spinning then
        // Done looking: if there is more work than this task, another worker should look.
        w.spinning = false
        Intrinsics.getAndAdd(spinning, -1)
        if t != null && hasQueuedWork then signalWork()
      if t != null then w.runTask(t)
      else if !awaitWork(w) then return

  /** A task for `w`: its own, then a submission, then one stolen from another worker. */
  private def scan(w: WorkQueue): ForkJoinTask[?] =
    var t = w.nextLocalTask(asyncMode)
    if t == null then t = pollSubmissionInternal()
    if t == null then t = steal(w)
    t

  private def steal(w: WorkQueue): ForkJoinTask[?] =
    val qs = queues
    val n = qs.length
    if n > 1 then
      val start = if w == null then 0 else (ThreadLocalRandom.current().nextInt() & Int.MaxValue) % n
      var i = 0
      while i < n do
        val q = qs((start + i) % n)
        if (q ne w) && q.size > 0 then
          val t = q.poll()
          if t != null then
            if w != null then w.stolen += 1
            return t
        i += 1
    null

  private def hasQueuedWork: Boolean =
    submissionCount > 0 || queues.exists(_.size > 0)

  /** Parks `w` until signaled; false if the worker should exit. */
  private def awaitWork(w: WorkQueue): Boolean =
    if runState >= Stop then return false
    pushIdle(w)
    if runState == Shutdown then
      lock.lock()
      try if isQuiescentLocked then tryTerminate()
      finally lock.unlock()
    // A task pushed before we became visible as idle did not signal us: look again.
    if hasQueuedWork && unidle(w) then return true
    val deadline = System.nanoTime() + keepAliveNanos
    while w.idle && runState < Stop do
      val remaining = deadline - System.nanoTime()
      if remaining <= 0L then
        // Timed out: exit unless the pool needs this worker after all.
        if unidle(w) then return hasQueuedWork
      else
        LockSupport.parkNanos(this, remaining)
        Thread.interrupted() // stray interrupts (shutdownNow) must not stop later tasks
    if !w.idle then w.spinning = true // signaled: signalWork counted it as spinning
    runState < Stop || { unidle(w); false }

  // ---- Blocking and joining ----------------------------------------------------------------

  /** Called by a worker before it blocks: lets another worker take its place. */
  private[concurrent] final def compensate(): Unit =
    Intrinsics.getAndAdd(blockedCount, 1)
    if hasQueuedWork then signalWork()

  private[concurrent] final def uncompensate(): Unit = Intrinsics.getAndAdd(blockedCount, -1)

  /** Helps until `task` completes: runs it if it is still in `w`'s queue, else other tasks.
   *  Returns the task's status, or UNCOMPENSATE if the caller must block (compensated).
   */
  private[concurrent] final def helpJoin(task: ForkJoinTask[?], w: WorkQueue): Int =
    if w.tryUnpush(task) then task.doExec()
    var s = task.status
    while s >= 0 && runState < Stop do
      var t = w.nextLocalTask(false)
      if t == null then t = steal(w)
      if t == null then
        compensate()
        return ForkJoinTask.UNCOMPENSATE
      w.runTask(t)
      s = task.status
    s

  private[concurrent] final def helpComplete(task: ForkJoinTask[?], w: WorkQueue, maxTasks: Int): Int =
    var n = 0
    var s = task.status
    while s >= 0 && (maxTasks <= 0 || n < maxTasks) do
      val t = w.nextLocalTask(false)
      if t == null then return s
      w.runTask(t)
      n += 1
      s = task.status
    s

  private def isQuiescentLocked: Boolean = idleCount + blockedCount >= workerCount && !hasQueuedWork

  // ---- Introspection -----------------------------------------------------------------------

  def getFactory(): ForkJoinPool.ForkJoinWorkerThreadFactory = factory
  def getUncaughtExceptionHandler(): Thread.UncaughtExceptionHandler = ueh
  def getParallelism(): Int = targetParallelism
  def setParallelism(size: Int): Int =
    if size < 1 || size > MaxCap then throw new IllegalArgumentException()
    if isCommon then throw new UnsupportedOperationException("Cannot set common pool parallelism")
    val previous = targetParallelism
    targetParallelism = size
    previous
  def getPoolSize(): Int = workerCount
  def getAsyncMode(): Boolean = asyncMode
  def getRunningThreadCount(): Int = math.max(0, workerCount - idleCount - blockedCount)
  def getActiveThreadCount(): Int = math.max(0, workerCount - idleCount)
  def isQuiescent(): Boolean = idleCount >= workerCount && !hasQueuedWork
  def getStealCount(): Long = stealCount + queues.foldLeft(0L)(_ + _.stolen)
  def getQueuedTaskCount(): Long = queues.foldLeft(0L)(_ + _.size)
  def getQueuedSubmissionCount(): Int = submissionCount
  def hasQueuedSubmissions(): Boolean = submissionCount > 0
  protected[concurrent] def pollSubmission(): ForkJoinTask[?] = pollSubmissionInternal()

  protected def drainTasksTo(c: Collection[? >: ForkJoinTask[?]]): Int =
    var n = 0
    var t = pollSubmissionInternal()
    while t != null do
      c.add(t)
      n += 1
      t = pollSubmissionInternal()
    for q <- queues do
      t = q.poll()
      while t != null do
        c.add(t)
        n += 1
        t = q.poll()
    n

  override def toString(): String =
    val state =
      if runState == Terminated then "Terminated"
      else if runState >= Shutdown then "Shutting down"
      else "Running"
    super.toString() + "[" + state + ", parallelism = " + targetParallelism + ", size = " + workerCount +
      ", active = " + getActiveThreadCount() + ", running = " + getRunningThreadCount() + ", steals = " +
      getStealCount() + ", tasks = " + getQueuedTaskCount() + ", submissions = " + submissionCount + "]"

  // ---- Termination -------------------------------------------------------------------------

  def shutdown(): Unit =
    if !isCommon then
      lock.lock()
      try
        if runState == Running then runState = Shutdown
        if isQuiescentLocked then tryTerminate()
      finally lock.unlock()

  def shutdownNow(): JList[Runnable] =
    if !isCommon then
      lock.lock()
      try
        if runState < Stop then runState = Stop
        tryTerminate()
      finally lock.unlock()
    new ArrayList[Runnable]()

  /** Moves to Stop once shut down and quiescent, then to Terminated once no worker is left;
   *  wakes the workers so that they exit. Called holding the lock.
   */
  private def tryTerminate(): Unit =
    if runState == Shutdown && isQuiescentLocked then runState = Stop
    if runState == Stop then
      // Cancel what is left, and wake everybody so that they exit.
      var t = pollSubmissionInternal()
      while t != null do
        ForkJoinTask.cancelIgnoringExceptions(t)
        t = pollSubmissionInternal()
      for q <- queues do
        t = q.poll()
        while t != null do
          ForkJoinTask.cancelIgnoringExceptions(t)
          t = q.poll()
        q.owner.interrupt()
      for q <- queues do LockSupport.unpark(q.owner)
      if workerCount == 0 then
        runState = Terminated
        terminated.signalAll()

  def isTerminated(): Boolean = runState == Terminated
  def isTerminating(): Boolean = runState >= Shutdown && runState != Terminated && !isCommon && runState != Running
  def isShutdown(): Boolean = runState >= Shutdown

  def awaitTermination(timeout: Long, unit: TimeUnit): Boolean =
    if isCommon then
      awaitQuiescence(timeout, unit)
      false
    else
      var nanos = unit.toNanos(timeout)
      lock.lock()
      try
        while runState != Terminated do
          if nanos <= 0L then return false
          nanos = terminated.awaitNanos(nanos)
        true
      finally lock.unlock()

  def awaitQuiescence(timeout: Long, unit: TimeUnit): Boolean =
    val deadline = System.nanoTime() + unit.toNanos(timeout)
    Thread.currentThread() match
      case wt: ForkJoinWorkerThread if wt.pool eq this =>
        var t = scan(wt.workQueue)
        while t != null do
          wt.workQueue.runTask(t)
          t = scan(wt.workQueue)
      case _ => ()
    while !isQuiescent() do
      if System.nanoTime() - deadline >= 0L then return false
      Thread.sleep(1)
    true

  override def close(): Unit =
    if !isCommon then
      var interrupted = false
      shutdown()
      while !isTerminated() do
        try awaitTermination(1L, TimeUnit.DAYS)
        catch
          case _: InterruptedException =>
            if !interrupted then
              shutdownNow()
              interrupted = true
      if interrupted then Thread.currentThread().interrupt()

object ForkJoinPool:
  @static val defaultForkJoinWorkerThreadFactory: ForkJoinWorkerThreadFactory =
    new ForkJoinWorkerThreadFactory:
      def newThread(pool: ForkJoinPool): ForkJoinWorkerThread = new ForkJoinWorkerThread(null, pool)

  // (Constructors cannot refer to the static field directly.)
  private def defaultFactory(): ForkJoinWorkerThreadFactory = defaultForkJoinWorkerThreadFactory

  /** Creates the workers of a pool. */
  trait ForkJoinWorkerThreadFactory:
    def newThread(pool: ForkJoinPool): ForkJoinWorkerThread

  /** A way to block that lets the pool compensate (`managedBlock`). */
  trait ManagedBlocker:
    def block(): Boolean
    def isReleasable(): Boolean

  private[concurrent] final val MaxCap = 0x7fff
  private final val DefaultKeepAliveMillis = 60000L
  private final val Running = 0
  private final val Shutdown = 1
  private final val Stop = 2
  private final val Terminated = 3

  private var poolIds = 0
  private def nextPoolId(): Int = synchronized {
    poolIds += 1
    poolIds
  }

  @volatile private var constructingCommon = false

  private[concurrent] lazy val common: ForkJoinPool =
    val parallelism = math.max(1, math.min(MaxCap, Runtime.getRuntime().availableProcessors() - 1))
    constructingCommon = true
    try new ForkJoinPool(parallelism, defaultForkJoinWorkerThreadFactory, null, false, 0, parallelism + 256, 1, null,
      DefaultKeepAliveMillis, TimeUnit.MILLISECONDS)
    finally constructingCommon = false

  def commonPool(): ForkJoinPool = common

  def getCommonPoolParallelism(): Int = common.getParallelism()

  /** Blocks until `blocker` is releasable; in a worker, lets the pool start or wake another worker. */
  def managedBlock(blocker: ManagedBlocker): Unit =
    if blocker == null then throw new NullPointerException()
    Thread.currentThread() match
      case wt: ForkJoinWorkerThread if wt.pool != null && !blocker.isReleasable() =>
        val p = wt.pool
        p.compensate()
        try while !blocker.isReleasable() && !blocker.block() do ()
        finally p.uncompensate()
      case _ =>
        while !blocker.isReleasable() && !blocker.block() do ()

  /** In the JDK, a worker about to wait for a `CompletableFuture` first runs the pool's pending
   *  asynchronous tasks. Here it blocks through `managedBlock`, which lets another worker run them.
   */
  private[concurrent] def helpAsyncBlocker(e: Executor, blocker: ManagedBlocker): Unit = ()

  /** The JDK's `helpQuiescePool`: runs the current worker's tasks, or waits for the common pool. */
  private[concurrent] def helpQuiescePool(): Unit =
    Thread.currentThread() match
      case wt: ForkJoinWorkerThread => wt.pool.awaitQuiescence(Long.MaxValue, TimeUnit.NANOSECONDS)
      case _                        => common.awaitQuiescence(Long.MaxValue, TimeUnit.NANOSECONDS)

  /** An estimate of how many more tasks the current worker has than others could steal. */
  private[concurrent] def getSurplusQueuedTaskCount(): Int =
    Thread.currentThread() match
      case wt: ForkJoinWorkerThread =>
        val p = wt.pool
        math.max(0, wt.workQueue.size - math.max(0, p.getPoolSize() - p.getActiveThreadCount()))
      case _ => 0

  /** An entry of the idle stack. */
  private final class IdleNode(val worker: WorkQueue):
    @volatile var next: IdleNode = null

  /** A worker's queue of tasks: a ring buffer under a spin lock. The owner takes from the top
   *  (LIFO) or, in async mode, the bottom; other workers steal from the bottom.
   */
  private[concurrent] final class WorkQueue(val owner: ForkJoinWorkerThread):
    @volatile private var locked = false
    private var tasks = new Array[ForkJoinTask[?]](16)
    private var base = 0 // index of the oldest task
    private var top = 0 // index after the newest task
    /** The number of queued tasks, readable without the lock. */
    @volatile var size = 0
    @volatile var idle = false
    /** Whether the owner was woken or started and has not found a task yet (owner-only). */
    var spinning = false
    @volatile var idleNode: AnyRef = null
    @volatile var poolIndex = 0
    @volatile var stolen = 0L

    private def lock(): Unit = while !Intrinsics.compareAndSet(locked, false, true) do Thread.onSpinWait()
    private def unlock(): Unit = locked = false

    def push(t: ForkJoinTask[?]): Unit =
      lock()
      try
        if top - base == tasks.length then grow()
        tasks(top & (tasks.length - 1)) = t
        top += 1
        size = top - base
      finally unlock()

    private def grow(): Unit =
      val bigger = new Array[ForkJoinTask[?]](tasks.length * 2)
      var i = base
      while i < top do
        bigger(i & (bigger.length - 1)) = tasks(i & (tasks.length - 1))
        i += 1
      tasks = bigger

    /** The newest task (LIFO), or the oldest in FIFO (async) mode. */
    def nextLocalTask(fifo: Boolean): ForkJoinTask[?] = if fifo then poll() else pop()

    def pop(): ForkJoinTask[?] =
      if size == 0 then null
      else
        lock()
        try
          if top == base then null
          else
            top -= 1
            val i = top & (tasks.length - 1)
            val t = tasks(i)
            tasks(i) = null
            size = top - base
            t
        finally unlock()

    def poll(): ForkJoinTask[?] =
      if size == 0 then null
      else
        lock()
        try
          if top == base then null
          else
            val i = base & (tasks.length - 1)
            val t = tasks(i)
            tasks(i) = null
            base += 1
            size = top - base
            t
        finally unlock()

    def peek(fifo: Boolean): ForkJoinTask[?] =
      lock()
      try
        if top == base then null
        else if fifo then tasks(base & (tasks.length - 1))
        else tasks((top - 1) & (tasks.length - 1))
      finally unlock()

    /** Removes `t` if it is the newest task. */
    def tryUnpush(t: ForkJoinTask[?]): Boolean =
      size > 0 && {
        lock()
        try
          val i = (top - 1) & (tasks.length - 1)
          if top != base && (tasks(i) eq t) then
            tasks(i) = null
            top -= 1
            size = top - base
            true
          else false
        finally unlock()
      }

    def runTask(t: ForkJoinTask[?]): Unit = t.doExec()

    def getPoolIndex(): Int = poolIndex
    def queueSize(): Int = size
