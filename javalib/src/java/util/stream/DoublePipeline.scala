package java.util.stream

import java.util.{DoubleSummaryStatistics, Objects, OptionalDouble, PrimitiveIterator, Spliterator, Spliterators}
import java.util.function._

import AbstractPipeline._
import Ops._

/** A stage of a pipeline of doubles: `DoubleStream`'s implementation (see [[AbstractPipeline]]). Doubles
 *  are never boxed, except by `boxed`, `distinct` and `iterator().next()`.
 */
private[stream] class DoublePipeline(previous: AbstractPipeline, setFlags: Int, clearFlags: Int, wrap: Sink => Sink)
    extends AbstractPipeline(previous, setFlags, clearFlags, wrap), DoubleStream:

  private[stream] def pushAll(spl: Spliterator[?], sink: Sink): Unit =
    spl.asInstanceOf[Spliterator.OfDouble].forEachRemaining(sink: DoubleConsumer)

  private[stream] def pushOne(spl: Spliterator[?], sink: Sink): Boolean =
    spl.asInstanceOf[Spliterator.OfDouble].tryAdvance(sink: DoubleConsumer)

  private def op(setFlags: Int, clearFlags: Int)(wrap: Sink => Sink): DoubleStream =
    new DoublePipeline(this, setFlags, clearFlags, wrap)

  // ---- BaseStream -------------------------------------------------------------------------------

  def iterator(): PrimitiveIterator.OfDouble = Spliterators.iterator(spliterator())

  def spliterator(): Spliterator.OfDouble =
    val source = consume()
    if previous == null then source.asInstanceOf[Spliterator.OfDouble]
    else StreamSpliterators.wrapDouble(this, source)

  def onClose(closeHandler: Runnable): DoubleStream =
    addCloseHandler(closeHandler)
    this

  def parallel(): DoubleStream =
    setParallel(true)
    this

  def sequential(): DoubleStream =
    setParallel(false)
    this

  def unordered(): DoubleStream =
    if !isOrdered then this else op(0, Spliterator.ORDERED)(down => down)

  // ---- Stateless operations ---------------------------------------------------------------------

  def filter(pred: DoublePredicate): DoubleStream =
    Objects.requireNonNull(pred)
    op(0, Spliterator.SIZED)(down =>
      new Sink.Chained(down):
        override def begin(size: Long): Unit = down.begin(-1)
        override def accept(v: Double): Unit = if pred.test(v) then down.accept(v)
    )

  def map(mapper: DoubleUnaryOperator): DoubleStream =
    Objects.requireNonNull(mapper)
    op(0, NotSortedOrDistinct)(down =>
      new Sink.Chained(down):
        override def accept(v: Double): Unit = down.accept(mapper.applyAsDouble(v))
    )

  def mapToObj[U](mapper: DoubleFunction[? <: U]): Stream[U] =
    Objects.requireNonNull(mapper)
    mapToObj(mapper, NotSortedOrDistinct)

  private def mapToObj[U](mapper: DoubleFunction[? <: U], clearFlags: Int): Stream[U] =
    new ReferencePipeline[U](this, 0, clearFlags, down =>
      new Sink.Chained(down):
        override def accept(v: Double): Unit = down.accept(mapper.apply(v): Any)
    )

  def boxed(): Stream[java.lang.Double] = mapToObj[java.lang.Double](v => java.lang.Double.valueOf(v), 0)

  def mapToInt(mapper: DoubleToIntFunction): IntStream =
    Objects.requireNonNull(mapper)
    new IntPipeline(this, 0, NotSortedOrDistinct, down =>
      new Sink.Chained(down):
        override def accept(v: Double): Unit = down.accept(mapper.applyAsInt(v))
    )

  def mapToLong(mapper: DoubleToLongFunction): LongStream =
    Objects.requireNonNull(mapper)
    new LongPipeline(this, 0, NotSortedOrDistinct, down =>
      new Sink.Chained(down):
        override def accept(v: Double): Unit = down.accept(mapper.applyAsLong(v))
    )

  def flatMap(mapper: DoubleFunction[? <: DoubleStream]): DoubleStream =
    Objects.requireNonNull(mapper)
    op(0, NotSortedDistinctOrSized)(down =>
      new FlatMapSink(down, this):
        override def accept(v: Double): Unit = push(mapper.apply(v))
    )

  override def mapMulti(mapper: DoubleStream.DoubleMapMultiConsumer): DoubleStream =
    Objects.requireNonNull(mapper)
    op(0, NotSortedDistinctOrSized)(down =>
      new Sink.Chained(down):
        override def begin(size: Long): Unit = down.begin(-1)
        override def accept(v: Double): Unit = mapper.accept(v, down)
    )

  def peek(action: DoubleConsumer): DoubleStream =
    Objects.requireNonNull(action)
    op(0, 0)(down =>
      new Sink.Chained(down):
        override def accept(v: Double): Unit =
          action.accept(v)
          down.accept(v)
    )

  override def takeWhile(pred: DoublePredicate): DoubleStream =
    Objects.requireNonNull(pred)
    op(ShortCircuit, Spliterator.SIZED)(down =>
      new Sink.Chained(down):
        private var taking = true
        override def begin(size: Long): Unit = down.begin(-1)
        override def accept(v: Double): Unit =
          if taking then
            taking = pred.test(v)
            if taking then down.accept(v)
        override def cancellationRequested(): Boolean = !taking || down.cancellationRequested()
    )

  override def dropWhile(pred: DoublePredicate): DoubleStream =
    Objects.requireNonNull(pred)
    op(0, Spliterator.SIZED)(down =>
      new Sink.Chained(down):
        private var taking = false
        override def begin(size: Long): Unit = down.begin(-1)
        override def accept(v: Double): Unit =
          if !taking then taking = !pred.test(v)
          if taking then down.accept(v)
    )

  // ---- Stateful operations ----------------------------------------------------------------------

  /** As the JDK's (`boxed().distinct().mapToDouble(i -> i)`): the elements are boxed into a set. */
  def distinct(): DoubleStream =
    val input = flags
    op(0, NotSortedDistinctOrSized)(down =>
      if (input & Spliterator.DISTINCT) != 0 then down else new DistinctSink(down)
    )

  def sorted(): DoubleStream =
    val input = flags
    op(Spliterator.ORDERED | Spliterator.SORTED, 0)(down =>
      if (input & Spliterator.SORTED) != 0 then down else new DoubleSortingSink(down)
    )

  def limit(maxSize: Long): DoubleStream =
    checkCount(maxSize)
    slice(0, maxSize)

  def skip(n: Long): DoubleStream =
    checkCount(n)
    if n == 0 then this else slice(n, -1)

  private def slice(toSkip: Long, maxSize: Long): DoubleStream =
    new DoublePipeline(this, sliceFlags(maxSize), 0, down =>
      new SliceSink(down, toSkip, maxSize):
        override def accept(v: Double): Unit = if take() then down.accept(v)
    ):
      override private[stream] def exactOutputSize(previousSize: Long): Long = sliceSize(previousSize, toSkip, maxSize)

  // ---- Terminal operations ----------------------------------------------------------------------

  def forEach(action: DoubleConsumer): Unit =
    Objects.requireNonNull(action)
    evaluate(new Sink.OfDoubleConsumer(action))

  def forEachOrdered(action: DoubleConsumer): Unit = forEach(action)

  private def matchSink(pred: DoublePredicate, stopWhen: Boolean): MatchSink =
    Objects.requireNonNull(pred)
    evaluateShortCircuit(new MatchSink(stopWhen):
      override def accept(v: Double): Unit = if !stopped then record(pred.test(v))
    )

  def anyMatch(pred: DoublePredicate): Boolean = matchSink(pred, stopWhen = true).stopped

  def allMatch(pred: DoublePredicate): Boolean = !matchSink(pred, stopWhen = false).stopped

  def noneMatch(pred: DoublePredicate): Boolean = !matchSink(pred, stopWhen = true).stopped

  def findFirst(): OptionalDouble =
    val sink = evaluateShortCircuit(new FindSink)
    if sink.found then OptionalDouble.of(sink.double) else OptionalDouble.empty()

  def findAny(): OptionalDouble = findFirst()

  def reduce(identity: Double, op: DoubleBinaryOperator): Double =
    Objects.requireNonNull(op)
    evaluate(new Accumulator[Double]:
      var result = identity
      override def accept(v: Double): Unit = result = op.applyAsDouble(result, v)
    ).result

  def reduce(op: DoubleBinaryOperator): OptionalDouble =
    Objects.requireNonNull(op)
    val sink = evaluate(new Accumulator[Double]:
      var result = 0.0
      override def accept(v: Double): Unit =
        result = if seen == 0 then v else op.applyAsDouble(result, v)
        seen = 1
    )
    if sink.seen == 0 then OptionalDouble.empty() else OptionalDouble.of(sink.result)

  def collect[R](supplier: Supplier[R], accumulator: ObjDoubleConsumer[R], combiner: BiConsumer[R, R]): R =
    Objects.requireNonNull(supplier)
    Objects.requireNonNull(accumulator)
    Objects.requireNonNull(combiner)
    evaluate(new Accumulator[R]:
      var result: R = null.asInstanceOf[R]
      override def begin(size: Long): Unit = result = supplier.get()
      override def accept(v: Double): Unit = accumulator.accept(result, v)
    ).result

  /** Compensated (Kahan) summation, as the JDK's. */
  def sum(): Double =
    evaluate(new Accumulator[Double]:
      private val sum = new DoubleSum
      override def accept(v: Double): Unit = sum.add(v)
      def result: Double = sum.result
    ).result

  def min(): OptionalDouble = reduce((a, b) => Math.min(a, b))

  def max(): OptionalDouble = reduce((a, b) => Math.max(a, b))

  def count(): Long = evaluateCount()

  def average(): OptionalDouble =
    val sum = evaluate(new Accumulator[DoubleSum]:
      val result = new DoubleSum
      override def accept(v: Double): Unit = result.add(v)
    ).result
    if sum.count > 0 then OptionalDouble.of(sum.result / sum.count) else OptionalDouble.empty()

  def summaryStatistics(): DoubleSummaryStatistics =
    evaluate(new Accumulator[DoubleSummaryStatistics]:
      val result = new DoubleSummaryStatistics
      override def accept(v: Double): Unit = result.accept(v)
    ).result

  def toArray(): Array[Double] =
    val spl = consume()
    val buffer = new DoubleBuffer
    val size = exactOutputSizeIfKnown(spl)
    if size >= 0 then
      checkArraySize(size)
      buffer.presize(size)
    copyInto(spl, buffer, shortCircuit = false)
    buffer.toArray()

private[stream] object DoublePipeline:
  def head(spl: Spliterator[?], supplier: Supplier[? <: Spliterator[?]], flags: Int, parallel: Boolean): DoubleStream =
    new DoublePipeline(null, flags, 0, null).initSource(spl, supplier, parallel)
