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
    Process.addShutdownHook(hook.native) match
      case 1 => throw new java.lang.IllegalArgumentException("Hook previously registered")
      case 2 => throw new java.lang.IllegalStateException("Shutdown in progress")
      case _ => ()

  def removeShutdownHook(hook: Thread): scala.Boolean =
    if hook == null then throw new java.lang.NullPointerException()
    Process.removeShutdownHook(hook.native) match
      case 0 => true
      case 2 => throw new java.lang.IllegalStateException("Shutdown in progress")
      case _ => false

  def exec(command: String): scalago.javalib.lang.Process = exec(command, null, null)
  def exec(command: String, envp: Array[String]): scalago.javalib.lang.Process = exec(command, envp, null)

  /** Runs the command's words (split at whitespace). */
  def exec(command: String, envp: Array[String], dir: java.io.File): scalago.javalib.lang.Process =
    if command.isEmpty() then throw new java.lang.IllegalArgumentException("Empty command")
    val words = new java.util.StringTokenizer(command)
    val cmdarray = new Array[String](words.countTokens())
    for i <- cmdarray.indices do cmdarray(i) = words.nextToken()
    exec(cmdarray, envp, dir)

  def exec(cmdarray: Array[String]): scalago.javalib.lang.Process = exec(cmdarray, null, null)
  def exec(cmdarray: Array[String], envp: Array[String]): scalago.javalib.lang.Process = exec(cmdarray, envp, null)

  /** Runs a program with the environment `envp` (`name=value` strings; this process's if null). */
  def exec(cmdarray: Array[String], envp: Array[String], dir: java.io.File): scalago.javalib.lang.Process =
    val builder = new ProcessBuilder(cmdarray).directory(dir)
    if envp != null then
      val env = builder.environment()
      env.clear()
      for entry <- envp do
        val eq = entry.indexOf('=', 1)
        if eq >= 0 then env.put(entry.substring(0, eq), entry.substring(eq + 1))
    builder.start()

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
