package java.nio.channels

import java.io.{FileDescriptor, IOException}
import java.nio.{ByteBuffer, HeapMappedByteBuffer, MappedByteBuffer}

import scalago.runtime.OS

/** The channel of an open file (`fd`), as the JDK's `sun.nio.ch.FileChannelImpl`: its position is
 *  the file descriptor's, shared with the stream it may come from. Closing it closes the file.
 */
private[java] final class FileChannelImpl(fd: FileDescriptor, readable: Boolean, writable: Boolean, append: Boolean)
    extends FileChannel:

  private def handle(): Object =
    if !isOpen() then throw new ClosedChannelException()
    fd.openHandle()

  private def readableHandle(): Object =
    val h = handle()
    if !readable then throw new NonReadableChannelException()
    h

  private def writableHandle(): Object =
    val h = handle()
    if !writable then throw new NonWritableChannelException()
    h

  protected def implCloseChannel(): Unit =
    FileLocks.releaseAll(this)
    fd.close()

  // ---- Reading and writing ----

  def read(dst: ByteBuffer): Int =
    val h = readableHandle()
    if dst.isReadOnly() then throw new IllegalArgumentException("Read-only buffer")
    val n = dst.remaining()
    if n == 0 then 0
    else
      val count = OS.read(h, dst.hb, dst.offset + dst.pos, n)
      if count > 0 then dst.pos += count
      count

  def read(dsts: Array[ByteBuffer], offset: Int, length: Int): Long =
    java.util.Objects.checkFromIndexSize(offset, length, dsts.length)
    readableHandle()
    var total = 0L
    var i = offset
    var done = false
    while !done && i < offset + length do
      val b = dsts(i)
      if b.hasRemaining() then
        val wanted = b.remaining()
        val n = read(b)
        if n < 0 then
          if total == 0 then total = -1
          done = true
        else
          total += n
          if n < wanted then done = true
      i += 1
    total

  def write(src: ByteBuffer): Int =
    val h = writableHandle()
    val n = src.remaining()
    OS.write(h, src.hb, src.offset + src.pos, n)
    src.pos += n
    n

  def write(srcs: Array[ByteBuffer], offset: Int, length: Int): Long =
    java.util.Objects.checkFromIndexSize(offset, length, srcs.length)
    writableHandle()
    var total = 0L
    var i = offset
    while i < offset + length do
      total += write(srcs(i))
      i += 1
    total

  def read(dst: ByteBuffer, position: Long): Int =
    if position < 0 then throw new IllegalArgumentException("Negative position")
    val h = readableHandle()
    if dst.isReadOnly() then throw new IllegalArgumentException("Read-only buffer")
    val n = dst.remaining()
    if n == 0 then 0
    else
      val count = OS.readAt(h, dst.hb, dst.offset + dst.pos, n, position)
      if count > 0 then dst.pos += count
      count

  def write(src: ByteBuffer, position: Long): Int =
    if position < 0 then throw new IllegalArgumentException("Negative position")
    val h = writableHandle()
    val n = src.remaining()
    OS.writeAt(h, src.hb, src.offset + src.pos, n, position)
    src.pos += n
    n

  // ---- Position and size ----

  def position(): Long =
    val h = handle()
    if append then OS.size(h) else OS.seek(h, 0, 1)

  def position(newPosition: Long): FileChannel =
    if newPosition < 0 then throw new IllegalArgumentException()
    OS.seek(handle(), newPosition, 0)
    this

  def size(): Long = OS.size(handle())

  def truncate(size: Long): FileChannel =
    if size < 0 then throw new IllegalArgumentException("Negative size")
    val h = writableHandle()
    val position = OS.seek(h, 0, 1)
    if size < OS.size(h) then OS.truncate(h, size)
    if position > size then OS.seek(h, size, 0)
    this

  def force(metaData: Boolean): Unit = OS.sync(handle())

  // ---- Transfers ----

  def transferTo(position: Long, count: Long, target: WritableByteChannel): Long =
    if position < 0 || count < 0 then throw new IllegalArgumentException()
    val h = readableHandle()
    if !target.isOpen() then throw new ClosedChannelException()
    val buffer = new Array[Byte](8192)
    var total = 0L
    var done = false
    while !done && total < count do
      val n = OS.readAt(h, buffer, 0, Math.min(count - total, buffer.length.toLong).toInt, position + total)
      if n <= 0 then done = true
      else
        val b = ByteBuffer.wrap(buffer, 0, n)
        while b.hasRemaining() do target.write(b)
        total += n
    total

  def transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long =
    if position < 0 || count < 0 then throw new IllegalArgumentException()
    val h = writableHandle()
    if !src.isOpen() then throw new ClosedChannelException()
    // Since JDK 21, a position beyond the end of the file extends it.
    val buffer = ByteBuffer.allocate(8192)
    var total = 0L
    var done = false
    while !done && total < count do
      buffer.clear()
      buffer.limit(Math.min(count - total, buffer.capacity().toLong).toInt)
      val n = src.read(buffer)
      if n <= 0 then done = true
      else
        OS.writeAt(h, buffer.hb, 0, n, position + total)
        total += n
    total

  /** A copy of the region in memory: read-only (`READ_ONLY`), or private to the buffer
   *  (`PRIVATE`, as on the JDK). `READ_WRITE` mappings, whose changes go to the file, are not
   *  supported.
   */
  def map(mode: FileChannel.MapMode, position: Long, size: Long): MappedByteBuffer =
    if mode == null then throw new NullPointerException("Mode is null")
    if position < 0 then throw new IllegalArgumentException("Negative position")
    if size < 0 then throw new IllegalArgumentException("Negative size")
    if size > Int.MaxValue then throw new IllegalArgumentException("Size exceeds Integer.MAX_VALUE")
    val h = readableHandle()
    if mode eq FileChannel.MapMode.READ_WRITE then
      throw new UnsupportedOperationException("READ_WRITE mappings are not supported by scala-go")
    if (mode eq FileChannel.MapMode.PRIVATE) && !writable then throw new NonWritableChannelException()
    val bytes = new Array[Byte](size.toInt)
    var read = 0
    var done = false
    while !done && read < bytes.length do
      val n = OS.readAt(h, bytes, read, bytes.length - read, position + read)
      if n <= 0 then done = true else read += n
    new HeapMappedByteBuffer(bytes.length, bytes, 0, mode eq FileChannel.MapMode.READ_ONLY)

  // ---- Locks ----

  def lock(position: Long, size: Long, shared: Boolean): FileLock = acquire(position, size, shared, true)

  def tryLock(position: Long, size: Long, shared: Boolean): FileLock = acquire(position, size, shared, false)

  private def acquire(position: Long, size: Long, shared: Boolean, waiting: Boolean): FileLock =
    val h = if shared then readableHandle() else writableHandle()
    val lock = new FileLockImpl(this, position, size, shared)
    FileLocks.add(fd.path, lock)
    var acquired = false
    try acquired = OS.lock(h, position, size, shared, waiting)
    finally if !acquired then FileLocks.remove(lock)
    if acquired then lock else null

  private[channels] def unlock(lock: FileLockImpl): Unit =
    if isOpen() then OS.unlock(fd.openHandle(), lock.position(), lock.size())

