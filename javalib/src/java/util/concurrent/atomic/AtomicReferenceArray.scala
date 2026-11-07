package java.util.concurrent.atomic

import java.util.function.{BinaryOperator, UnaryOperator}

import scalago.runtime.AtomicArrays

/** java.util.concurrent.atomic.AtomicReferenceArray on the runtime's arrays of volatile
 *  references (`scalago.runtime.AtomicArrays`). Every access is sequentially consistent, so the
 *  plain, opaque, acquire and release variants are the volatile ones. Values are compared by
 *  identity.
 */
@SerialVersionUID(-6209656149925076980L)
class AtomicReferenceArray[E](size: Int) extends Object with java.io.Serializable:
  private val refs: Object = AtomicArrays.newRefs(size)

  def this(array: Array[E]) =
    this(array.length)
    for i <- 0 until array.length do set(i, array(i))

  final def length(): Int = AtomicArrays.length(refs)

  final def get(i: Int): E = AtomicArrays.get(refs, i).asInstanceOf[E]
  final def set(i: Int, newValue: E): Unit = AtomicArrays.set(refs, i, newValue.asInstanceOf[Object])
  final def lazySet(i: Int, newValue: E): Unit = set(i, newValue)
  final def getPlain(i: Int): E = get(i)
  final def setPlain(i: Int, newValue: E): Unit = set(i, newValue)
  final def getOpaque(i: Int): E = get(i)
  final def setOpaque(i: Int, newValue: E): Unit = set(i, newValue)
  final def getAcquire(i: Int): E = get(i)
  final def setRelease(i: Int, newValue: E): Unit = set(i, newValue)

  final def getAndSet(i: Int, newValue: E): E =
    AtomicArrays.getAndSet(refs, i, newValue.asInstanceOf[Object]).asInstanceOf[E]

  final def compareAndSet(i: Int, expectedValue: E, newValue: E): Boolean =
    AtomicArrays.compareAndSet(refs, i, expectedValue.asInstanceOf[Object], newValue.asInstanceOf[Object])
  @deprecated("", "9") final def weakCompareAndSet(i: Int, expectedValue: E, newValue: E): Boolean =
    compareAndSet(i, expectedValue, newValue)
  final def weakCompareAndSetPlain(i: Int, expectedValue: E, newValue: E): Boolean = compareAndSet(i, expectedValue, newValue)
  final def weakCompareAndSetVolatile(i: Int, expectedValue: E, newValue: E): Boolean = compareAndSet(i, expectedValue, newValue)
  final def weakCompareAndSetAcquire(i: Int, expectedValue: E, newValue: E): Boolean = compareAndSet(i, expectedValue, newValue)
  final def weakCompareAndSetRelease(i: Int, expectedValue: E, newValue: E): Boolean = compareAndSet(i, expectedValue, newValue)

  /** Sets element `i` to `newValue` if it is `expectedValue`; returns the value it had. */
  final def compareAndExchange(i: Int, expectedValue: E, newValue: E): E =
    var witness = get(i)
    while (witness.asInstanceOf[AnyRef] eq expectedValue.asInstanceOf[AnyRef]) && !compareAndSet(i, expectedValue, newValue) do
      witness = get(i)
    witness
  final def compareAndExchangeAcquire(i: Int, expectedValue: E, newValue: E): E = compareAndExchange(i, expectedValue, newValue)
  final def compareAndExchangeRelease(i: Int, expectedValue: E, newValue: E): E = compareAndExchange(i, expectedValue, newValue)

  final def getAndUpdate(i: Int, updateFunction: UnaryOperator[E]): E =
    var prev = get(i)
    var next = null.asInstanceOf[E]
    var haveNext = false
    while true do
      if !haveNext then next = updateFunction.apply(prev)
      if compareAndSet(i, prev, next) then return prev
      val current = get(i)
      haveNext = current.asInstanceOf[AnyRef] eq prev.asInstanceOf[AnyRef]
      prev = current
    prev

  final def updateAndGet(i: Int, updateFunction: UnaryOperator[E]): E =
    var prev = get(i)
    var next = null.asInstanceOf[E]
    var haveNext = false
    while true do
      if !haveNext then next = updateFunction.apply(prev)
      if compareAndSet(i, prev, next) then return next
      val current = get(i)
      haveNext = current.asInstanceOf[AnyRef] eq prev.asInstanceOf[AnyRef]
      prev = current
    next

  final def getAndAccumulate(i: Int, x: E, accumulatorFunction: BinaryOperator[E]): E =
    getAndUpdate(i, prev => accumulatorFunction.apply(prev, x))

  final def accumulateAndGet(i: Int, x: E, accumulatorFunction: BinaryOperator[E]): E =
    updateAndGet(i, prev => accumulatorFunction.apply(prev, x))

  override def toString(): String =
    val sb = new java.lang.StringBuilder("[")
    for i <- 0 until length() do
      if i > 0 then sb.append(", ")
      sb.append(get(i))
    sb.append(']').toString()
