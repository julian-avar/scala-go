// Adapted from Scala Native's javalib (Apache-2.0, see javalib/LICENSE-scala-native.md).
package java.util.stream

import java.util._
import java.util.function._
import java.{lang => jl}

/* Design Note:
 *
 * LongStream extends BaseStream[jl.Long, LongStream]
 * in correspondence to the documentation & usage of Spliterator.Of*
 * and PrimitiveIterator.Of*. That is, the first type is a Java container.
 *
 * scala-go: the operations are implemented by LongPipeline. The default methods here (Java's
 * defaults) only serve other implementations of LongStream.
 */

trait LongStream extends BaseStream[jl.Long, LongStream] {

  def allMatch(pred: LongPredicate): Boolean

  def anyMatch(pred: LongPredicate): Boolean

  def asDoubleStream(): DoubleStream

  def average(): OptionalDouble

  def boxed(): Stream[jl.Long]

  def collect[R](
      supplier: Supplier[R],
      accumulator: ObjLongConsumer[R],
      combiner: BiConsumer[R, R]
  ): R

  def count(): scala.Long

  def distinct(): LongStream

  // Since: Java 9
  def dropWhile(pred: LongPredicate): LongStream = {
    Objects.requireNonNull(pred)
    boxed().dropWhile(i => pred.test(i)).mapToLong(i => i)
  }

  def filter(pred: LongPredicate): LongStream

  def findAny(): OptionalLong

  def findFirst(): OptionalLong

  def flatMap(mapper: LongFunction[? <: LongStream]): LongStream

  def forEach(action: LongConsumer): Unit

  def forEachOrdered(action: LongConsumer): Unit

  override def iterator(): PrimitiveIterator.OfLong

  def limit(maxSize: scala.Long): LongStream

  def map(mapper: LongUnaryOperator): LongStream

  // Since: Java 16
  def mapMulti(mapper: LongStream.LongMapMultiConsumer): LongStream =
    flatMap { (e: Long) =>
      val buffer = LongStream.builder()
      mapper.accept(e, buffer)
      buffer.build()
    }

  def mapToDouble(mapper: LongToDoubleFunction): DoubleStream

  def mapToInt(mapper: LongToIntFunction): IntStream
  def mapToObj[U](mapper: LongFunction[? <: U]): Stream[U]

  def max(): OptionalLong

  def min(): OptionalLong

  def noneMatch(pred: LongPredicate): Boolean

  def peek(action: LongConsumer): LongStream

  def reduce(identity: scala.Long, op: LongBinaryOperator): scala.Long

  def reduce(op: LongBinaryOperator): OptionalLong

  def skip(n: scala.Long): LongStream

  def sorted(): LongStream

  override def spliterator(): Spliterator.OfLong

  def sum(): scala.Long

  def summaryStatistics(): LongSummaryStatistics

  // Since: Java 9
  def takeWhile(pred: LongPredicate): LongStream = {
    Objects.requireNonNull(pred)
    boxed().takeWhile(i => pred.test(i)).mapToLong(i => i)
  }

  def toArray(): Array[scala.Long]

}

object LongStream {

  trait Builder extends LongConsumer {
    def accept(t: scala.Long): Unit
    def add(t: scala.Long): LongStream.Builder = {
      accept(t)
      this
    }
    def build(): LongStream
  }

  @FunctionalInterface
  trait LongMapMultiConsumer {
    def accept(value: scala.Long, lc: LongConsumer): Unit
  }

  def builder(): LongStream.Builder = new Streams.LongBuilder

  def concat(a: LongStream, b: LongStream): LongStream = Streams.concat(a, b)

  def empty(): LongStream =
    StreamSupport.longStream(Spliterators.emptyLongSpliterator(), false)

  def generate(s: LongSupplier): LongStream = {
    Objects.requireNonNull(s)
    StreamSupport.longStream(new Streams.LongGenerate(s), false)
  }

  // Since: Java 9
  def iterate(
      seed: scala.Long,
      hasNext: LongPredicate,
      next: LongUnaryOperator
  ): LongStream = {
    Objects.requireNonNull(next)
    Objects.requireNonNull(hasNext)
    StreamSupport.longStream(new Streams.LongIterate(seed, hasNext, next), false)
  }

  def iterate(seed: scala.Long, f: LongUnaryOperator): LongStream = {
    Objects.requireNonNull(f)
    StreamSupport.longStream(new Streams.LongIterate(seed, null, f), false)
  }

  def of(values: Array[Long]): LongStream = Arrays.stream(values)

  def of(t: Long): LongStream = Arrays.stream(Array(t))

  /* A range with more than Long.MaxValue elements is the concatenation of two halves, as on the
   * JDK, so that its size fits.
   */

  def range(startInclusive: Long, endExclusive: Long): LongStream =
    if (startInclusive >= endExclusive) empty()
    else if (endExclusive - startInclusive < 0) {
      val m = startInclusive + ((endExclusive - startInclusive) >>> 1) + 1
      concat(range(startInclusive, m), range(m, endExclusive))
    } else StreamSupport.longStream(new Streams.LongRange(startInclusive, endExclusive, closed = false), false)

  def rangeClosed(startInclusive: Long, endInclusive: Long): LongStream =
    if (startInclusive > endInclusive) empty()
    else if (endInclusive - startInclusive + 1 <= 0) {
      val m = startInclusive + ((endInclusive - startInclusive) >>> 1) + 1
      concat(range(startInclusive, m), rangeClosed(m, endInclusive))
    } else StreamSupport.longStream(new Streams.LongRange(startInclusive, endInclusive, closed = true), false)

}
