package java.io

import java.nio.charset.Charset

class FileWriter private (out: FileOutputStream, charset: Charset) extends OutputStreamWriter(out, charset):
  def this(fd: FileDescriptor) = this(new FileOutputStream(fd), Charset.defaultCharset())
  def this(file: File, charset: Charset, append: Boolean) = this(new FileOutputStream(file, append), charset)
  def this(file: File, charset: Charset) = this(file, charset, false)
  def this(file: File, append: Boolean) = this(file, Charset.defaultCharset(), append)
  def this(file: File) = this(file, false)
  def this(fileName: String, charset: Charset, append: Boolean) = this(new File(fileName), charset, append)
  def this(fileName: String, charset: Charset) = this(new File(fileName), charset, false)
  def this(fileName: String, append: Boolean) = this(new File(fileName), append)
  def this(fileName: String) = this(new File(fileName), false)
