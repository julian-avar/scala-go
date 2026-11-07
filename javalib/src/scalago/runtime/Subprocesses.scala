package scalago.runtime

/** The native side of java.lang.ProcessBuilder, Process and ProcessHandle (see
 *  runtime/rt/subprocess.go), on Go's os/exec. The parent's ends of a process's pipes are
 *  handles of [[OS]].
 */
object Subprocesses:
  private def native: Nothing = throw new UnsupportedOperationException("scala-go native")

  // Redirect kinds.
  final val Pipe = 0
  final val Inherit = 1
  final val Read = 2
  final val Write = 3
  final val Append = 4

  /** Starts `command` in `dir` (null: the current directory), with `env` (alternating names and
   *  values; null: this process's environment). `redirects` are the kinds of standard input,
   *  output and error, and `paths` their files. Returns the process or an error, which
   *  [[OS.isError]] recognizes.
   */
  def start(command: Array[String], dir: String, env: Array[String], redirects: Array[Int], paths: Array[String],
      mergeErrors: Boolean): Object = native
  /** "Exec failed, error: 2 (No such file or directory) ", or for redirects "<path> (<reason>)". */
  def errorMessage(error: Object): String = native

  /** The handles of the parent's ends of the pipes (null if not a pipe). */
  def stdin(process: Object): Object = native
  def stdout(process: Object): Object = native
  def stderr(process: Object): Object = native

  def pid(process: Object): Long = native
  /** Waits until the process exits (at most `timeoutNanos` unless negative): whether it has.
   *  Throws InterruptedException if the thread is interrupted.
   */
  def waitFor(process: Object, timeoutNanos: Long): Boolean = native
  /** The exit status of a process that has exited. */
  def exitCode(process: Object): Int = native
  /** Asks the process to end (SIGTERM), or kills it (SIGKILL). */
  def destroy(process: Object, force: Boolean): Unit = native

  def currentPid(): Long = native
  def parentPid(): Long = native
