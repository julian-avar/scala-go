package java.util

import java.util.function.DoubleConsumer

/** Count, sum, min, max and average of doubles. As in the JDK, the sum is compensated (Kahan
 *  summation), and NaN propagates to min and max.
 */
class DoubleSummaryStatistics() extends DoubleConsumer:
  private var count = 0L
  private var sum = 0.0
  /** The low-order bits lost by the running `sum`. */
  private var sumCompensation = 0.0
  /** The plain sum, to tell infinite sums from NaN ones. */
  private var simpleSum = 0.0
  private var min = java.lang.Double.POSITIVE_INFINITY
  private var max = java.lang.Double.NEGATIVE_INFINITY

  def this(count: Long, min: Double, max: Double, sum: Double) =
    this()
    if count < 0 then throw new IllegalArgumentException("Negative count value")
    if count > 0 then
      if min > max then throw new IllegalArgumentException("Minimum greater than maximum")
      this.count = count
      this.sum = sum
      this.simpleSum = sum
      this.min = min
      this.max = max

  def accept(value: Double): Unit =
    count += 1
    simpleSum += value
    sumWithCompensation(value)
    min = Math.min(min, value)
    max = Math.max(max, value)

  def combine(other: DoubleSummaryStatistics): Unit =
    count += other.count
    simpleSum += other.simpleSum
    sumWithCompensation(other.sum)
    sumWithCompensation(-other.sumCompensation)
    min = Math.min(min, other.min)
    max = Math.max(max, other.max)

  private def sumWithCompensation(value: Double): Unit =
    val tmp = value - sumCompensation
    val velvel = sum + tmp
    sumCompensation = (velvel - sum) - tmp
    sum = velvel

  final def getCount(): Long = count

  final def getSum(): Double =
    val tmp = sum - sumCompensation
    if java.lang.Double.isNaN(tmp) && java.lang.Double.isInfinite(simpleSum) then simpleSum else tmp

  final def getMin(): Double = min
  final def getMax(): Double = max
  final def getAverage(): Double = if count > 0 then getSum() / count else 0.0

  override def toString(): String =
    String.format("%s{count=%d, sum=%f, min=%f, average=%f, max=%f}", getClass().getSimpleName(),
      count, getSum(), min, getAverage(), max)
