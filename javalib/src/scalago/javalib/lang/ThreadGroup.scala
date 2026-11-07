package scalago.javalib.lang

/** java.lang.ThreadGroup: names, parents, maximum priorities and the uncaught-exception handler
 *  of last resort. Groups do not track their threads.
 */
class ThreadGroup private (parent: ThreadGroup, name: String, private var maxPriority: Int, private var daemon: scala.Boolean)
    extends Object with Thread.UncaughtExceptionHandler:
  def this(parent: ThreadGroup, name: String) =
    this(java.util.Objects.requireNonNull(parent), name, parent.getMaxPriority(), parent.isDaemon())
  def this(name: String) = this(Thread.currentThread().getThreadGroup(), name)

  def getName(): String = name
  def getParent(): ThreadGroup = parent
  def getMaxPriority(): Int = maxPriority
  def setMaxPriority(pri: Int): Unit =
    if pri >= Thread.MIN_PRIORITY && pri <= Thread.MAX_PRIORITY then
      maxPriority = if parent != null then math.min(pri, parent.getMaxPriority()) else pri
  @deprecated def isDaemon(): scala.Boolean = daemon
  @deprecated def setDaemon(on: scala.Boolean): Unit = daemon = on
  def parentOf(g: ThreadGroup): scala.Boolean =
    var current = g
    while current != null && (current ne this) do current = current.getParent()
    current != null
  @deprecated def checkAccess(): Unit = ()

  def uncaughtException(t: Thread, e: java.lang.Throwable): Unit =
    if parent != null then parent.uncaughtException(t, e)
    else
      val h = Thread.getDefaultUncaughtExceptionHandler()
      if h != null then h.uncaughtException(t, e)
      else java.lang.System.err.println("Exception in thread \"" + t.getName() + "\" " + e)

  override def toString(): String = getClass().getName() + "[name=" + name + ",maxpri=" + maxPriority + "]"

object ThreadGroup:
  private[lang] val system = new ThreadGroup(null, "system", Thread.MAX_PRIORITY, false)
  private[lang] val main = new ThreadGroup(system, "main", Thread.MAX_PRIORITY, false)
  private[lang] val virtualThreads = new ThreadGroup(system, "VirtualThreads", Thread.MAX_PRIORITY, true)
