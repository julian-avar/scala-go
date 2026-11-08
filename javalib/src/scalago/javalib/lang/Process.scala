package scalago.javalib.lang

import java.io.{
  BufferedInputStream, BufferedOutputStream, FileDescriptor, FileInputStream, FileOutputStream, IOException, InputStream,
  OutputStream
}
import java.util.Optional
import java.util.concurrent.TimeUnit

import scalago.runtime.Subprocesses

/** java.lang.Process. `onExit` (a CompletableFuture) is not implemented. */
abstract class Process:
  def getOutputStream(): OutputStream
  def getInputStream(): InputStream
  def getErrorStream(): InputStream
  def waitFor(): Int
  def exitValue(): Int
  def destroy(): Unit

  def waitFor(timeout: scala.Long, unit: TimeUnit): scala.Boolean =
    val deadline = java.lang.System.nanoTime() + unit.toNanos(timeout)
    var remaining = unit.toNanos(timeout)
    while
      try
        exitValue()
        return true
      catch case _: java.lang.IllegalThreadStateException => ()
      remaining > 0
    do
      Thread.sleep(Math.max(1L, Math.min(TimeUnit.NANOSECONDS.toMillis(remaining) + 1, 100L)))
      remaining = deadline - java.lang.System.nanoTime()
    false

  def destroyForcibly(): Process =
    destroy()
    this

  def supportsNormalTermination(): scala.Boolean = throw new java.lang.UnsupportedOperationException(
    getClass().getName() + ".supportsNormalTermination() not supported")

  def isAlive(): scala.Boolean =
    try
      exitValue()
      false
    catch case _: java.lang.IllegalThreadStateException => true

  def pid(): scala.Long = throw new java.lang.UnsupportedOperationException(getClass().getName() + ".pid() not supported")

  def toHandle(): ProcessHandle =
    throw new java.lang.UnsupportedOperationException(getClass().getName() + ".toHandle() not supported")

  def info(): ProcessHandle.Info = toHandle().info()

  def inputReader(): java.io.BufferedReader = inputReader(java.nio.charset.Charset.defaultCharset())
  def inputReader(charset: java.nio.charset.Charset): java.io.BufferedReader =
    new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(), charset))
  def errorReader(): java.io.BufferedReader = errorReader(java.nio.charset.Charset.defaultCharset())
  def errorReader(charset: java.nio.charset.Charset): java.io.BufferedReader =
    new java.io.BufferedReader(new java.io.InputStreamReader(getErrorStream(), charset))
  def outputWriter(): java.io.BufferedWriter = outputWriter(java.nio.charset.Charset.defaultCharset())
  def outputWriter(charset: java.nio.charset.Charset): java.io.BufferedWriter =
    new java.io.BufferedWriter(new java.io.OutputStreamWriter(getOutputStream(), charset))

/** A started process (`scalago.runtime.Subprocesses`): its pipes are buffered streams, as the
 *  JDK's (what is written to its input reaches it when flushed or closed).
 */
private[lang] final class ProcessImpl(process: Object) extends Process:
  private def pipe(handle: Object): FileDescriptor = if handle == null then null else FileDescriptor.fromHandle(handle)

  private val stdin: OutputStream =
    val fd = pipe(Subprocesses.stdin(process))
    if fd == null then ProcessImpl.NullOutputStream else new BufferedOutputStream(new FileOutputStream(fd), 8192)

  private val stdout: InputStream = ProcessImpl.input(pipe(Subprocesses.stdout(process)))
  private val stderr: InputStream = ProcessImpl.input(pipe(Subprocesses.stderr(process)))

  def getOutputStream(): OutputStream = stdin
  def getInputStream(): InputStream = stdout
  def getErrorStream(): InputStream = stderr

  def waitFor(): Int =
    Subprocesses.waitFor(process, -1)
    Subprocesses.exitCode(process)

  override def waitFor(timeout: scala.Long, unit: TimeUnit): scala.Boolean =
    val nanos = unit.toNanos(timeout)
    Subprocesses.waitFor(process, if nanos < 0 then 0 else nanos)

  def exitValue(): Int =
    if !Subprocesses.waitFor(process, 0) then throw new java.lang.IllegalThreadStateException("process hasn't exited")
    Subprocesses.exitCode(process)

  override def isAlive(): scala.Boolean = !Subprocesses.waitFor(process, 0)

  def destroy(): Unit = kill(false)

  override def destroyForcibly(): Process =
    kill(true)
    this

  private def kill(force: scala.Boolean): Unit =
    Subprocesses.destroy(process, force)
    // As the JDK, close the pipes: blocked readers see the end of their stream.
    try stdin.close() catch case _: IOException => ()
    try stdout.close() catch case _: IOException => ()
    try stderr.close() catch case _: IOException => ()

  override def supportsNormalTermination(): scala.Boolean = true

  override def pid(): scala.Long = Subprocesses.pid(process)

  override def toHandle(): ProcessHandle = new ProcessHandleImpl(pid(), this)

  override def toString(): String =
    "Process[pid=" + pid() + ", exitValue=" + (if isAlive() then "\"not exited\"" else exitValue().toString) + "]"

