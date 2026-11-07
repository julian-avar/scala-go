package java.util.stream

import java.util.{ArrayList, Arrays, Comparator, Objects, Spliterator, Spliterators}
import java.util.function._

/** Sources of the streams the stream interfaces create: builders, ranges, `generate`, `iterate`
 *  and `concat`.
 */
private[stream] object Streams:

  // ---- Builders ---------------------------------------------------------------------------------

  private def builtException() = new IllegalStateException()

  final class RefBuilder[T] extends Stream.Builder[T]:
    private var buffer = new ArrayList[T]()
    def accept(t: T): Unit =
      if buffer == null then throw builtException()
      buffer.add(t)
    def build(): Stream[T] =
      if buffer == null then throw builtException()
      val elems = buffer
      buffer = null
      elems.stream()

  final class IntBuilder extends IntStream.Builder:
    private var elems = new Array[Int](8)
    private var count = 0
    def accept(t: Int): Unit =
      if count < 0 then throw builtException()
      if count == elems.length then elems = Arrays.copyOf(elems, count * 2)
      elems(count) = t
      count += 1
    def build(): IntStream =
      if count < 0 then throw builtException()
      val n = count
      count = -1
      Arrays.stream(elems, 0, n)

  final class LongBuilder extends LongStream.Builder:
    private var elems = new Array[Long](8)
    private var count = 0
    def accept(t: Long): Unit =
      if count < 0 then throw builtException()
      if count == elems.length then elems = Arrays.copyOf(elems, count * 2)
      elems(count) = t
      count += 1
    def build(): LongStream =
      if count < 0 then throw builtException()
      val n = count
      count = -1
      Arrays.stream(elems, 0, n)

  final class DoubleBuilder extends DoubleStream.Builder:
    private var elems = new Array[Double](8)
    private var count = 0
    def accept(t: Double): Unit =
      if count < 0 then throw builtException()
      if count == elems.length then elems = Arrays.copyOf(elems, count * 2)
      elems(count) = t
      count += 1
    def build(): DoubleStream =
      if count < 0 then throw builtException()
      val n = count
      count = -1
      Arrays.stream(elems, 0, n)

  // ---- Ranges -----------------------------------------------------------------------------------

  private final val RangeCharacteristics =
    Spliterator.ORDERED | Spliterator.SIZED | Spliterator.SUBSIZED | Spliterator.IMMUTABLE |
      Spliterator.NONNULL | Spliterator.DISTINCT | Spliterator.SORTED

  /** `from` until `upTo`, and `upTo` itself if `closed` (as long as `last` is 1). */
  final class IntRange(private var from: Int, upTo: Int, closed: Boolean) extends Spliterator.OfInt:
    private var last = if closed then 1 else 0

    def tryAdvance(action: IntConsumer): Boolean =
      Objects.requireNonNull(action)
      val i = from
      if i < upTo then
        from += 1
        action.accept(i)
        true
      else if last > 0 then
        last = 0
        action.accept(i)
        true
      else false

    override def forEachRemaining(action: IntConsumer): Unit =
      Objects.requireNonNull(action)
      var i = from
      val end = upTo
      val withLast = last > 0
      from = upTo
      last = 0
      while i < end do
        action.accept(i)
        i += 1
      if withLast then action.accept(i)

    def estimateSize(): Long = upTo.toLong - from + last
    def characteristics(): Int = RangeCharacteristics
    override def getComparator(): Comparator[? >: Integer] = null
    def trySplit(): Spliterator.OfInt = null

  final class LongRange(private var from: Long, upTo: Long, closed: Boolean) extends Spliterator.OfLong:
    private var last = if closed then 1 else 0

    def tryAdvance(action: LongConsumer): Boolean =
      Objects.requireNonNull(action)
      val i = from
      if i < upTo then
        from += 1
        action.accept(i)
        true
      else if last > 0 then
        last = 0
        action.accept(i)
        true
      else false

    override def forEachRemaining(action: LongConsumer): Unit =
      Objects.requireNonNull(action)
      var i = from
      val end = upTo
      val withLast = last > 0
      from = upTo
      last = 0
      while i < end do
        action.accept(i)
        i += 1
      if withLast then action.accept(i)

    def estimateSize(): Long = upTo - from + last
    def characteristics(): Int = RangeCharacteristics
    override def getComparator(): Comparator[? >: java.lang.Long] = null
    def trySplit(): Spliterator.OfLong = null

  // ---- generate and iterate ---------------------------------------------------------------------

  final class Generate[T](s: Supplier[? <: T]) extends Spliterators.AbstractSpliterator[T](Long.MaxValue, Spliterator.IMMUTABLE):
    def tryAdvance(action: Consumer[? >: T]): Boolean =
      Objects.requireNonNull(action)
      action.accept(s.get())
      true

  final class IntGenerate(s: IntSupplier) extends Spliterators.AbstractIntSpliterator(Long.MaxValue, Spliterator.IMMUTABLE):
    def tryAdvance(action: IntConsumer): Boolean =
      Objects.requireNonNull(action)
      action.accept(s.getAsInt())
      true

  final class LongGenerate(s: LongSupplier) extends Spliterators.AbstractLongSpliterator(Long.MaxValue, Spliterator.IMMUTABLE):
    def tryAdvance(action: LongConsumer): Boolean =
      Objects.requireNonNull(action)
      action.accept(s.getAsLong())
      true

  final class DoubleGenerate(s: DoubleSupplier)
      extends Spliterators.AbstractDoubleSpliterator(Long.MaxValue, Spliterator.IMMUTABLE):
    def tryAdvance(action: DoubleConsumer): Boolean =
      Objects.requireNonNull(action)
      action.accept(s.getAsDouble())
      true

  private final val IterateCharacteristics = Spliterator.ORDERED | Spliterator.IMMUTABLE
  private final val PrimitiveIterateCharacteristics = IterateCharacteristics | Spliterator.NONNULL

  /** `seed`, then `next` applied to the previous element, while `hasNext` (if any) holds. `next`
   *  is applied only when the following element is needed, as on the JDK.
   */
  final class Iterate[T](seed: T, hasNext: Predicate[? >: T], next: UnaryOperator[T])
      extends Spliterators.AbstractSpliterator[T](Long.MaxValue, IterateCharacteristics):
    private var prev: T = null.asInstanceOf[T]
    private var started = false
    private var finished = false
    def tryAdvance(action: Consumer[? >: T]): Boolean =
      Objects.requireNonNull(action)
      if finished then false
      else
        val t =
          if started then next.apply(prev)
          else
            started = true
            seed
        if hasNext != null && !hasNext.test(t) then
          prev = null.asInstanceOf[T]
          finished = true
          false
        else
          prev = t
          action.accept(t)
          true

  final class IntIterate(seed: Int, hasNext: IntPredicate, next: IntUnaryOperator)
      extends Spliterators.AbstractIntSpliterator(Long.MaxValue, PrimitiveIterateCharacteristics):
    private var prev = 0
    private var started = false
    private var finished = false
    def tryAdvance(action: IntConsumer): Boolean =
      Objects.requireNonNull(action)
      if finished then false
      else
        val t =
          if started then next.applyAsInt(prev)
          else
            started = true
            seed
        if hasNext != null && !hasNext.test(t) then
          finished = true
          false
        else
          prev = t
          action.accept(t)
          true

  final class LongIterate(seed: Long, hasNext: LongPredicate, next: LongUnaryOperator)
      extends Spliterators.AbstractLongSpliterator(Long.MaxValue, PrimitiveIterateCharacteristics):
    private var prev = 0L
    private var started = false
    private var finished = false
    def tryAdvance(action: LongConsumer): Boolean =
      Objects.requireNonNull(action)
      if finished then false
      else
        val t =
          if started then next.applyAsLong(prev)
          else
            started = true
            seed
        if hasNext != null && !hasNext.test(t) then
          finished = true
          false
        else
          prev = t
          action.accept(t)
          true

  final class DoubleIterate(seed: Double, hasNext: DoublePredicate, next: DoubleUnaryOperator)
      extends Spliterators.AbstractDoubleSpliterator(Long.MaxValue, PrimitiveIterateCharacteristics):
    private var prev = 0.0
    private var started = false
    private var finished = false
    def tryAdvance(action: DoubleConsumer): Boolean =
      Objects.requireNonNull(action)
      if finished then false
      else
        val t =
          if started then next.applyAsDouble(prev)
          else
            started = true
            seed
        if hasNext != null && !hasNext.test(t) then
          finished = true
          false
        else
          prev = t
          action.accept(t)
          true

  // ---- concat -----------------------------------------------------------------------------------

  /** The elements of `a`, then those of `b`. */
  private abstract class Concat[T, S <: Spliterator[T]](protected val a: S, protected val b: S) extends Spliterator[T]:
    protected var inA = true
    private val unsized = a.estimateSize() + b.estimateSize() < 0

    def trySplit(): S = null.asInstanceOf[S]

    def estimateSize(): Long =
      if inA then
        val size = a.estimateSize() + b.estimateSize()
        if size >= 0 then size else Long.MaxValue
      else b.estimateSize()

    def characteristics(): Int =
      if inA then
        a.characteristics() & b.characteristics() &
          ~(Spliterator.DISTINCT | Spliterator.SORTED | (if unsized then Spliterator.SIZED | Spliterator.SUBSIZED else 0))
      else b.characteristics()

    override def getComparator(): Comparator[? >: T] =
      if inA then throw new IllegalStateException() else b.getComparator()

  private final class RefConcat[T](a: Spliterator[T], b: Spliterator[T]) extends Concat[T, Spliterator[T]](a, b):
    def tryAdvance(action: Consumer[? >: T]): Boolean =
      if inA then
        if a.tryAdvance(action) then true
        else
          inA = false
          b.tryAdvance(action)
      else b.tryAdvance(action)
    override def forEachRemaining(action: Consumer[? >: T]): Unit =
      if inA then a.forEachRemaining(action)
      b.forEachRemaining(action)

  private final class IntConcat(a: Spliterator.OfInt, b: Spliterator.OfInt)
      extends Concat[Integer, Spliterator.OfInt](a, b), Spliterator.OfInt:
    override def trySplit(): Spliterator.OfInt = null
    def tryAdvance(action: IntConsumer): Boolean =
      if inA then
        if a.tryAdvance(action) then true
        else
          inA = false
          b.tryAdvance(action)
      else b.tryAdvance(action)
    override def forEachRemaining(action: IntConsumer): Unit =
      if inA then a.forEachRemaining(action)
      b.forEachRemaining(action)

  private final class LongConcat(a: Spliterator.OfLong, b: Spliterator.OfLong)
      extends Concat[java.lang.Long, Spliterator.OfLong](a, b), Spliterator.OfLong:
    override def trySplit(): Spliterator.OfLong = null
    def tryAdvance(action: LongConsumer): Boolean =
      if inA then
        if a.tryAdvance(action) then true
        else
          inA = false
          b.tryAdvance(action)
      else b.tryAdvance(action)
    override def forEachRemaining(action: LongConsumer): Unit =
      if inA then a.forEachRemaining(action)
      b.forEachRemaining(action)

  private final class DoubleConcat(a: Spliterator.OfDouble, b: Spliterator.OfDouble)
      extends Concat[java.lang.Double, Spliterator.OfDouble](a, b), Spliterator.OfDouble:
    override def trySplit(): Spliterator.OfDouble = null
    def tryAdvance(action: DoubleConsumer): Boolean =
      if inA then
        if a.tryAdvance(action) then true
        else
          inA = false
          b.tryAdvance(action)
      else b.tryAdvance(action)
    override def forEachRemaining(action: DoubleConsumer): Unit =
      if inA then a.forEachRemaining(action)
      b.forEachRemaining(action)

  def concat[T](a: Stream[? <: T], b: Stream[? <: T]): Stream[T] =
    Objects.requireNonNull(a)
    Objects.requireNonNull(b)
    val spl = new RefConcat[T](a.spliterator().asInstanceOf[Spliterator[T]], b.spliterator().asInstanceOf[Spliterator[T]])
    StreamSupport.stream(spl, a.isParallel() || b.isParallel()).onClose(AbstractPipeline.composedClose(a, b))

  def concat(a: IntStream, b: IntStream): IntStream =
    Objects.requireNonNull(a)
    Objects.requireNonNull(b)
    val spl = new IntConcat(a.spliterator(), b.spliterator())
    StreamSupport.intStream(spl, a.isParallel() || b.isParallel()).onClose(AbstractPipeline.composedClose(a, b))

  def concat(a: LongStream, b: LongStream): LongStream =
    Objects.requireNonNull(a)
    Objects.requireNonNull(b)
    val spl = new LongConcat(a.spliterator(), b.spliterator())
    StreamSupport.longStream(spl, a.isParallel() || b.isParallel()).onClose(AbstractPipeline.composedClose(a, b))

  def concat(a: DoubleStream, b: DoubleStream): DoubleStream =
    Objects.requireNonNull(a)
    Objects.requireNonNull(b)
    val spl = new DoubleConcat(a.spliterator(), b.spliterator())
    StreamSupport.doubleStream(spl, a.isParallel() || b.isParallel()).onClose(AbstractPipeline.composedClose(a, b))
