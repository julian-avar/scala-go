package java.nio.channels

import java.io.{FileDescriptor, IOException}
import java.nio.{ByteBuffer, HeapMappedByteBuffer, MappedByteBuffer}
import java.nio.file.{FileSystemErrors, FileSystemException, LinkOption, OpenOption, Path, StandardOpenOption}
import java.nio.file.attribute.FileAttribute

import scalago.runtime.OS

/** java.nio.channels.FileChannel. Channels of files are `FileChannelImpl`s, on the native
 *  layer's handles (`scalago.runtime.OS`), shared with the stream they come from
 *  (`FileInputStream.getChannel`, ...).
 */
abstract class FileChannel protected ()
    extends spi.AbstractInterruptibleChannel
    with SeekableByteChannel
    with GatheringByteChannel
    with ScatteringByteChannel:
  def read(dst: ByteBuffer): Int
  def read(dsts: Array[ByteBuffer], offset: Int, length: Int): Long
  final def read(dsts: Array[ByteBuffer]): Long = read(dsts, 0, dsts.length)
  def write(src: ByteBuffer): Int
  def write(srcs: Array[ByteBuffer], offset: Int, length: Int): Long
  final def write(srcs: Array[ByteBuffer]): Long = write(srcs, 0, srcs.length)
  def position(): Long
  def position(newPosition: Long): FileChannel
  def size(): Long
  def truncate(size: Long): FileChannel
  def force(metaData: Boolean): Unit
  def transferTo(position: Long, count: Long, target: WritableByteChannel): Long
  def transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long
  def read(dst: ByteBuffer, position: Long): Int
  def write(src: ByteBuffer, position: Long): Int
  def map(mode: FileChannel.MapMode, position: Long, size: Long): MappedByteBuffer
  def lock(position: Long, size: Long, shared: Boolean): FileLock
  final def lock(): FileLock = lock(0L, Long.MaxValue, false)
  def tryLock(position: Long, size: Long, shared: Boolean): FileLock
  final def tryLock(): FileLock = tryLock(0L, Long.MaxValue, false)

object FileChannel:
  final class MapMode private (name: String):
    override def toString(): String = name

  object MapMode:
    val READ_ONLY: MapMode = new MapMode("READ_ONLY")
    val READ_WRITE: MapMode = new MapMode("READ_WRITE")
    val PRIVATE: MapMode = new MapMode("PRIVATE")

  def open(path: Path, options: java.util.Set[? <: OpenOption], attrs: Array[FileAttribute[?]]): FileChannel =
    var read = false
    var write = false
    var append = false
    var truncate = false
    var create = false
    var createNew = false
    var deleteOnClose = false
    val it = options.iterator()
    while it.hasNext() do
      it.next() match
        case StandardOpenOption.READ              => read = true
        case StandardOpenOption.WRITE             => write = true
        case StandardOpenOption.APPEND            => append = true
        case StandardOpenOption.TRUNCATE_EXISTING => truncate = true
        case StandardOpenOption.CREATE            => create = true
        case StandardOpenOption.CREATE_NEW        => createNew = true
        case StandardOpenOption.DELETE_ON_CLOSE   => deleteOnClose = true
        case null                                 => throw new NullPointerException()
        case o: StandardOpenOption                => () // SPARSE, SYNC, DSYNC
        case o if o eq LinkOption.NOFOLLOW_LINKS  => ()
        case o                                    => throw new UnsupportedOperationException(o.toString + " not supported")
    if append then write = true
    if !write then read = true
    if read && append then throw new IllegalArgumentException("READ + APPEND not allowed")
    if append && truncate then throw new IllegalArgumentException("APPEND + TRUNCATE_EXISTING not allowed")
    var mode = (if read then OS.Read else 0) | (if write then OS.Write else 0)
    if write then
      if append then mode |= OS.Append
      if truncate then mode |= OS.Truncate
      if createNew then mode |= OS.CreateNew
      else if create then mode |= OS.Create
    val file = path.toString
    val r = OS.open(file, mode, 0x1b6 /* 0666 */)
    if OS.isError(r) then
      if OS.errorKind(r) == OS.IsDirectory then throw new FileSystemException(file, null, "Is a directory")
      throw FileSystemErrors.translate(r, file, null)
    // As the JDK on Unix, DELETE_ON_CLOSE unlinks the file right away.
    if deleteOnClose then OS.remove(file)
    val fd = new FileDescriptor(r)
    fd.path = file
    new FileChannelImpl(fd, read, write, append)

  def open(path: Path, options: Array[OpenOption]): FileChannel =
    val set = new java.util.HashSet[OpenOption]()
    for o <- options do set.add(o)
    open(path, set, new Array[FileAttribute[?]](0))

/** java.nio.channels.FileLock. */
abstract class FileLock protected (ch: FileChannel, start: Long, length: Long, shared: Boolean)
    extends AutoCloseable:
  if start < 0 then throw new IllegalArgumentException("Negative position")
  if length < 0 then throw new IllegalArgumentException("Negative size")
  if start + length < 0 then throw new IllegalArgumentException("Negative position + size")

  final def channel(): FileChannel = ch
  def acquiredBy(): Channel = ch
  final def position(): Long = start
  final def size(): Long = length
  final def isShared(): Boolean = shared
  final def overlaps(position: Long, size: Long): Boolean =
    position + size > start && start + length > position
  def isValid(): Boolean
  def release(): Unit
  final def close(): Unit = release()

  override final def toString(): String =
    getClass().getName() + "[" + start + ":" + length + " " + (if shared then "shared" else "exclusive") + " " +
      (if isValid() then "valid" else "invalid") + "]"
