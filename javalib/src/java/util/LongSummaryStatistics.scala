package java.util

import java.util.function.{IntConsumer, LongConsumer}

/** Count, sum, min, max and average of longs. */
class LongSummaryStatistics() extends LongConsumer with IntConsumer:
  private var count = 0L
  private var sum = 0L
  private var min = java.lang.Long.MAX_VALUE
  private var max = java.lang.Long.MIN_VALUE

  def this(count: Long, min: Long, max: Long, sum: Long) =
    this()
    if count < 0 then throw new IllegalArgumentException("Negative count value")
    if count > 0 then
      if min > max then throw new IllegalArgumentException("Minimum greater than maximum")
      this.count = count
      this.sum = sum
      this.min = min
      this.max = max

  def accept(value: Int): Unit = accept(value.toLong)

  def accept(value: Long): Unit =
    count += 1
    sum += value
    min = Math.min(min, value)
    max = Math.max(max, value)

  def combine(other: LongSummaryStatistics): Unit =
    count += other.count
    sum += other.sum
    min = Math.min(min, other.min)
    max = Math.max(max, other.max)

  final def getCount(): Long = count
  final def getSum(): Long = sum
  final def getMin(): Long = min
  final def getMax(): Long = max
  final def getAverage(): Double = if count > 0 then sum.toDouble / count else 0.0

  override def toString(): String =
    String.format("%s{count=%d, sum=%d, min=%d, average=%f, max=%d}", getClass().getSimpleName(),
      count, sum, min, getAverage(), max)
