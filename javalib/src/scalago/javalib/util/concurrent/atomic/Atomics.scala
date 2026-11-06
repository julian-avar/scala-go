package scalago.javalib.util.concurrent.atomic

import scalago.runtime.Intrinsics

// java.util.concurrent.atomic on @volatile fields and the compareAndSet intrinsic.

class AtomicInteger(initialValue: Int) extends Number with java.io.Serializable:
  def this() = this(0)

  @volatile private var value: Int = initialValue

  final def get(): Int = value
  final def set(newValue: Int): Unit = value = newValue
  final def lazySet(newValue: Int): Unit = value = newValue
  final def getPlain(): Int = value
  final def setPlain(newValue: Int): Unit = value = newValue
  final def getAcquire(): Int = value
  final def setRelease(newValue: Int): Unit = value = newValue
  final def compareAndSet(expected: Int, update: Int): Boolean = Intrinsics.compareAndSet(value, expected, update)
  final def weakCompareAndSet(expected: Int, update: Int): Boolean = compareAndSet(expected, update)
  final def weakCompareAndSetPlain(expected: Int, update: Int): Boolean = compareAndSet(expected, update)
  final def getAndSet(newValue: Int): Int =
    var current = value
    while !compareAndSet(current, newValue) do current = value
    current

  def intValue(): Int = get().toInt
  def longValue(): scala.Long = get().toLong
  def floatValue(): scala.Float = get().toFloat
  def doubleValue(): scala.Double = get().toDouble

  final def getAndAdd(delta: Int): Int =
    var current = value
    while !compareAndSet(current, current + delta) do current = value
    current
  final def addAndGet(delta: Int): Int = getAndAdd(delta) + delta
  final def getAndIncrement(): Int = getAndAdd(1)
  final def getAndDecrement(): Int = getAndAdd(-1)
  final def incrementAndGet(): Int = addAndGet(1)
  final def decrementAndGet(): Int = addAndGet(-1)

  override def toString(): String = String.valueOf(get())

class AtomicLong(initialValue: scala.Long) extends Number with java.io.Serializable:
  def this() = this(0L)

  @volatile private var value: scala.Long = initialValue

  final def get(): scala.Long = value
  final def set(newValue: scala.Long): Unit = value = newValue
  final def lazySet(newValue: scala.Long): Unit = value = newValue
  final def getPlain(): scala.Long = value
  final def setPlain(newValue: scala.Long): Unit = value = newValue
  final def getAcquire(): scala.Long = value
  final def setRelease(newValue: scala.Long): Unit = value = newValue
  final def compareAndSet(expected: scala.Long, update: scala.Long): Boolean = Intrinsics.compareAndSet(value, expected, update)
  final def weakCompareAndSet(expected: scala.Long, update: scala.Long): Boolean = compareAndSet(expected, update)
  final def weakCompareAndSetPlain(expected: scala.Long, update: scala.Long): Boolean = compareAndSet(expected, update)
  final def getAndSet(newValue: scala.Long): scala.Long =
    var current = value
    while !compareAndSet(current, newValue) do current = value
    current

  def intValue(): Int = get().toInt
  def longValue(): scala.Long = get().toLong
  def floatValue(): scala.Float = get().toFloat
  def doubleValue(): scala.Double = get().toDouble

  final def getAndAdd(delta: scala.Long): scala.Long =
    var current = value
    while !compareAndSet(current, current + delta) do current = value
    current
  final def addAndGet(delta: scala.Long): scala.Long = getAndAdd(delta) + delta
  final def getAndIncrement(): scala.Long = getAndAdd(1)
  final def getAndDecrement(): scala.Long = getAndAdd(-1)
  final def incrementAndGet(): scala.Long = addAndGet(1)
  final def decrementAndGet(): scala.Long = addAndGet(-1)

  override def toString(): String = String.valueOf(get())

class AtomicBoolean(initialValue: Boolean) extends Object with java.io.Serializable:
  def this() = this(false)

  @volatile private var value: Boolean = initialValue

  final def get(): Boolean = value
  final def set(newValue: Boolean): Unit = value = newValue
  final def lazySet(newValue: Boolean): Unit = value = newValue
  final def getPlain(): Boolean = value
  final def setPlain(newValue: Boolean): Unit = value = newValue
  final def getAcquire(): Boolean = value
  final def setRelease(newValue: Boolean): Unit = value = newValue
  final def compareAndSet(expected: Boolean, update: Boolean): Boolean = Intrinsics.compareAndSet(value, expected, update)
  final def weakCompareAndSet(expected: Boolean, update: Boolean): Boolean = compareAndSet(expected, update)
  final def weakCompareAndSetPlain(expected: Boolean, update: Boolean): Boolean = compareAndSet(expected, update)
  final def getAndSet(newValue: Boolean): Boolean =
    var current = value
    while !compareAndSet(current, newValue) do current = value
    current

  override def toString(): String = String.valueOf(get())

class AtomicReference[V](initialValue: V) extends Object with java.io.Serializable:
  def this() = this(null.asInstanceOf[V])

  @volatile private var value: V = initialValue

  final def get(): V = value
  final def set(newValue: V): Unit = value = newValue
  final def lazySet(newValue: V): Unit = value = newValue
  final def getPlain(): V = value
  final def setPlain(newValue: V): Unit = value = newValue
  final def getAcquire(): V = value
  final def setRelease(newValue: V): Unit = value = newValue
  final def compareAndSet(expected: V, update: V): Boolean =
    Intrinsics.compareAndSet(value.asInstanceOf[AnyRef], expected.asInstanceOf[AnyRef], update.asInstanceOf[AnyRef])
  final def weakCompareAndSet(expected: V, update: V): Boolean = compareAndSet(expected, update)
  final def weakCompareAndSetPlain(expected: V, update: V): Boolean = compareAndSet(expected, update)
  final def getAndSet(newValue: V): V =
    var current = value
    while !compareAndSet(current, newValue) do current = value
    current

  override def toString(): String = String.valueOf(get())
