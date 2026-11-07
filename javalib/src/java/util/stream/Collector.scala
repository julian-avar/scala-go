// Adapted from Scala Native's javalib (Apache-2.0, see javalib/LICENSE-scala-native.md).
package java.util.stream

import java.util.function._
import java.util.{Collections, LinkedHashSet, Objects, Set}

import scala.annotation.static

trait Collector[T, A, R] {

  def accumulator(): BiConsumer[A, T]

  def characteristics(): Set[Collector.Characteristics]

  def combiner(): BinaryOperator[A]

  def finisher(): Function[A, R]

  def supplier(): Supplier[A]
}

object Collector {
  final class Characteristics private (name: String, ordinal: Int)
      extends scalago.javalib.lang.Enum[Characteristics](name, ordinal)

  object Characteristics {
    @static val CONCURRENT: Characteristics = new Characteristics("CONCURRENT", 0)
    @static val UNORDERED: Characteristics = new Characteristics("UNORDERED", 1)
    @static val IDENTITY_FINISH: Characteristics = new Characteristics("IDENTITY_FINISH", 2)

    @static def values(): Array[Characteristics] = Array(CONCURRENT, UNORDERED, IDENTITY_FINISH)

    @static def valueOf(name: String): Characteristics = {
      if (name == null) throw new NullPointerException("Name is null")
      values().find(_.name() == name).getOrElse {
        throw new IllegalArgumentException(
          "No enum constant java.util.stream.Collector.Characteristics." + name
        )
      }
    }
  }

  /** An unmodifiable set of characteristics, in the order of an EnumSet (by ordinal). */
  private[stream] def characteristicsSet(
      cs: Array[Characteristics],
      identityFinish: Boolean
  ): Set[Characteristics] = {
    val set = new LinkedHashSet[Characteristics]()
    val all = Characteristics.values()
    var i = 0
    while (i < all.length) {
      val c = all(i)
      if ((identityFinish && (c eq Characteristics.IDENTITY_FINISH)) || cs.contains(c)) set.add(c)
      i += 1
    }
    Collections.unmodifiableSet(set)
  }

  private[stream] val NoCharacteristics: Set[Characteristics] =
    characteristicsSet(new Array[Characteristics](0), identityFinish = false)
  private[stream] val IdentityFinish: Set[Characteristics] =
    characteristicsSet(new Array[Characteristics](0), identityFinish = true)

  /** A collector from its functions (Collectors' are too). */
  private[stream] final class Impl[T, A, R](
      _supplier: Supplier[A],
      _accumulator: BiConsumer[A, T],
      _combiner: BinaryOperator[A],
      _finisher: Function[A, R],
      _characteristics: Set[Characteristics]
  ) extends Collector[T, A, R] {
    def supplier(): Supplier[A] = _supplier
    def accumulator(): BiConsumer[A, T] = _accumulator
    def combiner(): BinaryOperator[A] = _combiner
    def finisher(): Function[A, R] = _finisher
    def characteristics(): Set[Characteristics] = _characteristics
  }

  private[stream] def castingIdentity[A, R](): Function[A, R] = (a: A) => a.asInstanceOf[R]

  def of[T, A, R](
      supplier: Supplier[A],
      accumulator: BiConsumer[A, T],
      combiner: BinaryOperator[A],
      finisher: Function[A, R],
      characteristics: Array[Characteristics]
  ): Collector[T, A, R] = {
    Objects.requireNonNull(supplier)
    Objects.requireNonNull(accumulator)
    Objects.requireNonNull(combiner)
    Objects.requireNonNull(finisher)
    Objects.requireNonNull(characteristics)
    val cs =
      if (characteristics.length == 0) NoCharacteristics
      else characteristicsSet(characteristics, identityFinish = false)
    new Impl[T, A, R](supplier, accumulator, combiner, finisher, cs)
  }

  def of[T, R](
      supplier: Supplier[R],
      accumulator: BiConsumer[R, T],
      combiner: BinaryOperator[R],
      characteristics: Array[Characteristics]
  ): Collector[T, R, R] = {
    Objects.requireNonNull(supplier)
    Objects.requireNonNull(accumulator)
    Objects.requireNonNull(combiner)
    Objects.requireNonNull(characteristics)
    val cs =
      if (characteristics.length == 0) IdentityFinish
      else characteristicsSet(characteristics, identityFinish = true)
    new Impl[T, R, R](supplier, accumulator, combiner, castingIdentity[R, R](), cs)
  }
}
