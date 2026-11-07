package java.util.concurrent.atomic

import scalago.runtime.Intrinsics

/** java.util.concurrent.atomic.AtomicBoolean: a `@volatile` field and the atomic intrinsics.
 *  Every access is sequentially consistent, so the plain, opaque, acquire and release variants
 *  are the volatile ones.
 */
@SerialVersionUID(4654671469794556979L)
class AtomicBoolean(initialValue: Boolean) extends Object with java.io.Serializable:
  def this() = this(false)

  @volatile private var value: Boolean = initialValue

  final def get(): Boolean = value
  final def set(newValue: Boolean): Unit = value = newValue
  final def lazySet(newValue: Boolean): Unit = value = newValue
  final def getPlain(): Boolean = value
  final def setPlain(newValue: Boolean): Unit = value = newValue
  final def getOpaque(): Boolean = value
  final def setOpaque(newValue: Boolean): Unit = value = newValue
  final def getAcquire(): Boolean = value
  final def setRelease(newValue: Boolean): Unit = value = newValue

  final def compareAndSet(expectedValue: Boolean, newValue: Boolean): Boolean =
    Intrinsics.compareAndSet(value, expectedValue, newValue)
  @deprecated("", "9") final def weakCompareAndSet(expectedValue: Boolean, newValue: Boolean): Boolean =
    compareAndSet(expectedValue, newValue)
  final def weakCompareAndSetPlain(expectedValue: Boolean, newValue: Boolean): Boolean = compareAndSet(expectedValue, newValue)
  final def weakCompareAndSetVolatile(expectedValue: Boolean, newValue: Boolean): Boolean = compareAndSet(expectedValue, newValue)
  final def weakCompareAndSetAcquire(expectedValue: Boolean, newValue: Boolean): Boolean = compareAndSet(expectedValue, newValue)
  final def weakCompareAndSetRelease(expectedValue: Boolean, newValue: Boolean): Boolean = compareAndSet(expectedValue, newValue)

  /** Sets the value to `newValue` if it is `expectedValue`; returns the value it had. */
  final def compareAndExchange(expectedValue: Boolean, newValue: Boolean): Boolean =
    var witness = value
    while witness == expectedValue && !Intrinsics.compareAndSet(value, expectedValue, newValue) do witness = value
    witness
  final def compareAndExchangeAcquire(expectedValue: Boolean, newValue: Boolean): Boolean = compareAndExchange(expectedValue, newValue)
  final def compareAndExchangeRelease(expectedValue: Boolean, newValue: Boolean): Boolean = compareAndExchange(expectedValue, newValue)

  final def getAndSet(newValue: Boolean): Boolean = Intrinsics.getAndSet(value, newValue)

  override def toString(): String = String.valueOf(value)
