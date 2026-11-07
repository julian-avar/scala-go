package java.util.stream

import java.util.{IntSummaryStatistics, Objects, OptionalDouble, OptionalInt, PrimitiveIterator, Spliterator, Spliterators}
import java.util.function._

import AbstractPipeline._
import Ops._

/** A stage of a pipeline of ints: `IntStream`'s implementation (see [[AbstractPipeline]]). Ints
 *  are never boxed, except by `boxed`, `distinct` and `iterator().next()`.
 */
private[stream] class IntPipeline(previous: AbstractPipeline, setFlags: Int, clearFlags: Int, wrap: Sink => Sink)
    extends AbstractPipeline(previous, setFlags, clearFlags, wrap), IntStream:

  private[stream] def pushAll(spl: Spliterator[?], sink: Sink): Unit =
    spl.asInstanceOf[Spliterator.OfInt].forEachRemaining(sink: IntConsumer)

  private[stream] def pushOne(spl: Spliterator[?], sink: Sink): Boolean =
    spl.asInstanceOf[Spliterator.OfInt].tryAdvance(sink: IntConsumer)

  private def op(setFlags: Int, clearFlags: Int)(wrap: Sink => Sink): IntStream =
    new IntPipeline(this, setFlags, clearFlags, wrap)

  // ---- BaseStream -------------------------------------------------------------------------------

  def iterator(): PrimitiveIterator.OfInt = Spliterators.iterator(spliterator())

  def spliterator(): Spliterator.OfInt =
    val source = consume()
    if previous == null then source.asInstanceOf[Spliterator.OfInt]
    else StreamSpliterators.wrapInt(this, source)

  def onClose(closeHandler: Runnable): IntStream =
    addCloseHandler(closeHandler)
    this

  def parallel(): IntStream =
    setParallel(true)
    this

  def sequential(): IntStream =
    setParallel(false)
    this

  def unordered(): IntStream =
    if !isOrdered then this else op(0, Spliterator.ORDERED)(down => down)

  // ---- Stateless operations ---------------------------------------------------------------------

  def filter(pred: IntPredicate): IntStream =
    Objects.requireNonNull(pred)
    op(0, Spliterator.SIZED)(down =>
      new Sink.Chained(down):
        override def begin(size: Long): Unit = down.begin(-1)
        override def accept(v: Int): Unit = if pred.test(v) then down.accept(v)
    )

  def map(mapper: IntUnaryOperator): IntStream =
    Objects.requireNonNull(mapper)
    op(0, NotSortedOrDistinct)(down =>
      new Sink.Chained(down):
        override def accept(v: Int): Unit = down.accept(mapper.applyAsInt(v))
    )

  def mapToObj[U](mapper: IntFunction[? <: U]): Stream[U] =
    Objects.requireNonNull(mapper)
    mapToObj(mapper, NotSortedOrDistinct)

  private def mapToObj[U](mapper: IntFunction[? <: U], clearFlags: Int): Stream[U] =
    new ReferencePipeline[U](this, 0, clearFlags, down =>
      new Sink.Chained(down):
        override def accept(v: Int): Unit = down.accept(mapper.apply(v): Any)
    )

  def boxed(): Stream[Integer] = mapToObj[Integer](v => Integer.valueOf(v), 0)

  def mapToLong(mapper: IntToLongFunction): LongStream =
    Objects.requireNonNull(mapper)
    new LongPipeline(this, 0, NotSortedOrDistinct, down =>
      new Sink.Chained(down):
        override def accept(v: Int): Unit = down.accept(mapper.applyAsLong(v))
    )

  def mapToDouble(mapper: IntToDoubleFunction): DoubleStream =
    Objects.requireNonNull(mapper)
    new DoublePipeline(this, 0, NotSortedOrDistinct, down =>
      new Sink.Chained(down):
        override def accept(v: Int): Unit = down.accept(mapper.applyAsDouble(v))
    )

  def asLongStream(): LongStream =
    new LongPipeline(this, 0, 0, down =>
      new Sink.Chained(down):
        override def accept(v: Int): Unit = down.accept(v.toLong)
    )

  def asDoubleStream(): DoubleStream =
    new DoublePipeline(this, 0, 0, down =>
      new Sink.Chained(down):
        override def accept(v: Int): Unit = down.accept(v.toDouble)
    )

  def flatMap(mapper: IntFunction[? <: IntStream]): IntStream =
    Objects.requireNonNull(mapper)
    op(0, NotSortedDistinctOrSized)(down =>
      new FlatMapSink(down, this):
        override def accept(v: Int): Unit = push(mapper.apply(v))
    )

  override def mapMulti(mapper: IntStream.IntMapMultiConsumer): IntStream =
    Objects.requireNonNull(mapper)
    op(0, NotSortedDistinctOrSized)(down =>
      new Sink.Chained(down):
        override def begin(size: Long): Unit = down.begin(-1)
        override def accept(v: Int): Unit = mapper.accept(v, down)
    )

  def peek(action: IntConsumer): IntStream =
    Objects.requireNonNull(action)
    op(0, 0)(down =>
      new Sink.Chained(down):
        override def accept(v: Int): Unit =
          action.accept(v)
          down.accept(v)
    )

  override def takeWhile(pred: IntPredicate): IntStream =
    Objects.requireNonNull(pred)
    op(ShortCircuit, Spliterator.SIZED)(down =>
      new Sink.Chained(down):
        private var taking = true
        override def begin(size: Long): Unit = down.begin(-1)
        override def accept(v: Int): Unit =
          if taking then
            taking = pred.test(v)
            if taking then down.accept(v)
        override def cancellationRequested(): Boolean = !taking || down.cancellationRequested()
    )

  override def dropWhile(pred: IntPredicate): IntStream =
    Objects.requireNonNull(pred)
    op(0, Spliterator.SIZED)(down =>
      new Sink.Chained(down):
        private var taking = false
        override def begin(size: Long): Unit = down.begin(-1)
        override def accept(v: Int): Unit =
          if !taking then taking = !pred.test(v)
          if taking then down.accept(v)
    )

  // ---- Stateful operations ----------------------------------------------------------------------

  /** As the JDK's (`boxed().distinct().mapToInt(i -> i)`): the elements are boxed into a set. */
  def distinct(): IntStream =
    val input = flags
    op(0, NotSortedDistinctOrSized)(down =>
      if (input & Spliterator.DISTINCT) != 0 then down else new DistinctSink(down)
    )

  def sorted(): IntStream =
    val input = flags
    op(Spliterator.ORDERED | Spliterator.SORTED, 0)(down =>
      if (input & Spliterator.SORTED) != 0 then down else new IntSortingSink(down)
    )

  def limit(maxSize: Long): IntStream =
    checkCount(maxSize)
    slice(0, maxSize)

  def skip(n: Long): IntStream =
    checkCount(n)
    if n == 0 then this else slice(n, -1)

  private def slice(toSkip: Long, maxSize: Long): IntStream =
    new IntPipeline(this, sliceFlags(maxSize), 0, down =>
      new SliceSink(down, toSkip, maxSize):
        override def accept(v: Int): Unit = if take() then down.accept(v)
    ):
      override private[stream] def exactOutputSize(previousSize: Long): Long = sliceSize(previousSize, toSkip, maxSize)

  // ---- Terminal operations ----------------------------------------------------------------------

  def forEach(action: IntConsumer): Unit =
    Objects.requireNonNull(action)
    evaluate(new Sink.OfIntConsumer(action))

  def forEachOrdered(action: IntConsumer): Unit = forEach(action)

  private def matchSink(pred: IntPredicate, stopWhen: Boolean): MatchSink =
    Objects.requireNonNull(pred)
    evaluateShortCircuit(new MatchSink(stopWhen):
      override def accept(v: Int): Unit = if !stopped then record(pred.test(v))
    )

  def anyMatch(pred: IntPredicate): Boolean = matchSink(pred, stopWhen = true).stopped

  def allMatch(pred: IntPredicate): Boolean = !matchSink(pred, stopWhen = false).stopped

  def noneMatch(pred: IntPredicate): Boolean = !matchSink(pred, stopWhen = true).stopped

  def findFirst(): OptionalInt =
    val sink = evaluateShortCircuit(new FindSink)
    if sink.found then OptionalInt.of(sink.int) else OptionalInt.empty()

  def findAny(): OptionalInt = findFirst()

  def reduce(identity: Int, op: IntBinaryOperator): Int =
    Objects.requireNonNull(op)
    evaluate(new Accumulator[Int]:
      var result = identity
      override def accept(v: Int): Unit = result = op.applyAsInt(result, v)
    ).result

  def reduce(op: IntBinaryOperator): OptionalInt =
    Objects.requireNonNull(op)
    val sink = evaluate(new Accumulator[Int]:
      var result = 0
      override def accept(v: Int): Unit =
        result = if seen == 0 then v else op.applyAsInt(result, v)
        seen = 1
    )
    if sink.seen == 0 then OptionalInt.empty() else OptionalInt.of(sink.result)

  def collect[R](supplier: Supplier[R], accumulator: ObjIntConsumer[R], combiner: BiConsumer[R, R]): R =
    Objects.requireNonNull(supplier)
    Objects.requireNonNull(accumulator)
    Objects.requireNonNull(combiner)
    evaluate(new Accumulator[R]:
      var result: R = null.asInstanceOf[R]
      override def begin(size: Long): Unit = result = supplier.get()
      override def accept(v: Int): Unit = accumulator.accept(result, v)
    ).result

  def sum(): Int =
    evaluate(new Accumulator[Int]:
      var result = 0
      override def accept(v: Int): Unit = result += v
    ).result

  def min(): OptionalInt = reduce((a, b) => Math.min(a, b))

  def max(): OptionalInt = reduce((a, b) => Math.max(a, b))

  def count(): Long = evaluateCount()

  def average(): OptionalDouble =
    val sink = evaluate(new Accumulator[Long]:
      var result = 0L
      override def accept(v: Int): Unit =
        seen += 1
        result += v
    )
    if sink.seen > 0 then OptionalDouble.of(sink.result.toDouble / sink.seen) else OptionalDouble.empty()

  def summaryStatistics(): IntSummaryStatistics =
    evaluate(new Accumulator[IntSummaryStatistics]:
      val result = new IntSummaryStatistics
      override def accept(v: Int): Unit = result.accept(v)
    ).result

  def toArray(): Array[Int] =
    val spl = consume()
    val buffer = new IntBuffer
    val size = exactOutputSizeIfKnown(spl)
    if size >= 0 then
      checkArraySize(size)
      buffer.presize(size)
    copyInto(spl, buffer, shortCircuit = false)
    buffer.toArray()

private[stream] object IntPipeline:
  def head(spl: Spliterator[?], supplier: Supplier[? <: Spliterator[?]], flags: Int, parallel: Boolean): IntStream =
    new IntPipeline(null, flags, 0, null).initSource(spl, supplier, parallel)
