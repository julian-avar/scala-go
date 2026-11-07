// Adapted from Scala Native's javalib (Apache-2.0, see javalib/LICENSE-scala-native.md).

package java.io

class FilterOutputStream(protected var out: OutputStream) extends OutputStream {
  def write(b: Int): Unit =
    out.write(b)

  override def write(b: Array[Byte]): Unit =
    write(b, 0, b.length) // this is spec! it must not call out.write(b)

  override def flush(): Unit = out.flush()

  override def close(): Unit = {
    flush()
    out.close()
  }
}
