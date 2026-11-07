package java.io

abstract class FilterWriter protected (protected val out: Writer) extends Writer(out):
  override def write(c: Int): Unit = out.write(c)
  def write(cbuf: Array[Char], off: Int, len: Int): Unit = out.write(cbuf, off, len)
  override def write(str: String, off: Int, len: Int): Unit = out.write(str, off, len)
  def flush(): Unit = out.flush()
  def close(): Unit = out.close()