/** A lock that a FileChannelImpl holds. */
private[channels] final class FileLockImpl(ch: FileChannelImpl, position: Long, size: Long, shared: Boolean)
    extends FileLock(ch, position, size, shared):
  @volatile private[channels] var valid = true

  def isValid(): Boolean = valid && ch.isOpen()

  def release(): Unit =
    if !ch.isOpen() then throw new ClosedChannelException()
    if FileLocks.remove(this) then ch.unlock(this)

/** The locks held in this process, by file: a region may be locked once, as on the JDK (the
 *  operating system's locks belong to the process, so they would not exclude each other).
 */
private[channels] object FileLocks:
  private val byFile = new java.util.HashMap[Object, java.util.ArrayList[FileLockImpl]]()
  /** The key in `byFile` of each lock. */
  private val keys = new java.util.HashMap[FileLockImpl, Object]()

  private def key(path: String, lock: FileLockImpl): Object =
    if path != null then java.nio.file.Path.of(path, new Array[String](0)).toAbsolutePath().normalize() else lock.channel()

  def add(path: String, lock: FileLockImpl): Unit = synchronized {
    val k = key(path, lock)
    var locks = byFile.get(k)
    if locks == null then
      locks = new java.util.ArrayList[FileLockImpl]()
      byFile.put(k, locks)
    val it = locks.iterator()
    while it.hasNext() do
      if it.next().overlaps(lock.position(), lock.size()) then throw new OverlappingFileLockException()
    locks.add(lock)
    keys.put(lock, k)
  }

  /** Removes a lock; false if it was already released. */
  def remove(lock: FileLockImpl): Boolean = synchronized {
    lock.valid = false
    val k = keys.remove(lock)
    k != null && {
      val locks = byFile.get(k)
      locks.remove(lock)
      if locks.isEmpty() then byFile.remove(k)
      true
    }
  }

  /** Forgets the locks of a closing channel (closing the file releases them). */
  def releaseAll(channel: FileChannelImpl): Unit = synchronized {
    val it = keys.keySet().iterator()
    val mine = new java.util.ArrayList[FileLockImpl]()
    while it.hasNext() do
      val lock = it.next()
      if lock.channel() eq channel then mine.add(lock)
    mine.forEach(lock => remove(lock))
  }
