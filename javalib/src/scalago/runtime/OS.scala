package scalago.runtime

/** The native side of java.io and java.nio.file (see runtime/rt/files.go): a small,
 *  byte-oriented layer over Go's `os` package.
 *
 *  A handle is an open file (or standard input, output or error). Reads and writes on handles
 *  throw `java.io.IOException`. Operations on paths return their result, or an error object that
 *  [[isError]] recognizes; [[errorKind]] and [[errorReason]] describe it, and the Java APIs turn
 *  it into the exception their contract names (`FileNotFoundException`, `NoSuchFileException`, ...).
 */
object OS:
  private def native: Nothing = throw new UnsupportedOperationException("scala-go native")

  // Open modes.
  final val Read = 1
  final val Write = 2
  final val Append = 4
  final val Create = 8
  final val CreateNew = 16
  final val Truncate = 32
  /** Fail with "Is a directory" when the path is a directory (java.io's file streams). */
  final val RejectDirectory = 64

  // File types (the first element of `stat`'s result).
  final val RegularFile = 1
  final val Directory = 2
  final val SymbolicLink = 3
  final val OtherFile = 4

  // Error kinds.
  final val OtherError = 0
  final val NoSuchFile = 1
  final val AlreadyExists = 2
  final val NotEmpty = 3
  final val AccessDenied = 4
  final val NotDirectory = 5
  final val IsDirectory = 6
  final val Loop = 7

  def stdin(): Object = native
  def stdout(): Object = native
  def stderr(): Object = native

  /** A handle, or an error. */
  def open(path: String, mode: Int, permissions: Int): Object = native
  /** The number of bytes read, or -1 at the end of the input. */
  def read(handle: Object, b: Array[Byte], off: Int, len: Int): Int = native
  def write(handle: Object, b: Array[Byte], off: Int, len: Int): Unit = native
  /** Moves the position (`whence`: 0 from the start, 1 from the current position, 2 from the
   *  end) and returns the new one.
   */
  def seek(handle: Object, offset: Long, whence: Int): Long = native
  def available(handle: Object): Int = native
  def size(handle: Object): Long = native
  def truncate(handle: Object, size: Long): Unit = native
  def sync(handle: Object): Unit = native
  def close(handle: Object): Unit = native

  /** `Array[Long](type, size, last modified (ms), permission bits)`, or an error. */
  def stat(path: String, followLinks: Boolean): Object = native
  /** Whether the process may read (4), write (2) or execute (1) the file, as access(2). */
  def access(path: String, mode: Int): Boolean = native
  /** null, or an error. */
  def mkdir(path: String, permissions: Int): Object = native
  /** Deletes a file or an empty directory: null, or an error. */
  def remove(path: String): Object = native
  /** Renames, replacing a file at the target: null, or an error. */
  def rename(from: String, to: String): Object = native
  /** The entries' names (`Array[String]`), or an error. */
  def readDir(path: String): Object = native
  /** Creates a new file (or directory) named prefix + random digits + suffix in `dir`; its path,
   *  or an error.
   */
  def createTemp(dir: String, prefix: String, suffix: String, directory: Boolean): Object = native
  def chmod(path: String, permissions: Int): Object = native
  def setModified(path: String, millis: Long): Object = native
  def symlink(target: String, link: String): Object = native
  /** The link's target, or an error. */
  def readLink(path: String): Object = native
  /** The absolute path with symbolic links resolved, or an error. */
  def realPath(path: String): Object = native
  def workingDir(): String = native

  def isError(result: Object): Boolean = native
  def errorKind(error: Object): Int = native
  /** The C library's description ("No such file or directory"), as in the JDK's messages. */
  def errorReason(error: Object): String = native
