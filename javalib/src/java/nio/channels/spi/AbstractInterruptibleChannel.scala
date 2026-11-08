package java.nio.channels.spi

import java.nio.channels.{Channel, InterruptibleChannel}

/** java.nio.channels.spi.AbstractInterruptibleChannel: `close` closes once. Blocking operations
 *  are not interrupted by `Thread.interrupt` (`begin` and `end` do nothing), unlike on the JDK.
 */
abstract class AbstractInterruptibleChannel protected () extends Channel with InterruptibleChannel:
  private val closeLock = new Object
  @volatile private var closed = false

  final def close(): Unit = closeLock.synchronized {
    if !closed then
      closed = true
      implCloseChannel()
  }

  final def isOpen(): Boolean = !closed

  protected def implCloseChannel(): Unit

  protected final def begin(): Unit = ()
  protected final def end(completed: Boolean): Unit = ()
