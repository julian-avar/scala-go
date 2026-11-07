package java.io

import java.nio.charset.Charset

class FileReader private (fd: FileDescriptor, charset: Charset) extends InputStreamReader(new FileInputStream(fd), charset):
  def this(fd: FileDescriptor) = this(fd, Charset.defaultCharset())
  def this(file: File, charset: Charset) = this(FileDescriptor.open(file, scalago.runtime.OS.Read), charset)
  def this(file: File) = this(file, Charset.defaultCharset())
  def this(fileName: String, charset: Charset) = this(new File(fileName), charset)
  def this(fileName: String) = this(new File(fileName))
