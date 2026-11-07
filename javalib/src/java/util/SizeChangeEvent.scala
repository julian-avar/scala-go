// Adapted from Scala Native's javalib (Apache-2.0, see javalib/LICENSE-scala-native.md).
package java.util

private[util] trait SizeChangeEvent {
  protected var end: Int

  @inline
  protected final def changeSize(delta: Int): Unit = {
    end += delta
    onSizeChanged(delta)
  }

  protected def onSizeChanged(delta: Int): Unit = () // override if needed
}
