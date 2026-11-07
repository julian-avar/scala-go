package java.io

import scala.annotation.static

import scalago.runtime.OS

/** An open file: a handle of the native layer (`scalago.runtime.OS`), or null once closed.
 *  `out` and `err` write through the runtime's console buffer, so they stay ordered with
 *  System.out and System.err.
 */
final class FileDescriptor private[java] (private[java] var handle: Object):
  def this() = this(null)

  def valid(): Boolean = handle != null

  /** The file's path, when known: for the locks of its channel. */
  private[java] var path: String = null

  def sync(): Unit =
    if handle == null then throw new SyncFailedException("sync failed")
    OS.sync(handle)

  /** The handle, or an IOException if the descriptor was closed. */
  private[java] def openHandle(): Object =
    val h = handle
    if h == null then throw new IOException("Stream Closed")
    h

  private[java] def close(): Unit =
    val h = handle
    if h != null then
      handle = null
      OS.close(h)

object FileDescriptor:
  @static val in: FileDescriptor = new FileDescriptor(OS.stdin())
  @static val out: FileDescriptor = new FileDescriptor(OS.stdout())
  @static val err: FileDescriptor = new FileDescriptor(OS.stderr())

  /** Opens a file for java.io's streams: failures are FileNotFoundExceptions with the JDK's
   *  message, `<path> (<reason>)`.
   */
  private[java] def open(file: File, mode: Int): FileDescriptor =
    val path = file.getPath()
    val result = if path.isEmpty() then null else OS.open(path, mode | OS.RejectDirectory, 0x1b6 /* 0666 */)
    if result == null then throw new FileNotFoundException(path + " (No such file or directory)")
    if OS.isError(result) then throw new FileNotFoundException(path + " (" + OS.errorReason(result) + ")")
    val fd = new FileDescriptor(result)
    fd.path = path
    fd
