package java.util.stream

import java.util.{LongSummaryStatistics, Objects, OptionalDouble, OptionalLong, PrimitiveIterator, Spliterator, Spliterators}
import java.util.function._

import AbstractPipeline._
import Ops._

/** A stage of a pipeline of longs: `LongStream`'s implementation (see [[AbstractPipeline]]). Longs
 *  are never boxed, except by `boxed`, `distinct` and `iterator().next()`.
 */
private[stream] class LongPipeline(previous: AbstractPipeline, setFlags: Int, clearFlags: Int, wrap: Sink => Sink)
    extends AbstractPipeline(previous, setFlags, clearFlags, wrap), LongStream:

  private[stream] def pushAll(spl: Spliterator[?], sink: Sink): Unit =
    spl.asInstanceOf[Spliterator.OfLong].forEachRemaining(sink: LongConsumer)

  private[stream] def pushOne(spl: Spliterator[?], sink: Sink): Boolean =
    spl.asInstanceOf[Spliterator.OfLong].tryAdvance(sink: LongConsumer)

  private def op(setFlags: Int, clearFlags: Int)(wrap: Sink => Sink): LongStream =
    new LongPipeline(this, setFlags, clearFlags, wrap)

  // ---- BaseStream -------------------------------------------------------------------------------

  def iterator(): PrimitiveIterator.OfLong = Spliterators.iterator(spliterator())

  def spliterator(): Spliterator.OfLong =
    val source = consume()
    if previous == null then source.asInstanceOf[Spliterator.OfLong]
    else StreamSpliterators.wrapLong(this, source)

  def onClose(closeHandler: Runnable): LongStream =
    addCloseHandler(closeHandler)
    this

  def parallel(): LongStream =
    setParallel(true)
    this

  def sequential(): LongStream =
    setParallel(false)
    this

  def unordered(): LongStream =
    if !isOrdered then this else op(0, Spliterator.ORDERED)(down => down)

  // ---- Stateless operations ---------------------------------------------------------------------

  def filter(pred: LongPredicate): LongStream =
    Objects.requireNonNull(pred)
    op(0, Spliterator.SIZED)(down =>
      new Sink.Chained(down):
        override def begin(size: Long): Unit = down.begin(-1)
        override def accept(v: Long): Unit = if pred.test(v) then down.accept(v)
    )

  def map(mapper: LongUnaryOperator): LongStream =
    Objects.requireNonNull(mapper)
    op(0, NotSortedOrDistinct)(down =>
      new Sink.Chained(down):
        override def accept(v: Long): Unit = down.accept(mapper.applyAsLong(v))
    )

  def mapToObj[U](mapper: LongFunction[? <: U]): Stream[U] =
    Objects.requireNonNull(mapper)
    mapToObj(mapper, NotSortedOrDistinct)

  private def mapToObj[U](mapper: LongFunction[? <: U], clearFlags: Int): Stream[U] =
    new ReferencePipeline[U](this, 0, clearFlags, down =>
      new Sink.Chained(down):
        override def accept(v: Long): Unit = down.accept(mapper.apply(v): Any)
    )

  def boxed(): Stream[java.lang.Long] = mapToObj[java.lang.Long](v => java.lang.Long.valueOf(v), 0)

  def mapToInt(mapper: LongToIntFunction): IntStream =
    Objects.requireNonNull(mapper)
    new IntPipeline(this, 0, NotSortedOrDistinct, down =>
      new Sink.Chained(down):
        override def accept(v: Long): Unit = down.accept(mapper.applyAsInt(v))
    )

  def mapToDouble(mapper: LongToDoubleFunction): DoubleStream =
    Objects.requireNonNull(mapper)
    new DoublePipeline(this, 0, NotSortedOrDistinct, down =>
      new Sink.Chained(down):
        override def accept(v: Long): Unit = down.accept(mapper.applyAsDouble(v))
    )

  def asDoubleStream(): DoubleStream =
    new DoublePipeline(this, 0, 0, down =>
      new Sink.Chained(down):
        override def accept(v: Long): Unit = down.accept(v.toDouble)
    )

  def flatMap(mapper: LongFunction[? <: LongStream]): LongStream =
    Objects.requireNonNull(mapper)
    op(0, NotSortedDistinctOrSized)(down =>
      new FlatMapSink(down, this):
        override def accept(v: Long): Unit = push(mapper.apply(v))
    )

  override def mapMulti(mapper: LongStream.LongMapMultiConsumer): LongStream =
    Objects.requireNonNull(mapper)
    op(0, NotSortedDistinctOrSized)(down =>
      new Sink.Chained(down):
        override def begin(size: Long): Unit = down.begin(-1)
        override def accept(v: Long): Unit = mapper.accept(v, down)
    )

  def peek(action: LongConsumer): LongStream =
    Objects.requireNonNull(action)
    op(0, 0)(down =>
      new Sink.Chained(down):
        override def accept(v: Long): Unit =
          action.accept(v)
          down.accept(v)
    )

  override def takeWhile(pred: LongPredicate): LongStream =
    Objects.requireNonNull(pred)
    op(ShortCircuit, Spliterator.SIZED)(down =>
      new Sink.Chained(down):
        private var taking = true
        override def begin(size: Long): Unit = down.begin(-1)
        override def accept(v: Long): Unit =
          if taking then
            taking = pred.test(v)
            if taking then down.accept(v)
        override def cancellationRequested(): Boolean = !taking || down.cancellationRequested()
    )

  override def dropWhile(pred: LongPredicate): LongStream =
    Objects.requireNonNull(pred)
    op(0, Spliterator.SIZED)(down =>
      new Sink.Chained(down):
        private var taking = false
        override def begin(size: Long): Unit = down.begin(-1)
        override def accept(v: Long): Unit =
          if !taking then taking = !pred.test(v)
          if taking then down.accept(v)
    )

  // ---- Stateful operations ----------------------------------------------------------------------

  /** As the JDK's (`boxed().distinct().mapToLong(i -> i)`): the elements are boxed into a set. */
  def distinct(): LongStream =
    val input = flags
    op(0, NotSortedDistinctOrSized)(down =>
      if (input & Spliterator.DISTINCT) != 0 then down else new DistinctSink(down)
    )

  def sorted(): LongStream =
    val input = flags
    op(Spliterator.ORDERED | Spliterator.SORTED, 0)(down =>
      if (input & Spliterator.SORTED) != 0 then down else new LongSortingSink(down)
    )

  def limit(maxSize: Long): LongStream =
    checkCount(maxSize)
    slice(0, maxSize)

  def skip(n: Long): LongStream =
    checkCount(n)
    if n == 0 then this else slice(n, -1)

  private def slice(toSkip: Long, maxSize: Long): LongStream =
    new LongPipeline(this, sliceFlags(maxSize), 0, down =>
      new SliceSink(down, toSkip, maxSize):
        override def accept(v: Long): Unit = if take() then down.accept(v)
    ):
      override private[stream] def exactOutputSize(previousSize: Long): Long = sliceSize(previousSize, toSkip, maxSize)

  // ---- Terminal operations ----------------------------------------------------------------------

  def forEach(action: LongConsumer): Unit =
    Objects.requireNonNull(action)
    evaluate(new Sink.OfLongConsumer(action))

  def forEachOrdered(action: LongConsumer): Unit = forEach(action)

  private def matchSink(pred: LongPredicate, stopWhen: Boolean): MatchSink =
    Objects.requireNonNull(pred)
    evaluateShortCircuit(new MatchSink(stopWhen):
      override def accept(v: Long): Unit = if !stopped then record(pred.test(v))
    )

  def anyMatch(pred: LongPredicate): Boolean = matchSink(pred, stopWhen = true).stopped

  def allMatch(pred: LongPredicate): Boolean = !matchSink(pred, stopWhen = false).stopped

  def noneMatch(pred: LongPredicate): Boolean = !matchSink(pred, stopWhen = true).stopped

  def findFirst(): OptionalLong =
    val sink = evaluateShortCircuit(new FindSink)
    if sink.found then OptionalLong.of(sink.long) else OptionalLong.empty()

  def findAny(): OptionalLong = findFirst()

  def reduce(identity: Long, op: LongBinaryOperator): Long =
    Objects.requireNonNull(op)
    evaluate(new Accumulator[Long]:
      var result = identity
      override def accept(v: Long): Unit = result = op.applyAsLong(result, v)
    ).result

  def reduce(op: LongBinaryOperator): OptionalLong =
    Objects.requireNonNull(op)
    val sink = evaluate(new Accumulator[Long]:
      var result = 0L
      override def accept(v: Long): Unit =
        result = if seen == 0 then v else op.applyAsLong(result, v)
        seen = 1
    )
    if sink.seen == 0 then OptionalLong.empty() else OptionalLong.of(sink.result)

  def collect[R](supplier: Supplier[R], accumulator: ObjLongConsumer[R], combiner: BiConsumer[R, R]): R =
    Objects.requireNonNull(supplier)
    Objects.requireNonNull(accumulator)
    Objects.requireNonNull(combiner)
    evaluate(new Accumulator[R]:
      var result: R = null.asInstanceOf[R]
      override def begin(size: Long): Unit = result = supplier.get()
      override def accept(v: Long): Unit = accumulator.accept(result, v)
    ).result

  def sum(): Long =
    evaluate(new Accumulator[Long]:
      var result = 0L
      override def accept(v: Long): Unit = result += v
    ).result

  def min(): OptionalLong = reduce((a, b) => Math.min(a, b))

  def max(): OptionalLong = reduce((a, b) => Math.max(a, b))

  def count(): Long = evaluateCount()

  def average(): OptionalDouble =
    val sink = evaluate(new Accumulator[Long]:
      var result = 0L
      override def accept(v: Long): Unit =
        seen += 1
        result += v
    )
    if sink.seen > 0 then OptionalDouble.of(sink.result.toDouble / sink.seen) else OptionalDouble.empty()

  def summaryStatistics(): LongSummaryStatistics =
    evaluate(new Accumulator[LongSummaryStatistics]:
      val result = new LongSummaryStatistics
      override def accept(v: Long): Unit = result.accept(v)
    ).result

  def toArray(): Array[Long] =
    val spl = consume()
    val buffer = new LongBuffer
    val size = exactOutputSizeIfKnown(spl)
    if size >= 0 then
      checkArraySize(size)
      buffer.presize(size)
    copyInto(spl, buffer, shortCircuit = false)
    buffer.toArray()

private[stream] object LongPipeline:
  def head(spl: Spliterator[?], supplier: Supplier[? <: Spliterator[?]], flags: Int, parallel: Boolean): LongStream =
    new LongPipeline(null, flags, 0, null).initSource(spl, supplier, parallel)
