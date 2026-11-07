package java.io

import scala.annotation.static

import java.nio.file.Path

import scalago.runtime.OS

/** java.io.File, with Unix path syntax (`/` separators), on the native layer
 *  (`scalago.runtime.OS`). Paths are normalized as by the JDK: no repeated or trailing slashes.
 */
class File private (path: String, normalized: scala.Boolean) extends Object with Serializable with Comparable[File]:
  def this(pathname: String) = this(File.normalize(pathname), true)
  def this(parent: String, child: String) = this(File.join(parent, child), true)
  def this(parent: File, child: String) = this(File.join(if parent == null then null else parent.getPath(), child), true)

  def getPath(): String = path
  override def toString(): String = path
  def getName(): String = path.substring(path.lastIndexOf('/') + 1)

  def getParent(): String =
    val i = path.lastIndexOf('/')
    if i < 0 then null
    else if i == 0 then (if path.length() > 1 then "/" else null)
    else path.substring(0, i)

  def getParentFile(): File =
    val p = getParent()
    if p == null then null else new File(p, true)

  def isAbsolute(): scala.Boolean = path.startsWith("/")

  def getAbsolutePath(): String =
    if isAbsolute() then path else File.resolve(System.getProperty("user.dir"), path)

  def getAbsoluteFile(): File = new File(getAbsolutePath(), true)

  /** The absolute path without `.` and `..`, with the symbolic links of its existing part
   *  resolved.
   */
  def getCanonicalPath(): String = File.canonicalize(File.removeDots(getAbsolutePath()))
  def getCanonicalFile(): File = new File(getCanonicalPath(), true)

  def toPath(): Path = Path.of(path, new Array[String](0))

  // ---- Attributes ----

  /** stat's result, or null if the file does not exist (or cannot be examined). */
  private def stat(): Array[scala.Long] =
    if path.isEmpty() then null
    else
      val r = OS.stat(path, true)
      if OS.isError(r) then null else r.asInstanceOf[Array[scala.Long]]

  def exists(): scala.Boolean = stat() != null
  def isFile(): scala.Boolean =
    val s = stat()
    s != null && s(0) == OS.RegularFile
  def isDirectory(): scala.Boolean =
    val s = stat()
    s != null && s(0) == OS.Directory
  def isHidden(): scala.Boolean = getName().startsWith(".")
  def length(): scala.Long =
    val s = stat()
    if s == null then 0L else s(1)
  def lastModified(): scala.Long =
    val s = stat()
    if s == null then 0L else s(2)

  def canRead(): scala.Boolean = !path.isEmpty() && OS.access(path, 4)
  def canWrite(): scala.Boolean = !path.isEmpty() && OS.access(path, 2)
  def canExecute(): scala.Boolean = !path.isEmpty() && OS.access(path, 1)

  def setLastModified(time: scala.Long): scala.Boolean =
    if time < 0 then throw new IllegalArgumentException("Negative time")
    !path.isEmpty() && !OS.isError(OS.setModified(path, time))

  private def setPermission(bits: Int, enable: scala.Boolean, ownerOnly: scala.Boolean): scala.Boolean =
    val s = stat()
    if s == null then false
    else
      val mask = if ownerOnly then bits & 0x1c0 /* 0700 */ else bits
      val mode = s(3).toInt
      !OS.isError(OS.chmod(path, if enable then mode | mask else mode & ~mask))

  def setReadOnly(): scala.Boolean = setPermission(0x92 /* 0222 */, false, false)
  def setWritable(writable: scala.Boolean, ownerOnly: scala.Boolean): scala.Boolean = setPermission(0x92, writable, ownerOnly)
  def setWritable(writable: scala.Boolean): scala.Boolean = setWritable(writable, true)
  def setReadable(readable: scala.Boolean, ownerOnly: scala.Boolean): scala.Boolean = setPermission(0x124 /* 0444 */, readable, ownerOnly)
  def setReadable(readable: scala.Boolean): scala.Boolean = setReadable(readable, true)
  def setExecutable(executable: scala.Boolean, ownerOnly: scala.Boolean): scala.Boolean = setPermission(0x49 /* 0111 */, executable, ownerOnly)
  def setExecutable(executable: scala.Boolean): scala.Boolean = setExecutable(executable, true)

  // ---- Operations ----

  def createNewFile(): scala.Boolean =
    if path.isEmpty() then throw new IOException("No such file or directory")
    val r = OS.open(path, OS.Write | OS.CreateNew, 0x1b6 /* 0666 */)
    if !OS.isError(r) then
      OS.close(r)
      true
    else if OS.errorKind(r) == OS.AlreadyExists then false
    else throw new IOException(OS.errorReason(r))

  def delete(): scala.Boolean = !path.isEmpty() && !OS.isError(OS.remove(path))

  def deleteOnExit(): Unit = File.DeleteOnExit.add(getAbsolutePath())

  def renameTo(dest: File): scala.Boolean =
    if dest == null then throw new NullPointerException()
    !path.isEmpty() && !dest.getPath().isEmpty() && !OS.isError(OS.rename(path, dest.getPath()))

  def mkdir(): scala.Boolean = !path.isEmpty() && !OS.isError(OS.mkdir(path, 0x1ff /* 0777 */))

  def mkdirs(): scala.Boolean =
    if exists() then false
    else if mkdir() then true
    else
      val canonical =
        try getCanonicalFile()
        catch case _: IOException => return false
      val parent = canonical.getParentFile()
      parent != null && (parent.mkdirs() || parent.exists()) && canonical.mkdir()

  def list(): Array[String] =
    if path.isEmpty() then null
    else
      val r = OS.readDir(path)
      if OS.isError(r) then null else r.asInstanceOf[Array[String]]

  def list(filter: FilenameFilter): Array[String] =
    val names = list()
    if names == null || filter == null then names else names.filter(filter.accept(this, _))

  def listFiles(): Array[File] =
    val names = list()
    if names == null then null else names.map(new File(this, _))

  def listFiles(filter: FilenameFilter): Array[File] =
    val names = list(filter)
    if names == null then null else names.map(new File(this, _))

  def listFiles(filter: FileFilter): Array[File] =
    val files = listFiles()
    if files == null || filter == null then files else files.filter(filter.accept(_))

  // ---- Identity ----

  def compareTo(other: File): Int = path.compareTo(other.getPath())
  override def equals(obj: Any): scala.Boolean = obj match
    case other: File => path == other.getPath()
    case _           => false
  override def hashCode(): Int = path.hashCode() ^ 1234321

