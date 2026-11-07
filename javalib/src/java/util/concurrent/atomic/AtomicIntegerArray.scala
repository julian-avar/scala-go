package java.util.concurrent.atomic

import java.util.function.{IntBinaryOperator, IntUnaryOperator}

import scalago.runtime.AtomicArrays

/** java.util.concurrent.atomic.AtomicIntegerArray: an array whose elements are only accessed through
 *  the runtime's atomic operations (`scalago.runtime.AtomicArrays`). Every access is sequentially
 *  consistent, so the plain, opaque, acquire and release variants are the volatile ones.
 */
@SerialVersionUID(2862133569453604235L)
class AtomicIntegerArray private (array: Array[Int], ignored: Unit) extends Object with java.io.Serializable:
  def this(length: Int) = this(new Array[Int](length), ())
  def this(array: Array[Int]) = this(array.clone(), ())

  final def length(): Int = array.length

  final def get(i: Int): Int = AtomicArrays.getInt(array, i)
  final def set(i: Int, newValue: Int): Unit = AtomicArrays.setInt(array, i, newValue)
  final def lazySet(i: Int, newValue: Int): Unit = set(i, newValue)
  final def getPlain(i: Int): Int = get(i)
  final def setPlain(i: Int, newValue: Int): Unit = set(i, newValue)
  final def getOpaque(i: Int): Int = get(i)
  final def setOpaque(i: Int, newValue: Int): Unit = set(i, newValue)
  final def getAcquire(i: Int): Int = get(i)
  final def setRelease(i: Int, newValue: Int): Unit = set(i, newValue)

  final def getAndSet(i: Int, newValue: Int): Int = AtomicArrays.getAndSetInt(array, i, newValue)

  final def compareAndSet(i: Int, expectedValue: Int, newValue: Int): Boolean =
    AtomicArrays.compareAndSetInt(array, i, expectedValue, newValue)
  @deprecated("", "9") final def weakCompareAndSet(i: Int, expectedValue: Int, newValue: Int): Boolean =
    compareAndSet(i, expectedValue, newValue)
  final def weakCompareAndSetPlain(i: Int, expectedValue: Int, newValue: Int): Boolean = compareAndSet(i, expectedValue, newValue)
  final def weakCompareAndSetVolatile(i: Int, expectedValue: Int, newValue: Int): Boolean = compareAndSet(i, expectedValue, newValue)
  final def weakCompareAndSetAcquire(i: Int, expectedValue: Int, newValue: Int): Boolean = compareAndSet(i, expectedValue, newValue)
  final def weakCompareAndSetRelease(i: Int, expectedValue: Int, newValue: Int): Boolean = compareAndSet(i, expectedValue, newValue)

  /** Sets element `i` to `newValue` if it is `expectedValue`; returns the value it had. */
  final def compareAndExchange(i: Int, expectedValue: Int, newValue: Int): Int =
    var witness = get(i)
    while witness == expectedValue && !compareAndSet(i, expectedValue, newValue) do witness = get(i)
    witness
  final def compareAndExchangeAcquire(i: Int, expectedValue: Int, newValue: Int): Int = compareAndExchange(i, expectedValue, newValue)
  final def compareAndExchangeRelease(i: Int, expectedValue: Int, newValue: Int): Int = compareAndExchange(i, expectedValue, newValue)

  final def getAndAdd(i: Int, delta: Int): Int = AtomicArrays.getAndAddInt(array, i, delta)
  final def addAndGet(i: Int, delta: Int): Int = getAndAdd(i, delta) + delta
  final def getAndIncrement(i: Int): Int = getAndAdd(i, 1)
  final def getAndDecrement(i: Int): Int = getAndAdd(i, -1)
  final def incrementAndGet(i: Int): Int = addAndGet(i, 1)
  final def decrementAndGet(i: Int): Int = addAndGet(i, -1)

  final def getAndUpdate(i: Int, updateFunction: IntUnaryOperator): Int =
    var prev = get(i)
    var next = 0
    var haveNext = false
    while true do
      if !haveNext then next = updateFunction.applyAsInt(prev)
      if compareAndSet(i, prev, next) then return prev
      val current = get(i)
      haveNext = current == prev
      prev = current
    prev

  final def updateAndGet(i: Int, updateFunction: IntUnaryOperator): Int =
    var prev = get(i)
    var next = 0
    var haveNext = false
    while true do
      if !haveNext then next = updateFunction.applyAsInt(prev)
      if compareAndSet(i, prev, next) then return next
      val current = get(i)
      haveNext = current == prev
      prev = current
    next

  final def getAndAccumulate(i: Int, x: Int, accumulatorFunction: IntBinaryOperator): Int =
    getAndUpdate(i, prev => accumulatorFunction.applyAsInt(prev, x))

  final def accumulateAndGet(i: Int, x: Int, accumulatorFunction: IntBinaryOperator): Int =
    updateAndGet(i, prev => accumulatorFunction.applyAsInt(prev, x))

  override def toString(): String =
    val sb = new java.lang.StringBuilder("[")
    for i <- 0 until array.length do
      if i > 0 then sb.append(", ")
      sb.append(get(i))
    sb.append(']').toString()
