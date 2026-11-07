package java.nio.channels

import java.io.{Closeable, IOException}
import java.nio.ByteBuffer

trait Channel extends Closeable:
  def isOpen(): Boolean
  def close(): Unit

trait ReadableByteChannel extends Channel:
  def read(dst: ByteBuffer): Int

trait WritableByteChannel extends Channel:
  def write(src: ByteBuffer): Int

trait ByteChannel extends ReadableByteChannel with WritableByteChannel

trait SeekableByteChannel extends ByteChannel:
  def position(): Long
  def position(newPosition: Long): SeekableByteChannel
  def size(): Long
  def truncate(size: Long): SeekableByteChannel

trait ScatteringByteChannel extends ReadableByteChannel:
  def read(dsts: Array[ByteBuffer], offset: Int, length: Int): Long
  def read(dsts: Array[ByteBuffer]): Long

trait GatheringByteChannel extends WritableByteChannel:
  def write(srcs: Array[ByteBuffer], offset: Int, length: Int): Long
  def write(srcs: Array[ByteBuffer]): Long

trait InterruptibleChannel extends Channel

class ClosedChannelException() extends IOException()
class AsynchronousCloseException() extends ClosedChannelException()
class ClosedByInterruptException() extends AsynchronousCloseException()
class FileLockInterruptionException() extends IOException()
class NonReadableChannelException() extends IllegalStateException()
class NonWritableChannelException() extends IllegalStateException()
class OverlappingFileLockException() extends IllegalStateException()
class IllegalBlockingModeException() extends IllegalStateException()
