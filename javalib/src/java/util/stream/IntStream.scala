// Adapted from Scala Native's javalib (Apache-2.0, see javalib/LICENSE-scala-native.md).
package java.util.stream

import java.util._
import java.util.function._
import java.{lang => jl}

/* Design Note:
 *
 * IntStream extends BaseStream[jl.Int, IntStream]
 * in correspondence to the documentation & usage of Spliterator.Of*
 * and PrimitiveIterator.Of*. That is, the first type is a Java container.
 *
 * scala-go: the operations are implemented by IntPipeline. The default methods here (Java's
 * defaults) only serve other implementations of IntStream.
 */

trait IntStream extends BaseStream[jl.Integer, IntStream] {

  def allMatch(pred: IntPredicate): Boolean

  def anyMatch(pred: IntPredicate): Boolean

  def asDoubleStream(): DoubleStream

  def asLongStream(): LongStream

  def average(): OptionalDouble

  def boxed(): Stream[jl.Integer]

  def collect[R](
      supplier: Supplier[R],
      accumulator: ObjIntConsumer[R],
      combiner: BiConsumer[R, R]
  ): R

  def count(): scala.Long

  def distinct(): IntStream

  // Since: Java 9
  def dropWhile(pred: IntPredicate): IntStream = {
    Objects.requireNonNull(pred)
    boxed().dropWhile(i => pred.test(i)).mapToInt(i => i)
  }

  def filter(pred: IntPredicate): IntStream

  def findAny(): OptionalInt

  def findFirst(): OptionalInt

  def flatMap(mapper: IntFunction[? <: IntStream]): IntStream

  def forEach(action: IntConsumer): Unit

  def forEachOrdered(action: IntConsumer): Unit

  override def iterator(): PrimitiveIterator.OfInt

  def limit(maxSize: scala.Long): IntStream

  def map(mapper: IntUnaryOperator): IntStream

  // Since: Java 16
  def mapMulti(mapper: IntStream.IntMapMultiConsumer): IntStream =
    flatMap { (e: Int) =>
      val buffer = IntStream.builder()
      mapper.accept(e, buffer)
      buffer.build()
    }

  def mapToDouble(mapper: IntToDoubleFunction): DoubleStream

  def mapToLong(mapper: IntToLongFunction): LongStream

  def mapToObj[U](mapper: IntFunction[? <: U]): Stream[U]

  def max(): OptionalInt

  def min(): OptionalInt

  def noneMatch(pred: IntPredicate): Boolean

  def peek(action: IntConsumer): IntStream

  def reduce(identity: scala.Int, op: IntBinaryOperator): scala.Int

  def reduce(op: IntBinaryOperator): OptionalInt

  def skip(n: scala.Long): IntStream

  def sorted(): IntStream

  override def spliterator(): Spliterator.OfInt

  def sum(): scala.Int

  def summaryStatistics(): IntSummaryStatistics

  // Since: Java 9
  def takeWhile(pred: IntPredicate): IntStream = {
    Objects.requireNonNull(pred)
    boxed().takeWhile(i => pred.test(i)).mapToInt(i => i)
  }

  def toArray(): Array[scala.Int]

}

object IntStream {

  trait Builder extends IntConsumer {
    def accept(t: scala.Int): Unit
    def add(t: scala.Int): IntStream.Builder = {
      accept(t)
      this
    }
    def build(): IntStream
  }

  @FunctionalInterface
  trait IntMapMultiConsumer {
    def accept(value: scala.Int, ic: IntConsumer): Unit
  }

  def builder(): IntStream.Builder = new Streams.IntBuilder

  def concat(a: IntStream, b: IntStream): IntStream = Streams.concat(a, b)

  def empty(): IntStream =
    StreamSupport.intStream(Spliterators.emptyIntSpliterator(), false)

  def generate(s: IntSupplier): IntStream = {
    Objects.requireNonNull(s)
    StreamSupport.intStream(new Streams.IntGenerate(s), false)
  }

  // Since: Java 9
  def iterate(
      seed: scala.Int,
      hasNext: IntPredicate,
      next: IntUnaryOperator
  ): IntStream = {
    Objects.requireNonNull(next)
    Objects.requireNonNull(hasNext)
    StreamSupport.intStream(new Streams.IntIterate(seed, hasNext, next), false)
  }

  def iterate(seed: scala.Int, f: IntUnaryOperator): IntStream = {
    Objects.requireNonNull(f)
    StreamSupport.intStream(new Streams.IntIterate(seed, null, f), false)
  }

  def of(values: Array[Int]): IntStream = Arrays.stream(values)

  def of(t: Int): IntStream = Arrays.stream(Array(t))

  def range(startInclusive: Int, endExclusive: Int): IntStream =
    if (startInclusive >= endExclusive) empty()
    else StreamSupport.intStream(new Streams.IntRange(startInclusive, endExclusive, closed = false), false)

  def rangeClosed(startInclusive: Int, endInclusive: Int): IntStream =
    if (startInclusive > endInclusive) empty()
    else StreamSupport.intStream(new Streams.IntRange(startInclusive, endInclusive, closed = true), false)

}
