package java.nio.file

import scala.annotation.static

import java.io.{
  BufferedReader, BufferedWriter, Closeable, FileDescriptor, FileInputStream, FileOutputStream, IOException,
  InputStream, InputStreamReader, OutputStream, OutputStreamWriter
}
import java.nio.charset.{Charset, CodingErrorAction, StandardCharsets}
import java.nio.file.attribute.{BasicFileAttributes, FileAttribute, FileTime, PosixFilePermission, PosixFilePermissions}

import scalago.runtime.OS

/** java.nio.file.Files on the native layer (`scalago.runtime.OS`), with the JDK's semantics and
 *  exceptions for the default (Unix) file system. File attributes given at creation are ignored.
 */
final class Files private ()

object Files:
  // ---- Errors ----

  import FileSystemErrors.translate

  private def check(result: Object, file: Path): Object =
    if OS.isError(result) then throw translate(result, file.toString, null)
    result

  private def noFollow(options: Array[? <: Object]): scala.Boolean = options.exists(_ eq LinkOption.NOFOLLOW_LINKS)

  // ---- Attributes ----

  /** stat's result, or null if the file cannot be examined. */
  private def statOrNull(path: Path, followLinks: scala.Boolean): Array[Long] =
    val r = OS.stat(path.toString, followLinks)
    if OS.isError(r) then null else r.asInstanceOf[Array[Long]]

  private def stat(path: Path, followLinks: scala.Boolean): Array[Long] =
    check(OS.stat(path.toString, followLinks), path).asInstanceOf[Array[Long]]

  @static def exists(path: Path, options: Array[LinkOption]): scala.Boolean = statOrNull(path, !noFollow(options)) != null
  @static def notExists(path: Path, options: Array[LinkOption]): scala.Boolean =
    val r = OS.stat(path.toString, !noFollow(options))
    OS.isError(r) && OS.errorKind(r) == OS.NoSuchFile

  private def isType(path: Path, kind: Int, followLinks: scala.Boolean): scala.Boolean =
    val s = statOrNull(path, followLinks)
    s != null && s(0) == kind

  @static def isDirectory(path: Path, options: Array[LinkOption]): scala.Boolean = isType(path, OS.Directory, !noFollow(options))
  @static def isRegularFile(path: Path, options: Array[LinkOption]): scala.Boolean = isType(path, OS.RegularFile, !noFollow(options))
  @static def isSymbolicLink(path: Path): scala.Boolean = isType(path, OS.SymbolicLink, false)
  @static def isReadable(path: Path): scala.Boolean = OS.access(path.toString, 4)
  @static def isWritable(path: Path): scala.Boolean = OS.access(path.toString, 2)
  @static def isExecutable(path: Path): scala.Boolean = OS.access(path.toString, 1)
  @static def isHidden(path: Path): scala.Boolean =
    val name = path.getFileName()
    name != null && name.toString.startsWith(".")

  @static def isSameFile(path: Path, path2: Path): scala.Boolean =
    path == path2 || path.toRealPath(new Array[LinkOption](0)) == path2.toRealPath(new Array[LinkOption](0))

  @static def size(path: Path): Long = stat(path, true)(1)

  @static def getLastModifiedTime(path: Path, options: Array[LinkOption]): FileTime =
    FileTime.fromMillis(stat(path, !noFollow(options))(2))

  @static def setLastModifiedTime(path: Path, time: FileTime): Path =
    check(OS.setModified(path.toString, time.toMillis()), path)
    path

  @static def getPosixFilePermissions(path: Path, options: Array[LinkOption]): java.util.Set[PosixFilePermission] =
    PosixFilePermissions.fromBits(stat(path, !noFollow(options))(3).toInt)

  @static def setPosixFilePermissions(path: Path, perms: java.util.Set[PosixFilePermission]): Path =
    check(OS.chmod(path.toString, PosixFilePermissions.toBits(perms)), path)
    path

  @static def readAttributes[A <: BasicFileAttributes](path: Path, kind: Class[A], options: Array[LinkOption]): A =
    if kind != classOf[BasicFileAttributes] then throw new UnsupportedOperationException()
    new Attributes(stat(path, !noFollow(options))).asInstanceOf[A]

  private final class Attributes(s: Array[Long]) extends BasicFileAttributes:
    def lastModifiedTime(): FileTime = FileTime.fromMillis(s(2))
    def lastAccessTime(): FileTime = lastModifiedTime()
    def creationTime(): FileTime = lastModifiedTime()
    def isRegularFile(): scala.Boolean = s(0) == OS.RegularFile
    def isDirectory(): scala.Boolean = s(0) == OS.Directory
    def isSymbolicLink(): scala.Boolean = s(0) == OS.SymbolicLink
    def isOther(): scala.Boolean = s(0) == OS.OtherFile
    def size(): Long = s(1)
    def fileKey(): Object = null

  // ---- Creating and deleting ----

  @static def createFile(path: Path, attrs: Array[FileAttribute[?]]): Path =
    OS.close(check(OS.open(path.toString, OS.Write | OS.CreateNew, 0x1b6 /* 0666 */), path))
    path

  @static def createDirectory(dir: Path, attrs: Array[FileAttribute[?]]): Path =
    check(OS.mkdir(dir.toString, 0x1ff /* 0777 */), dir)
    dir

  @static def createDirectories(dir: Path, attrs: Array[FileAttribute[?]]): Path =
    val s = statOrNull(dir, true)
    if s != null then
      if s(0) != OS.Directory then throw new FileAlreadyExistsException(dir.toString)
    else
      // Create the missing ancestors, from the closest existing one down.
      val parent = dir.toAbsolutePath().getParent()
      if parent != null && statOrNull(parent, true) == null then createDirectories(parent, attrs)
      val r = OS.mkdir(dir.toString, 0x1ff)
      if OS.isError(r) && !(OS.errorKind(r) == OS.AlreadyExists && isDirectory(dir, new Array[LinkOption](0))) then
        throw translate(r, dir.toString, null)
    dir

  private def tempDir(dir: Path): String =
    if dir != null then dir.toString else System.getProperty("java.io.tmpdir")

  private def createTemp(dir: Path, prefix: String, suffix: String, directory: scala.Boolean): Path =
    val r = OS.createTemp(tempDir(dir), if prefix == null then "" else prefix, if suffix == null then "" else suffix, directory)
    if OS.isError(r) then throw translate(r, tempDir(dir), null)
    Path.of(r.asInstanceOf[String], new Array[String](0))

  @static def createTempFile(dir: Path, prefix: String, suffix: String, attrs: Array[FileAttribute[?]]): Path =
    createTemp(dir, prefix, if suffix == null then ".tmp" else suffix, false)

  @static def createTempFile(prefix: String, suffix: String, attrs: Array[FileAttribute[?]]): Path =
    createTempFile(null, prefix, suffix, attrs)

  @static def createTempDirectory(dir: Path, prefix: String, attrs: Array[FileAttribute[?]]): Path =
    createTemp(dir, prefix, "", true)

  @static def createTempDirectory(prefix: String, attrs: Array[FileAttribute[?]]): Path =
    createTempDirectory(null, prefix, attrs)

  @static def createSymbolicLink(link: Path, target: Path, attrs: Array[FileAttribute[?]]): Path =
    val r = OS.symlink(target.toString, link.toString)
    if OS.isError(r) then throw translate(r, link.toString, null)
    link

  @static def readSymbolicLink(link: Path): Path =
    val r = OS.readLink(link.toString)
    if OS.isError(r) then
      if OS.errorKind(r) == OS.OtherError && statOrNull(link, false) != null then
        throw new NotLinkException(link.toString)
      throw translate(r, link.toString, null)
    Path.of(r.asInstanceOf[String], new Array[String](0))

  @static def delete(path: Path): Unit =
    val r = OS.remove(path.toString)
    if OS.isError(r) then
      if OS.errorKind(r) == OS.NotEmpty then throw new DirectoryNotEmptyException(path.toString)
      throw translate(r, path.toString, null)

  @static def deleteIfExists(path: Path): scala.Boolean =
    val r = OS.remove(path.toString)
    if !OS.isError(r) then true
    else if OS.errorKind(r) == OS.NoSuchFile then false
    else if OS.errorKind(r) == OS.NotEmpty then throw new DirectoryNotEmptyException(path.toString)
    else throw translate(r, path.toString, null)

  // ---- Copying and moving ----

  /** Removes `target` for REPLACE_EXISTING; fails if it exists otherwise. */
  private def prepareTarget(target: Path, replace: scala.Boolean): Unit =
    if statOrNull(target, false) != null then
      if !replace then throw new FileAlreadyExistsException(target.toString)
      delete(target)

  @static def copy(source: Path, target: Path, options: Array[CopyOption]): Path =
    val replace = options.contains(StandardCopyOption.REPLACE_EXISTING)
    val follow = !noFollow(options)
    val s = stat(source, follow)
    if statOrNull(target, false) != null && isSameFile(source, target) then return target
    prepareTarget(target, replace)
    s(0) match
      case OS.Directory    => createDirectory(target, new Array[FileAttribute[?]](0))
      case OS.SymbolicLink => createSymbolicLink(target, readSymbolicLink(source), new Array[FileAttribute[?]](0))
      case _ =>
        val in = newInputStream(source, new Array[OpenOption](0))
        try
          val out = newOutputStream(target, Array(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
          try in.transferTo(out)
          finally out.close()
        finally in.close()
    if options.contains(StandardCopyOption.COPY_ATTRIBUTES) && s(0) != OS.SymbolicLink then
      OS.chmod(target.toString, s(3).toInt)
      OS.setModified(target.toString, s(2))
    target

  @static def copy(in: InputStream, target: Path, options: Array[CopyOption]): Long =
    if in == null then throw new NullPointerException()
    prepareTarget(target, options.contains(StandardCopyOption.REPLACE_EXISTING))
    val out = newOutputStream(target, Array(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
    try in.transferTo(out)
    finally out.close()

  @static def copy(source: Path, out: OutputStream): Long =
    if out == null then throw new NullPointerException()
    val in = newInputStream(source, new Array[OpenOption](0))
    try in.transferTo(out)
    finally in.close()

  @static def move(source: Path, target: Path, options: Array[CopyOption]): Path =
    val atomic = options.contains(StandardCopyOption.ATOMIC_MOVE)
    stat(source, false)
    if statOrNull(target, false) != null && isSameFile(source, target) then return target
    if !atomic then prepareTarget(target, options.contains(StandardCopyOption.REPLACE_EXISTING))
    val r = OS.rename(source.toString, target.toString)
    if OS.isError(r) then
      if OS.errorKind(r) == OS.OtherError && !atomic && !isDirectory(source, Array(LinkOption.NOFOLLOW_LINKS)) then
        // Another file system: copy, then delete.
        copy(source, target, Array[CopyOption](StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS))
        delete(source)
      else if atomic && OS.errorKind(r) == OS.OtherError then
        throw new AtomicMoveNotSupportedException(source.toString, target.toString, OS.errorReason(r))
      else if OS.errorKind(r) == OS.NotEmpty then throw new DirectoryNotEmptyException(target.toString)
      else throw translate(r, source.toString, target.toString)
    target

  // ---- Streams ----

  /** Opens a file; `mode` is a combination of OS's open modes. */
  private def open(path: Path, mode: Int): FileDescriptor =
    val r = OS.open(path.toString, mode, 0x1b6 /* 0666 */)
    if OS.isError(r) then
      if OS.errorKind(r) == OS.IsDirectory then throw new FileSystemException(path.toString, null, "Is a directory")
      throw translate(r, path.toString, null)
    new FileDescriptor(r)

  @static def newInputStream(path: Path, options: Array[OpenOption]): InputStream =
    for o <- options do
      if o == StandardOpenOption.APPEND || o == StandardOpenOption.WRITE then
        throw new UnsupportedOperationException("'" + o + "' not allowed")
    new FileInputStream(open(path, OS.Read))

  /** The open mode for writing with `options` (the JDK's default: CREATE, TRUNCATE_EXISTING). */
  private def writeMode(options: Array[OpenOption]): Int =
    if options.length == 0 then OS.Write | OS.Create | OS.Truncate
    else
      var mode = OS.Write
      for o <- options do
        if o == StandardOpenOption.READ then throw new IllegalArgumentException("READ not allowed")
        else if o == StandardOpenOption.APPEND then mode |= OS.Append
        else if o == StandardOpenOption.TRUNCATE_EXISTING then mode |= OS.Truncate
        else if o == StandardOpenOption.CREATE then mode |= OS.Create
        else if o == StandardOpenOption.CREATE_NEW then mode |= OS.CreateNew
        else if o == null then throw new NullPointerException()
      if (mode & OS.Append) != 0 && (mode & OS.Truncate) != 0 then
        throw new IllegalArgumentException("APPEND + TRUNCATE_EXISTING not allowed")
      if (mode & OS.Append) != 0 then mode & ~OS.Truncate else mode

  @static def newOutputStream(path: Path, options: Array[OpenOption]): OutputStream =
    new FileOutputStream(open(path, writeMode(options)))

  @static def newByteChannel(path: Path, options: java.util.Set[? <: OpenOption], attrs: Array[FileAttribute[?]])
      : java.nio.channels.SeekableByteChannel =
    java.nio.channels.FileChannel.open(path, options, attrs)

  @static def newByteChannel(path: Path, options: Array[OpenOption]): java.nio.channels.SeekableByteChannel =
    java.nio.channels.FileChannel.open(path, options)

  private def decoder(cs: Charset) =
    cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)

  private def encoder(cs: Charset) =
    cs.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)

  @static def newBufferedReader(path: Path, cs: Charset): BufferedReader =
    new BufferedReader(new InputStreamReader(newInputStream(path, new Array[OpenOption](0)), decoder(cs)))

  @static def newBufferedReader(path: Path): BufferedReader = newBufferedReader(path, StandardCharsets.UTF_8)

  @static def newBufferedWriter(path: Path, cs: Charset, options: Array[OpenOption]): BufferedWriter =
    new BufferedWriter(new OutputStreamWriter(newOutputStream(path, options), encoder(cs)))

  @static def newBufferedWriter(path: Path, options: Array[OpenOption]): BufferedWriter =
    newBufferedWriter(path, StandardCharsets.UTF_8, options)

  // ---- Reading and writing whole files ----

  @static def readAllBytes(path: Path): Array[Byte] =
    val in = newInputStream(path, new Array[OpenOption](0))
    try in.readAllBytes()
    finally in.close()

  /** Decodes all of `bytes`, reporting malformed input (MalformedInputException). */
  private def decode(bytes: Array[Byte], cs: Charset): String =
    decoder(cs).decodeAll(bytes, 0, bytes.length)

  @static def readString(path: Path, cs: Charset): String = decode(readAllBytes(path), cs)

  @static def readAllLines(path: Path, cs: Charset): java.util.List[String] =
    val reader = newBufferedReader(path, cs)
    try
      val lines = new java.util.ArrayList[String]()
      var line = reader.readLine()
      while line != null do
        lines.add(line)
        line = reader.readLine()
      lines
    finally reader.close()

  @static def readAllLines(path: Path): java.util.List[String] = readAllLines(path, StandardCharsets.UTF_8)
  @static def readString(path: Path): String = readString(path, StandardCharsets.UTF_8)

  @static def write(path: Path, bytes: Array[Byte], options: Array[OpenOption]): Path =
    val out = newOutputStream(path, options)
    try out.write(bytes)
    finally out.close()
    path

  /** Encodes all of `s`, reporting unencodable chars. */
  private def encode(s: CharSequence, cs: Charset): Array[Byte] =
    val chars = s.toString.toCharArray()
    encoder(cs).encodeAll(chars, 0, chars.length)

  @static def writeString(path: Path, csq: CharSequence, cs: Charset, options: Array[OpenOption]): Path =
    write(path, encode(csq, cs), options)

  @static def writeString(path: Path, csq: CharSequence, options: Array[OpenOption]): Path =
    writeString(path, csq, StandardCharsets.UTF_8, options)

  @static def write(path: Path, lines: java.lang.Iterable[? <: CharSequence], cs: Charset, options: Array[OpenOption]): Path =
    val writer = newBufferedWriter(path, cs, options)
    try
      val it = lines.iterator()
      while it.hasNext() do
        writer.append(it.next())
        writer.newLine()
    finally writer.close()
    path

  @static def write(path: Path, lines: java.lang.Iterable[? <: CharSequence], options: Array[OpenOption]): Path =
    write(path, lines, StandardCharsets.UTF_8, options)

  // ---- Directories ----

  /** The entries of a directory, or NotDirectoryException. */
  private def entries(dir: Path): Array[String] =
    val r = OS.readDir(dir.toString)
    if OS.isError(r) then
      if OS.errorKind(r) == OS.NotDirectory then throw new NotDirectoryException(dir.toString)
      throw translate(r, dir.toString, null)
    r.asInstanceOf[Array[String]]

  @static def newDirectoryStream(dir: Path): DirectoryStream[Path] =
    newDirectoryStream(dir, (_: Path) => true)

  @static def newDirectoryStream(dir: Path, filter: DirectoryStream.Filter[? >: Path]): DirectoryStream[Path] =
    val paths = entries(dir).map(dir.resolve).filter(p => filter.accept(p))
    new DirectoryStream[Path]:
      private var used = false
      private var closed = false
      def close(): Unit = closed = true
      def iterator(): java.util.Iterator[Path] =
        if used then throw new IllegalStateException("Iterator already obtained")
        if closed then throw new IllegalStateException("Directory stream is closed")
        used = true
        new java.util.Iterator[Path]:
          private var i = 0
          def hasNext(): scala.Boolean = !closed && i < paths.length
          def next(): Path =
            if !hasNext() then throw new java.util.NoSuchElementException()
            i += 1
            paths(i - 1)

  // ---- Streams of lines and paths ----

  /** An IOException of a stream's close handler, unchecked. */
  private def closeUnchecked(c: Closeable): Runnable = () =>
    try c.close()
    catch case e: IOException => throw new java.io.UncheckedIOException(e)

  @static def lines(path: Path, cs: Charset): java.util.stream.Stream[String] =
    val reader = newBufferedReader(path, cs)
    try reader.lines().onClose(closeUnchecked(reader))
    catch
      case e: Throwable =>
        try reader.close()
        catch case e2: IOException => e.addSuppressed(e2)
        throw e

  @static def lines(path: Path): java.util.stream.Stream[String] = lines(path, StandardCharsets.UTF_8)

  /** The entries of a directory (in name order: the JDK's follows the directory's). */
  @static def list(dir: Path): java.util.stream.Stream[Path] =
    val ds = newDirectoryStream(dir)
    java.util.stream.StreamSupport
      .stream(java.util.Spliterators.spliteratorUnknownSize(ds.iterator(), java.util.Spliterator.DISTINCT), false)
      .onClose(closeUnchecked(ds))

  @static def walk(start: Path, maxDepth: Int, options: Array[FileVisitOption]): java.util.stream.Stream[Path] =
    treeStream(start, maxDepth, options).map(_.path)

  @static def walk(start: Path, options: Array[FileVisitOption]): java.util.stream.Stream[Path] =
    walk(start, Int.MaxValue, options)

  @static def find(start: Path, maxDepth: Int, matcher: java.util.function.BiPredicate[Path, BasicFileAttributes],
      options: Array[FileVisitOption]): java.util.stream.Stream[Path] =
    java.util.Objects.requireNonNull(matcher)
    treeStream(start, maxDepth, options).filter(e => matcher.test(e.path, e.attributes)).map(_.path)

  private final class TreeEntry(val path: Path, val attributes: BasicFileAttributes)

  /** The files under `start`, depth-first and directories first, as the JDK's FileTreeIterator:
   *  a directory is read when the walk reaches it, and an error reading one is thrown as an
   *  UncheckedIOException. Directories loops through links (with FOLLOW_LINKS) are not detected.
   */
  private def treeStream(start: Path, maxDepth: Int, options: Array[FileVisitOption]): java.util.stream.Stream[TreeEntry] =
    if maxDepth < 0 then throw new IllegalArgumentException("'maxDepth' is negative")
    val followLinks = options.exists(_ eq FileVisitOption.FOLLOW_LINKS)
    val startAttrs = new Attributes(stat(start, followLinks))

    final class Level(val dir: Path, val names: Array[String], val depth: Int):
      var next = 0

    val it = new java.util.Iterator[TreeEntry]:
      private val levels = new java.util.ArrayDeque[Level]()
      private var pending = enter(new TreeEntry(start, startAttrs), 0)

      /** Returns `entry`, after opening it if it is a directory to walk into. */
      private def enter(entry: TreeEntry, depth: Int): TreeEntry =
        if entry.attributes.isDirectory() && depth < maxDepth then
          val names =
            try entries(entry.path)
            catch case e: IOException => throw new java.io.UncheckedIOException(e)
          levels.push(new Level(entry.path, names, depth + 1))
        entry

      def hasNext(): scala.Boolean =
        while pending == null && !levels.isEmpty() do
          val level = levels.peek()
          if level.next == level.names.length then levels.pop()
          else
            val path = level.dir.resolve(level.names(level.next))
            level.next += 1
            val r = OS.stat(path.toString, followLinks)
            val attrs =
              if !OS.isError(r) then new Attributes(r.asInstanceOf[Array[Long]])
              else if followLinks && !OS.isError(OS.stat(path.toString, false)) then
                // A broken link: the JDK returns the link itself.
                new Attributes(OS.stat(path.toString, false).asInstanceOf[Array[Long]])
              else throw new java.io.UncheckedIOException(translate(r, path.toString, null))
            pending = enter(new TreeEntry(path, attrs), level.depth)
        pending != null

      def next(): TreeEntry =
        if !hasNext() then throw new java.util.NoSuchElementException()
        val entry = pending
        pending = null
        entry

    java.util.stream.StreamSupport
      .stream(java.util.Spliterators.spliteratorUnknownSize(it, java.util.Spliterator.DISTINCT), false)

  @static def walkFileTree(start: Path, visitor: FileVisitor[? >: Path]): Path =
    walk(start, Int.MaxValue, false, visitor.asInstanceOf[FileVisitor[Path]])
    start

  @static def walkFileTree(start: Path, options: java.util.Set[FileVisitOption], maxDepth: Int,
      visitor: FileVisitor[? >: Path]): Path =
    if maxDepth < 0 then throw new IllegalArgumentException("'maxDepth' is negative")
    walk(start, maxDepth, options.contains(FileVisitOption.FOLLOW_LINKS), visitor.asInstanceOf[FileVisitor[Path]])
    start

  /** Depth-first, as the JDK's FileTreeWalker: CONTINUE, TERMINATE or SKIP_SIBLINGS. */
  private def walk(path: Path, depth: Int, followLinks: scala.Boolean, visitor: FileVisitor[Path]): FileVisitResult =
    val s = statOrNull(path, followLinks)
    if s == null then
      val r = OS.stat(path.toString, followLinks)
      visitor.visitFileFailed(path, translate(r, path.toString, null))
    else if s(0) != OS.Directory || depth == 0 then visitor.visitFile(path, new Attributes(s))
    else
      visitor.preVisitDirectory(path, new Attributes(s)) match
        case FileVisitResult.CONTINUE =>
          var failure: IOException = null
          val names =
            try entries(path)
            catch
              case e: IOException =>
                failure = e
                new Array[String](0)
          var i = 0
          var result = FileVisitResult.CONTINUE
          while result == FileVisitResult.CONTINUE && i < names.length do
            result = walk(path.resolve(names(i)), depth - 1, followLinks, visitor)
            if result == FileVisitResult.SKIP_SUBTREE then result = FileVisitResult.CONTINUE
            i += 1
          if result == FileVisitResult.TERMINATE then result
          else visitor.postVisitDirectory(path, failure)
        case FileVisitResult.SKIP_SUBTREE => FileVisitResult.CONTINUE
        case other                        => other

trait DirectoryStream[T] extends Closeable with java.lang.Iterable[T]

object DirectoryStream:
  trait Filter[T]:
    def accept(entry: T): scala.Boolean

trait FileVisitor[T]:
  def preVisitDirectory(dir: T, attrs: BasicFileAttributes): FileVisitResult
  def visitFile(file: T, attrs: BasicFileAttributes): FileVisitResult
  def visitFileFailed(file: T, exc: IOException): FileVisitResult
  def postVisitDirectory(dir: T, exc: IOException): FileVisitResult

class SimpleFileVisitor[T] protected () extends FileVisitor[T]:
  def preVisitDirectory(dir: T, attrs: BasicFileAttributes): FileVisitResult =
    if dir == null || attrs == null then throw new NullPointerException()
    FileVisitResult.CONTINUE
  def visitFile(file: T, attrs: BasicFileAttributes): FileVisitResult =
    if file == null || attrs == null then throw new NullPointerException()
    FileVisitResult.CONTINUE
  def visitFileFailed(file: T, exc: IOException): FileVisitResult =
    if file == null then throw new NullPointerException()
    throw exc
  def postVisitDirectory(dir: T, exc: IOException): FileVisitResult =
    if dir == null then throw new NullPointerException()
    if exc != null then throw exc
    FileVisitResult.CONTINUE
