// Adapted from Scala Native's javalib (Apache-2.0, see javalib/LICENSE-scala-native.md).
package java.util.stream

import java.util.Spliterator
import java.util.function.Supplier

object StreamSupport {

  def doubleStream(
      spliterator: Spliterator.OfDouble,
      parallel: Boolean
  ): DoubleStream =
    DoublePipeline.head(spliterator, null, AbstractPipeline.sourceFlags(spliterator), parallel)

  def doubleStream(
      supplier: Supplier[? <: Spliterator.OfDouble],
      characteristics: Int,
      parallel: Boolean
  ): DoubleStream =
    DoublePipeline.head(null, supplier, AbstractPipeline.sourceFlags(characteristics, null), parallel)

  def intStream(
      spliterator: Spliterator.OfInt,
      parallel: Boolean
  ): IntStream =
    IntPipeline.head(spliterator, null, AbstractPipeline.sourceFlags(spliterator), parallel)

  def intStream(
      supplier: Supplier[? <: Spliterator.OfInt],
      characteristics: Int,
      parallel: Boolean
  ): IntStream =
    IntPipeline.head(null, supplier, AbstractPipeline.sourceFlags(characteristics, null), parallel)

  def longStream(
      spliterator: Spliterator.OfLong,
      parallel: Boolean
  ): LongStream =
    LongPipeline.head(spliterator, null, AbstractPipeline.sourceFlags(spliterator), parallel)

  def longStream(
      supplier: Supplier[? <: Spliterator.OfLong],
      characteristics: Int,
      parallel: Boolean
  ): LongStream =
    LongPipeline.head(null, supplier, AbstractPipeline.sourceFlags(characteristics, null), parallel)

  def stream[T](
      spliterator: Spliterator[T],
      parallel: Boolean
  ): Stream[T] =
    ReferencePipeline.head[T](spliterator, null, AbstractPipeline.sourceFlags(spliterator), parallel)

  def stream[T](
      supplier: Supplier[? <: Spliterator[T]],
      characteristics: Int,
      parallel: Boolean
  ): Stream[T] =
    ReferencePipeline.head[T](null, supplier, AbstractPipeline.sourceFlags(characteristics, null), parallel)

}
