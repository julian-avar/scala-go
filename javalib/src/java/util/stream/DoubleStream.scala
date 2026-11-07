// Adapted from Scala Native's javalib (Apache-2.0, see javalib/LICENSE-scala-native.md).
package java.util.stream

import java.util._
import java.util.function._
import java.{lang => jl}

/* Design Note:
 *
 * DoubleStream extends BaseStream[jl.Double, DoubleStream]
 * in correspondence to the documentation & usage of Spliterator.Of*
 * and PrimitiveIterator.Of*. That is, the first type is a Java container.
 *
 * scala-go: the operations are implemented by DoublePipeline. The default methods here (Java's
 * defaults) only serve other implementations of DoubleStream.
 */

trait DoubleStream extends BaseStream[jl.Double, DoubleStream] {

  def allMatch(pred: DoublePredicate): Boolean

  def anyMatch(pred: DoublePredicate): Boolean

  def average(): OptionalDouble

  def boxed(): Stream[jl.Double]

  def collect[R](
      supplier: Supplier[R],
      accumulator: ObjDoubleConsumer[R],
      combiner: BiConsumer[R, R]
  ): R

  def count(): scala.Long

  def distinct(): DoubleStream

  // Since: Java 9
  def dropWhile(pred: DoublePredicate): DoubleStream = {
    Objects.requireNonNull(pred)
    boxed().dropWhile(i => pred.test(i)).mapToDouble(i => i)
  }

  def filter(pred: DoublePredicate): DoubleStream

  def findAny(): OptionalDouble

  def findFirst(): OptionalDouble

  def flatMap(mapper: DoubleFunction[? <: DoubleStream]): DoubleStream

  def forEach(action: DoubleConsumer): Unit

  def forEachOrdered(action: DoubleConsumer): Unit

  override def iterator(): PrimitiveIterator.OfDouble

  def limit(maxSize: scala.Long): DoubleStream

  def map(mapper: DoubleUnaryOperator): DoubleStream

  // Since: Java 16
  def mapMulti(mapper: DoubleStream.DoubleMapMultiConsumer): DoubleStream =
    flatMap { (e: Double) =>
      val buffer = DoubleStream.builder()
      mapper.accept(e, buffer)
      buffer.build()
    }

  def mapToInt(mapper: DoubleToIntFunction): IntStream

  def mapToLong(mapper: DoubleToLongFunction): LongStream

  def mapToObj[U](mapper: DoubleFunction[? <: U]): Stream[U]

  def max(): OptionalDouble

  def min(): OptionalDouble

  def noneMatch(pred: DoublePredicate): Boolean

  def peek(action: DoubleConsumer): DoubleStream

  def reduce(identity: scala.Double, op: DoubleBinaryOperator): scala.Double

  def reduce(op: DoubleBinaryOperator): OptionalDouble

  def skip(n: scala.Long): DoubleStream

  def sorted(): DoubleStream

  override def spliterator(): Spliterator.OfDouble

  def sum(): scala.Double

  def summaryStatistics(): DoubleSummaryStatistics

  // Since: Java 9
  def takeWhile(pred: DoublePredicate): DoubleStream = {
    Objects.requireNonNull(pred)
    boxed().takeWhile(i => pred.test(i)).mapToDouble(i => i)
  }

  def toArray(): Array[scala.Double]

}

object DoubleStream {

  trait Builder extends DoubleConsumer {
    def accept(t: scala.Double): Unit
    def add(t: scala.Double): DoubleStream.Builder = {
      accept(t)
      this
    }
    def build(): DoubleStream
  }

  @FunctionalInterface
  trait DoubleMapMultiConsumer {
    def accept(value: scala.Double, dc: DoubleConsumer): Unit
  }

  def builder(): DoubleStream.Builder = new Streams.DoubleBuilder

  def concat(a: DoubleStream, b: DoubleStream): DoubleStream = Streams.concat(a, b)

  def empty(): DoubleStream =
    StreamSupport.doubleStream(Spliterators.emptyDoubleSpliterator(), false)

  def generate(s: DoubleSupplier): DoubleStream = {
    Objects.requireNonNull(s)
    StreamSupport.doubleStream(new Streams.DoubleGenerate(s), false)
  }

  // Since: Java 9
  def iterate(
      seed: scala.Double,
      hasNext: DoublePredicate,
      next: DoubleUnaryOperator
  ): DoubleStream = {
    Objects.requireNonNull(next)
    Objects.requireNonNull(hasNext)
    StreamSupport.doubleStream(new Streams.DoubleIterate(seed, hasNext, next), false)
  }

  def iterate(seed: scala.Double, f: DoubleUnaryOperator): DoubleStream = {
    Objects.requireNonNull(f)
    StreamSupport.doubleStream(new Streams.DoubleIterate(seed, null, f), false)
  }

  def of(values: Array[Double]): DoubleStream = Arrays.stream(values)

  def of(t: Double): DoubleStream = Arrays.stream(Array(t))

}
