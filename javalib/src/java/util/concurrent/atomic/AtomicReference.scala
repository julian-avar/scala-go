package java.util.concurrent.atomic

import java.util.function.{BinaryOperator, UnaryOperator}

import scalago.runtime.Intrinsics

/** java.util.concurrent.atomic.AtomicReference: a `@volatile` field and the atomic intrinsics.
 *  Every access is sequentially consistent, so the plain, opaque, acquire and release variants
 *  are the volatile ones. Values are compared by identity.
 */
@SerialVersionUID(-1848883965231344442L)
class AtomicReference[V](initialValue: V) extends Object with java.io.Serializable:
  def this() = this(null.asInstanceOf[V])

  @volatile private var value: AnyRef = initialValue.asInstanceOf[AnyRef]

  final def get(): V = value.asInstanceOf[V]
  final def set(newValue: V): Unit = value = newValue.asInstanceOf[AnyRef]
  final def lazySet(newValue: V): Unit = set(newValue)
  final def getPlain(): V = get()
  final def setPlain(newValue: V): Unit = set(newValue)
  final def getOpaque(): V = get()
  final def setOpaque(newValue: V): Unit = set(newValue)
  final def getAcquire(): V = get()
  final def setRelease(newValue: V): Unit = set(newValue)

  final def compareAndSet(expectedValue: V, newValue: V): Boolean =
    Intrinsics.compareAndSet(value, expectedValue.asInstanceOf[AnyRef], newValue.asInstanceOf[AnyRef])
  @deprecated("", "9") final def weakCompareAndSet(expectedValue: V, newValue: V): Boolean = compareAndSet(expectedValue, newValue)
  final def weakCompareAndSetPlain(expectedValue: V, newValue: V): Boolean = compareAndSet(expectedValue, newValue)
  final def weakCompareAndSetVolatile(expectedValue: V, newValue: V): Boolean = compareAndSet(expectedValue, newValue)
  final def weakCompareAndSetAcquire(expectedValue: V, newValue: V): Boolean = compareAndSet(expectedValue, newValue)
  final def weakCompareAndSetRelease(expectedValue: V, newValue: V): Boolean = compareAndSet(expectedValue, newValue)

  /** Sets the value to `newValue` if it is `expectedValue`; returns the value it had. */
  final def compareAndExchange(expectedValue: V, newValue: V): V =
    val expected = expectedValue.asInstanceOf[AnyRef]
    var witness = value
    while (witness eq expected) && !Intrinsics.compareAndSet(value, expected, newValue.asInstanceOf[AnyRef]) do witness = value
    witness.asInstanceOf[V]
  final def compareAndExchangeAcquire(expectedValue: V, newValue: V): V = compareAndExchange(expectedValue, newValue)
  final def compareAndExchangeRelease(expectedValue: V, newValue: V): V = compareAndExchange(expectedValue, newValue)

  final def getAndSet(newValue: V): V = Intrinsics.getAndSet(value, newValue.asInstanceOf[AnyRef]).asInstanceOf[V]

  final def getAndUpdate(updateFunction: UnaryOperator[V]): V =
    var prev = get()
    var next = null.asInstanceOf[V]
    var haveNext = false
    while true do
      if !haveNext then next = updateFunction.apply(prev)
      if compareAndSet(prev, next) then return prev
      val current = get()
      haveNext = current.asInstanceOf[AnyRef] eq prev.asInstanceOf[AnyRef]
      prev = current
    prev

  final def updateAndGet(updateFunction: UnaryOperator[V]): V =
    var prev = get()
    var next = null.asInstanceOf[V]
    var haveNext = false
    while true do
      if !haveNext then next = updateFunction.apply(prev)
      if compareAndSet(prev, next) then return next
      val current = get()
      haveNext = current.asInstanceOf[AnyRef] eq prev.asInstanceOf[AnyRef]
      prev = current
    next

  final def getAndAccumulate(x: V, accumulatorFunction: BinaryOperator[V]): V =
    getAndUpdate(prev => accumulatorFunction.apply(prev, x))

  final def accumulateAndGet(x: V, accumulatorFunction: BinaryOperator[V]): V =
    updateAndGet(prev => accumulatorFunction.apply(prev, x))

  override def toString(): String = String.valueOf(value)
