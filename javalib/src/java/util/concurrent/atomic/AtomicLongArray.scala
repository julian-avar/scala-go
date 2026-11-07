package java.util.concurrent.atomic

import java.util.function.{LongBinaryOperator, LongUnaryOperator}

import scalago.runtime.AtomicArrays

/** java.util.concurrent.atomic.AtomicLongArray: an array whose elements are only accessed through
 *  the runtime's atomic operations (`scalago.runtime.AtomicArrays`). Every access is sequentially
 *  consistent, so the plain, opaque, acquire and release variants are the volatile ones.
 */
@SerialVersionUID(-2308431214976778248L)
class AtomicLongArray private (array: Array[Long], ignored: Unit) extends Object with java.io.Serializable:
  def this(length: Int) = this(new Array[Long](length), ())
  def this(array: Array[Long]) = this(array.clone(), ())

  final def length(): Int = array.length

  final def get(i: Int): Long = AtomicArrays.getLong(array, i)
  final def set(i: Int, newValue: Long): Unit = AtomicArrays.setLong(array, i, newValue)
  final def lazySet(i: Int, newValue: Long): Unit = set(i, newValue)
  final def getPlain(i: Int): Long = get(i)
  final def setPlain(i: Int, newValue: Long): Unit = set(i, newValue)
  final def getOpaque(i: Int): Long = get(i)
  final def setOpaque(i: Int, newValue: Long): Unit = set(i, newValue)
  final def getAcquire(i: Int): Long = get(i)
  final def setRelease(i: Int, newValue: Long): Unit = set(i, newValue)

  final def getAndSet(i: Int, newValue: Long): Long = AtomicArrays.getAndSetLong(array, i, newValue)

  final def compareAndSet(i: Int, expectedValue: Long, newValue: Long): Boolean =
    AtomicArrays.compareAndSetLong(array, i, expectedValue, newValue)
  @deprecated("", "9") final def weakCompareAndSet(i: Int, expectedValue: Long, newValue: Long): Boolean =
    compareAndSet(i, expectedValue, newValue)
  final def weakCompareAndSetPlain(i: Int, expectedValue: Long, newValue: Long): Boolean = compareAndSet(i, expectedValue, newValue)
  final def weakCompareAndSetVolatile(i: Int, expectedValue: Long, newValue: Long): Boolean = compareAndSet(i, expectedValue, newValue)
  final def weakCompareAndSetAcquire(i: Int, expectedValue: Long, newValue: Long): Boolean = compareAndSet(i, expectedValue, newValue)
  final def weakCompareAndSetRelease(i: Int, expectedValue: Long, newValue: Long): Boolean = compareAndSet(i, expectedValue, newValue)

  /** Sets element `i` to `newValue` if it is `expectedValue`; returns the value it had. */
  final def compareAndExchange(i: Int, expectedValue: Long, newValue: Long): Long =
    var witness = get(i)
    while witness == expectedValue && !compareAndSet(i, expectedValue, newValue) do witness = get(i)
    witness
  final def compareAndExchangeAcquire(i: Int, expectedValue: Long, newValue: Long): Long = compareAndExchange(i, expectedValue, newValue)
  final def compareAndExchangeRelease(i: Int, expectedValue: Long, newValue: Long): Long = compareAndExchange(i, expectedValue, newValue)

  final def getAndAdd(i: Int, delta: Long): Long = AtomicArrays.getAndAddLong(array, i, delta)
  final def addAndGet(i: Int, delta: Long): Long = getAndAdd(i, delta) + delta
  final def getAndIncrement(i: Int): Long = getAndAdd(i, 1)
  final def getAndDecrement(i: Int): Long = getAndAdd(i, -1)
  final def incrementAndGet(i: Int): Long = addAndGet(i, 1)
  final def decrementAndGet(i: Int): Long = addAndGet(i, -1)

  final def getAndUpdate(i: Int, updateFunction: LongUnaryOperator): Long =
    var prev = get(i)
    var next = 0L
    var haveNext = false
    while true do
      if !haveNext then next = updateFunction.applyAsLong(prev)
      if compareAndSet(i, prev, next) then return prev
      val current = get(i)
      haveNext = current == prev
      prev = current
    prev

  final def updateAndGet(i: Int, updateFunction: LongUnaryOperator): Long =
    var prev = get(i)
    var next = 0L
    var haveNext = false
    while true do
      if !haveNext then next = updateFunction.applyAsLong(prev)
      if compareAndSet(i, prev, next) then return next
      val current = get(i)
      haveNext = current == prev
      prev = current
    next

  final def getAndAccumulate(i: Int, x: Long, accumulatorFunction: LongBinaryOperator): Long =
    getAndUpdate(i, prev => accumulatorFunction.applyAsLong(prev, x))

  final def accumulateAndGet(i: Int, x: Long, accumulatorFunction: LongBinaryOperator): Long =
    updateAndGet(i, prev => accumulatorFunction.applyAsLong(prev, x))

  override def toString(): String =
    val sb = new java.lang.StringBuilder("[")
    for i <- 0 until array.length do
      if i > 0 then sb.append(", ")
      sb.append(get(i))
    sb.append(']').toString()
