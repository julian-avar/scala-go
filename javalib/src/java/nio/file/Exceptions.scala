package java.nio.file

import java.io.IOException

import scalago.runtime.OS

/** The JDK's exceptions for failed operations of the native layer (`scalago.runtime.OS`). */
private[java] object FileSystemErrors:
  /** The exception for an error on `file` (and `other`). */
  def translate(error: Object, file: String, other: String): IOException =
    OS.errorKind(error) match
      case OS.NoSuchFile    => new NoSuchFileException(file, other, null)
      case OS.AlreadyExists => new FileAlreadyExistsException(file, other, null)
      case OS.AccessDenied  => new AccessDeniedException(file, other, null)
      case OS.Loop =>
        new FileSystemException(file, other, OS.errorReason(error) + " or unable to access attributes of symbolic link")
      case _ => new FileSystemException(file, other, OS.errorReason(error))

class FileSystemException(file: String, other: String, reason: String) extends IOException(reason):
  def this(file: String) = this(file, null, null)

  def getFile(): String = file
  def getOtherFile(): String = other
  def getReason(): String = super.getMessage()

  /** As in the JDK: `file -> other: reason`, leaving out what is null. */
  override def getMessage(): String =
    if file == null && other == null then getReason()
    else
      val sb = new java.lang.StringBuilder()
      if file != null then sb.append(file)
      if other != null then sb.append(" -> ").append(other)
      if getReason() != null then sb.append(": ").append(getReason())
      sb.toString()

class AccessDeniedException(file: String, other: String, reason: String) extends FileSystemException(file, other, reason):
  def this(file: String) = this(file, null, null)

class NoSuchFileException(file: String, other: String, reason: String) extends FileSystemException(file, other, reason):
  def this(file: String) = this(file, null, null)

class FileAlreadyExistsException(file: String, other: String, reason: String) extends FileSystemException(file, other, reason):
  def this(file: String) = this(file, null, null)

class DirectoryNotEmptyException(dir: String) extends FileSystemException(dir)

class NotDirectoryException(file: String) extends FileSystemException(file)

class NotLinkException(file: String, other: String, reason: String) extends FileSystemException(file, other, reason):
  def this(file: String) = this(file, null, null)

class FileSystemLoopException(file: String) extends FileSystemException(file)

class AtomicMoveNotSupportedException(source: String, target: String, reason: String)
    extends FileSystemException(source, target, reason)

class InvalidPathException(input: String, reason: String, index: Int) extends IllegalArgumentException(reason):
  if input == null || reason == null then throw new NullPointerException()
  if index < -1 then throw new IllegalArgumentException()

  def this(input: String, reason: String) = this(input, reason, -1)

  def getInput(): String = input
  def getReason(): String = reason
  def getIndex(): Int = index

  override def getMessage(): String =
    reason + (if index > -1 then " at index " + index else "") + ": " + input

class DirectoryIteratorException(cause: IOException) extends java.util.ConcurrentModificationException(cause.toString()):
  initCause(cause)
  override def getCause(): IOException = cause

class FileSystemNotFoundException(msg: String) extends RuntimeException(msg):
  def this() = this(null)