private[lang] object ProcessImpl:
  def input(fd: FileDescriptor): InputStream =
    if fd == null then NullInputStream else new BufferedInputStream(new FileInputStream(fd), 8192)

  /** The streams of what is not a pipe, as the JDK's `ProcessBuilder.NullInputStream`. */
  object NullInputStream extends InputStream:
    override def read(): Int = -1
    override def available(): Int = 0

  object NullOutputStream extends OutputStream:
    override def write(b: Int): Unit = throw new IOException("Stream closed")

/** java.lang.ProcessHandle. `onExit` is not implemented, and neither are `children`,
 *  `descendants`, `allProcesses` and the details of `info` (its values are empty).
 */
trait ProcessHandle extends Comparable[ProcessHandle]:
  def pid(): scala.Long
  def parent(): Optional[ProcessHandle]
  def info(): ProcessHandle.Info
  def supportsNormalTermination(): scala.Boolean
  def destroy(): scala.Boolean
  def destroyForcibly(): scala.Boolean
  def isAlive(): scala.Boolean
  def compareTo(other: ProcessHandle): Int = java.lang.Long.compare(pid(), other.pid())

object ProcessHandle:
  def current(): ProcessHandle = ProcessHandleImpl.current

  /** The handle of this process, or of a child it started (other processes are not known). */
  def of(pid: scala.Long): Optional[ProcessHandle] =
    if pid == ProcessHandleImpl.current.pid() then Optional.of(ProcessHandleImpl.current) else Optional.empty()

  trait Info:
    def command(): Optional[String]
    def commandLine(): Optional[String]
    def arguments(): Optional[Array[String]]
    def startInstant(): Optional[java.time.Instant]
    def totalCpuDuration(): Optional[java.time.Duration]
    def user(): Optional[String]

private[lang] final class ProcessHandleImpl(id: scala.Long, process: Process) extends ProcessHandle:
  def pid(): scala.Long = id

  def parent(): Optional[ProcessHandle] =
    if process == null then Optional.of(new ProcessHandleImpl(Subprocesses.parentPid(), null))
    else Optional.of(ProcessHandleImpl.current)

  def info(): ProcessHandle.Info = ProcessHandleImpl.NoInfo

  def supportsNormalTermination(): scala.Boolean = true

  private def checkNotCurrent(): Unit =
    if id == ProcessHandleImpl.current.pid() then
      throw new java.lang.IllegalStateException("destroy of current process not allowed")

  def destroy(): scala.Boolean =
    checkNotCurrent()
    if process == null then false
    else
      process.destroy()
      true

  def destroyForcibly(): scala.Boolean =
    checkNotCurrent()
    if process == null then false
    else
      process.destroyForcibly()
      true

  def isAlive(): scala.Boolean = if process == null then id == ProcessHandleImpl.current.pid() else process.isAlive()

  override def equals(that: Any): Boolean = that match
    case that: ProcessHandleImpl => that.pid() == id
    case _                       => false
  override def hashCode(): Int = java.lang.Long.hashCode(id)
  override def toString(): String = java.lang.Long.toString(id)

private[lang] object ProcessHandleImpl:
  val current: ProcessHandle = new ProcessHandleImpl(Subprocesses.currentPid(), null)

  object NoInfo extends ProcessHandle.Info:
    def command(): Optional[String] = Optional.empty()
    def commandLine(): Optional[String] = Optional.empty()
    def arguments(): Optional[Array[String]] = Optional.empty()
    def startInstant(): Optional[java.time.Instant] = Optional.empty()
    def totalCpuDuration(): Optional[java.time.Duration] = Optional.empty()
    def user(): Optional[String] = Optional.empty()
    override def toString(): String = "[]"