object File:
  @static val separatorChar: Char = '/'
  @static val separator: String = "/"
  @static val pathSeparatorChar: Char = ':'
  @static val pathSeparator: String = ":"

  @static def listRoots(): Array[File] = Array(new File("/"))

  @static def createTempFile(prefix: String, suffix: String, directory: File): File =
    if prefix.length() < 3 then
      throw new IllegalArgumentException("Prefix string \"" + prefix + "\" too short: length must be at least 3")
    val dir = if directory != null then directory.getPath() else System.getProperty("java.io.tmpdir")
    val r = OS.createTemp(dir, prefix, if suffix == null then ".tmp" else suffix, false)
    if OS.isError(r) then throw new IOException(OS.errorReason(r))
    new File(r.asInstanceOf[String])

  @static def createTempFile(prefix: String, suffix: String): File = createTempFile(prefix, suffix, null)

  /** Removes repeated and trailing slashes, as the JDK's UnixFileSystem.normalize. */
  private def normalize(p: String): String =
    if p == null then throw new NullPointerException()
    val n = p.length()
    var clean = true
    var i = 0
    while i < n && clean do
      if p.charAt(i) == '/' && (i == n - 1 || p.charAt(i + 1) == '/') && n > 1 then clean = false
      i += 1
    if clean then p
    else
      val sb = new java.lang.StringBuilder(n)
      var prev = 0.toChar
      i = 0
      while i < n do
        val c = p.charAt(i)
        if !(c == '/' && prev == '/') then sb.append(c)
        prev = c
        i += 1
      if sb.length() > 1 && sb.charAt(sb.length() - 1) == '/' then sb.setLength(sb.length() - 1)
      sb.toString()

  private def resolve(parent: String, child: String): String =
    if child.isEmpty() then parent
    else if child.startsWith("/") then (if parent == "/" then child else parent + child)
    else if parent == "/" then parent + child
    else parent + "/" + child

  private def join(parent: String, child: String): String =
    if child == null then throw new NullPointerException()
    if parent == null then normalize(child)
    else if parent.isEmpty() then resolve("/", normalize(child))
    else resolve(normalize(parent), normalize(child))

  /** Removes `.` and `..` from an absolute path. */
  private def removeDots(p: String): String =
    val parts = p.split("/")
    val out = new Array[String](parts.length)
    var n = 0
    var i = 0
    while i < parts.length do
      val part = parts(i)
      if part == ".." then (if n > 0 then n -= 1)
      else if !part.isEmpty() && part != "." then
        out(n) = part
        n += 1
      i += 1
    if n == 0 then "/"
    else
      val sb = new java.lang.StringBuilder()
      i = 0
      while i < n do
        sb.append('/').append(out(i))
        i += 1
      sb.toString()

  /** Resolves the symbolic links of the longest existing prefix of an absolute path. */
  private def canonicalize(p: String): String =
    val r = OS.realPath(p)
    if !OS.isError(r) then r.asInstanceOf[String]
    else
      val i = p.lastIndexOf('/')
      if i <= 0 then p
      else
        val parent = canonicalize(p.substring(0, i))
        resolve(parent, p.substring(i + 1))

  /** Files to delete at exit (File.deleteOnExit), most recently registered first. */
  private object DeleteOnExit:
    private var paths: List[String] = Nil
    private var hooked = false

    def add(path: String): Unit = synchronized {
      if !hooked then
        hooked = true
        Runtime.getRuntime().addShutdownHook(new Thread(() => deleteAll()))
      paths = path :: paths
    }

    private def deleteAll(): Unit =
      val toDelete = synchronized(paths)
      toDelete.foreach(p => OS.remove(p))
