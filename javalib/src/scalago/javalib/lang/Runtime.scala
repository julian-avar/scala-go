package scalago.javalib.lang

import scala.annotation.static

import scalago.runtime.Process

/** java.lang.Runtime: processors, memory, exit and shutdown hooks. Shutdown hooks run when the
 *  program ends normally (after the last non-daemon thread) or calls `exit`.
 */
class Runtime private ():
  def availableProcessors(): Int = Process.availableProcessors()

  def exit(status: Int): Unit = Process.exit(status)
  def halt(status: Int): Unit = Process.halt(status)

  def addShutdownHook(hook: Thread): Unit =
    if hook == null then throw new java.lang.NullPointerException()
    if hook.wasStarted then throw new java.lang.IllegalArgumentException("Hook already running")
    Process.addShutdownHook(hook) match
      case 1 => throw new java.lang.IllegalArgumentException("Hook previously registered")
      case 2 => throw new java.lang.IllegalStateException("Shutdown in progress")
      case _ => ()

  def removeShutdownHook(hook: Thread): scala.Boolean =
    if hook == null then throw new java.lang.NullPointerException()
    Process.removeShutdownHook(hook) match
      case 0 => true
      case 2 => throw new java.lang.IllegalStateException("Shutdown in progress")
      case _ => false

  /** The heap Go obtained from the system. */
  def totalMemory(): scala.Long = Process.totalMemory()
  /** The part of [[totalMemory]] not in use. */
  def freeMemory(): scala.Long = Process.freeMemory()
  /** No limit: as on the JVM without one, `Long.MaxValue`. */
  def maxMemory(): scala.Long = java.lang.Long.MAX_VALUE
  def gc(): Unit = Process.gc()

object Runtime:
  @static private val current: Runtime = new Runtime()

  @static def getRuntime(): Runtime = current
