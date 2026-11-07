package java.util.stream

import java.util.{Arrays, Collections, Comparator, Iterator, List, Objects, Optional, Spliterator, Spliterators}
import java.util.function._

import AbstractPipeline._
import Ops._

/** A stage of a pipeline of references: `Stream`'s implementation (see [[AbstractPipeline]]). */
private[stream] class ReferencePipeline[T](previous: AbstractPipeline, setFlags: Int, clearFlags: Int, wrap: Sink => Sink)
    extends AbstractPipeline(previous, setFlags, clearFlags, wrap), Stream[T]:

  /** A source of primitives behind a `Stream` delivers boxes to a consumer that is not a
   *  primitive consumer.
   */
  private def consumerFor(spl: Spliterator[?], sink: Sink): Consumer[Any] = spl match
    case _: Spliterator.OfPrimitive[?, ?, ?] => (e: Any) => sink.accept(e)
    case _                                   => sink

  private[stream] def pushAll(spl: Spliterator[?], sink: Sink): Unit =
    spl.asInstanceOf[Spliterator[Any]].forEachRemaining(consumerFor(spl, sink))

  private[stream] def pushOne(spl: Spliterator[?], sink: Sink): Boolean =
    spl.asInstanceOf[Spliterator[Any]].tryAdvance(consumerFor(spl, sink))

  private def op[R](setFlags: Int, clearFlags: Int)(wrap: Sink => Sink): Stream[R] =
    new ReferencePipeline[R](this, setFlags, clearFlags, wrap)

  // ---- BaseStream -------------------------------------------------------------------------------

  def iterator(): Iterator[T] = Spliterators.iterator[T](spliterator())

  def spliterator(): Spliterator[T] =
    val source = consume()
    if previous == null then source.asInstanceOf[Spliterator[T]]
    else StreamSpliterators.wrapRef[T](this, source)

  def onClose(closeHandler: Runnable): Stream[T] =
    addCloseHandler(closeHandler)
    this

  def parallel(): Stream[T] =
    setParallel(true)
    this

  def sequential(): Stream[T] =
    setParallel(false)
    this

  def unordered(): Stream[T] =
    if !isOrdered then this else op[T](0, Spliterator.ORDERED)(down => down)

  // ---- Stateless operations ---------------------------------------------------------------------

  def filter(pred: Predicate[? >: T]): Stream[T] =
    Objects.requireNonNull(pred)
    val p = pred.asInstanceOf[Predicate[Any]]
    op[T](0, Spliterator.SIZED)(down =>
      new Sink.Chained(down):
        override def begin(size: Long): Unit = down.begin(-1)
        override def accept(t: Any): Unit = if p.test(t) then down.accept(t)
    )

  def map[R](mapper: Function[? >: T, ? <: R]): Stream[R] =
    Objects.requireNonNull(mapper)
    val f = mapper.asInstanceOf[Function[Any, Any]]
    op[R](0, NotSortedOrDistinct)(down =>
      new Sink.Chained(down):
        override def accept(t: Any): Unit = down.accept(f.apply(t))
    )

  def mapToInt(mapper: ToIntFunction[? >: T]): IntStream =
    Objects.requireNonNull(mapper)
    val f = mapper.asInstanceOf[ToIntFunction[Any]]
    new IntPipeline(this, 0, NotSortedOrDistinct, down =>
      new Sink.Chained(down):
        override def accept(t: Any): Unit = down.accept(f.applyAsInt(t))
    )

  def mapToLong(mapper: ToLongFunction[? >: T]): LongStream =
    Objects.requireNonNull(mapper)
    val f = mapper.asInstanceOf[ToLongFunction[Any]]
    new LongPipeline(this, 0, NotSortedOrDistinct, down =>
      new Sink.Chained(down):
        override def accept(t: Any): Unit = down.accept(f.applyAsLong(t))
    )

  def mapToDouble(mapper: ToDoubleFunction[? >: T]): DoubleStream =
    Objects.requireNonNull(mapper)
    val f = mapper.asInstanceOf[ToDoubleFunction[Any]]
    new DoublePipeline(this, 0, NotSortedOrDistinct, down =>
      new Sink.Chained(down):
        override def accept(t: Any): Unit = down.accept(f.applyAsDouble(t))
    )

  def flatMap[R](mapper: Function[? >: T, ? <: Stream[? <: R]]): Stream[R] =
    Objects.requireNonNull(mapper)
    val f = mapper.asInstanceOf[Function[Any, Stream[?]]]
    op[R](0, NotSortedDistinctOrSized)(down =>
      new FlatMapSink(down, this):
        override def accept(t: Any): Unit = push(f.apply(t))
    )

  def flatMapToInt(mapper: Function[? >: T, ? <: IntStream]): IntStream =
    Objects.requireNonNull(mapper)
    val f = mapper.asInstanceOf[Function[Any, IntStream]]
    new IntPipeline(this, 0, NotSortedDistinctOrSized, down =>
      new FlatMapSink(down, this):
        override def accept(t: Any): Unit = push(f.apply(t))
    )

  def flatMapToLong(mapper: Function[? >: T, ? <: LongStream]): LongStream =
    Objects.requireNonNull(mapper)
    val f = mapper.asInstanceOf[Function[Any, LongStream]]
    new LongPipeline(this, 0, NotSortedDistinctOrSized, down =>
      new FlatMapSink(down, this):
        override def accept(t: Any): Unit = push(f.apply(t))
    )

  def flatMapToDouble(mapper: Function[? >: T, ? <: DoubleStream]): DoubleStream =
    Objects.requireNonNull(mapper)
    val f = mapper.asInstanceOf[Function[Any, DoubleStream]]
    new DoublePipeline(this, 0, NotSortedDistinctOrSized, down =>
      new FlatMapSink(down, this):
        override def accept(t: Any): Unit = push(f.apply(t))
    )

  override def mapMulti[R](mapper: BiConsumer[? >: T, ? >: Consumer[R]]): Stream[R] =
    Objects.requireNonNull(mapper)
    val f = mapper.asInstanceOf[BiConsumer[Any, Consumer[Any]]]
    op[R](0, NotSortedDistinctOrSized)(down =>
      new Sink.Chained(down):
        override def begin(size: Long): Unit = down.begin(-1)
        override def accept(t: Any): Unit = f.accept(t, down)
    )

  override def mapMultiToInt(mapper: BiConsumer[? >: T, ? >: IntConsumer]): IntStream =
    Objects.requireNonNull(mapper)
    val f = mapper.asInstanceOf[BiConsumer[Any, IntConsumer]]
    new IntPipeline(this, 0, NotSortedDistinctOrSized, down =>
      new Sink.Chained(down):
        override def begin(size: Long): Unit = down.begin(-1)
        override def accept(t: Any): Unit = f.accept(t, down)
    )

  override def mapMultiToLong(mapper: BiConsumer[? >: T, ? >: LongConsumer]): LongStream =
    Objects.requireNonNull(mapper)
    val f = mapper.asInstanceOf[BiConsumer[Any, LongConsumer]]
    new LongPipeline(this, 0, NotSortedDistinctOrSized, down =>
      new Sink.Chained(down):
        override def begin(size: Long): Unit = down.begin(-1)
        override def accept(t: Any): Unit = f.accept(t, down)
    )

  override def mapMultiToDouble(mapper: BiConsumer[? >: T, ? >: DoubleConsumer]): DoubleStream =
    Objects.requireNonNull(mapper)
    val f = mapper.asInstanceOf[BiConsumer[Any, DoubleConsumer]]
    new DoublePipeline(this, 0, NotSortedDistinctOrSized, down =>
      new Sink.Chained(down):
        override def begin(size: Long): Unit = down.begin(-1)
        override def accept(t: Any): Unit = f.accept(t, down)
    )

  def peek(action: Consumer[? >: T]): Stream[T] =
    Objects.requireNonNull(action)
    val a = action.asInstanceOf[Consumer[Any]]
    op[T](0, 0)(down =>
      new Sink.Chained(down):
        override def accept(t: Any): Unit =
          a.accept(t)
          down.accept(t)
    )

  override def takeWhile(pred: Predicate[? >: T]): Stream[T] =
    Objects.requireNonNull(pred)
    val p = pred.asInstanceOf[Predicate[Any]]
    op[T](ShortCircuit, Spliterator.SIZED)(down =>
      new Sink.Chained(down):
        private var taking = true
        override def begin(size: Long): Unit = down.begin(-1)
        override def accept(t: Any): Unit =
          if taking then
            taking = p.test(t)
            if taking then down.accept(t)
        override def cancellationRequested(): Boolean = !taking || down.cancellationRequested()
    )

  override def dropWhile(pred: Predicate[? >: T]): Stream[T] =
    Objects.requireNonNull(pred)
    val p = pred.asInstanceOf[Predicate[Any]]
    op[T](0, Spliterator.SIZED)(down =>
      new Sink.Chained(down):
        private var taking = false
        override def begin(size: Long): Unit = down.begin(-1)
        override def accept(t: Any): Unit =
          if !taking then taking = !p.test(t)
          if taking then down.accept(t)
    )

  // ---- Stateful operations ----------------------------------------------------------------------

  def distinct(): Stream[T] =
    val input = flags
    op[T](Spliterator.DISTINCT, Spliterator.SIZED)(down =>
      if (input & Spliterator.DISTINCT) != 0 then down
      else if (input & Spliterator.SORTED) != 0 then new SortedDistinctSink(down)
      else new DistinctSink(down)
    )

  def sorted(): Stream[T] =
    val input = flags
    op[T](Spliterator.ORDERED | Spliterator.SORTED, 0)(down =>
      if (input & Spliterator.SORTED) != 0 then down
      else new RefSortingSink(down, Comparator.naturalOrder[Integer]().asInstanceOf[Comparator[Any]])
    )

  def sorted(comparator: Comparator[? >: T]): Stream[T] =
    Objects.requireNonNull(comparator)
    val c = comparator.asInstanceOf[Comparator[Any]]
    op[T](Spliterator.ORDERED, Spliterator.SORTED)(down => new RefSortingSink(down, c))

  def limit(maxSize: Long): Stream[T] =
    checkCount(maxSize)
    slice(0, maxSize)

  def skip(n: Long): Stream[T] =
    checkCount(n)
    if n == 0 then this else slice(n, -1)

  private def slice(toSkip: Long, maxSize: Long): Stream[T] =
    new ReferencePipeline[T](this, sliceFlags(maxSize), 0, down =>
      new SliceSink(down, toSkip, maxSize):
        override def accept(t: Any): Unit = if take() then down.accept(t)
    ):
      override private[stream] def exactOutputSize(previousSize: Long): Long = sliceSize(previousSize, toSkip, maxSize)

  // ---- Terminal operations ----------------------------------------------------------------------

  def forEach(action: Consumer[? >: T]): Unit =
    Objects.requireNonNull(action)
    evaluate(new Sink.OfConsumer(action.asInstanceOf[Consumer[Any]]))

  def forEachOrdered(action: Consumer[? >: T]): Unit = forEach(action)

  private def matchSink(pred: Predicate[? >: T], stopWhen: Boolean): MatchSink =
    Objects.requireNonNull(pred)
    val p = pred.asInstanceOf[Predicate[Any]]
    evaluateShortCircuit(new MatchSink(stopWhen):
      override def accept(t: Any): Unit = if !stopped then record(p.test(t))
    )

  def anyMatch(pred: Predicate[? >: T]): Boolean = matchSink(pred, stopWhen = true).stopped

  def allMatch(pred: Predicate[? >: T]): Boolean = !matchSink(pred, stopWhen = false).stopped

  def noneMatch(pred: Predicate[? >: T]): Boolean = !matchSink(pred, stopWhen = true).stopped

  def findFirst(): Optional[T] =
    val sink = evaluateShortCircuit(new FindSink)
    if sink.found then Optional.of(sink.ref.asInstanceOf[T]) else Optional.empty[T]()

  def findAny(): Optional[T] = findFirst()

  def reduce(identity: T, accumulator: BinaryOperator[T]): T =
    Objects.requireNonNull(accumulator)
    val op = accumulator.asInstanceOf[BinaryOperator[Any]]
    evaluate(new Accumulator[Any]:
      var result: Any = identity
      override def accept(t: Any): Unit = result = op.apply(result, t)
    ).result.asInstanceOf[T]

  def reduce(accumulator: BinaryOperator[T]): Optional[T] =
    Objects.requireNonNull(accumulator)
    val op = accumulator.asInstanceOf[BinaryOperator[Any]]
    val sink = evaluate(new Accumulator[Any]:
      var result: Any = null
      override def accept(t: Any): Unit =
        result = if seen == 0 then t else op.apply(result, t)
        seen = 1
    )
    if sink.seen == 0 then Optional.empty[T]() else Optional.of(sink.result.asInstanceOf[T])

  def reduce[U](identity: U, accumulator: BiFunction[U, ? >: T, U], combiner: BinaryOperator[U]): U =
    Objects.requireNonNull(accumulator)
    Objects.requireNonNull(combiner)
    val op = accumulator.asInstanceOf[BiFunction[Any, Any, Any]]
    evaluate(new Accumulator[Any]:
      var result: Any = identity
      override def accept(t: Any): Unit = result = op.apply(result, t)
    ).result.asInstanceOf[U]

  def collect[R, A](collector: Collector[? >: T, A, R]): R =
    val c = collector.asInstanceOf[Collector[Any, Any, R]]
    val supplier = c.supplier()
    val accumulator = c.accumulator()
    c.combiner()
    c.characteristics()
    val container = evaluate(new Accumulator[Any]:
      var result: Any = null
      override def begin(size: Long): Unit = result = supplier.get()
      override def accept(t: Any): Unit = accumulator.accept(result, t)
    ).result
    if c.characteristics().contains(Collector.Characteristics.IDENTITY_FINISH) then container.asInstanceOf[R]
    else c.finisher().apply(container)

  def collect[R](supplier: Supplier[R], accumulator: BiConsumer[R, ? >: T], combiner: BiConsumer[R, R]): R =
    Objects.requireNonNull(supplier)
    Objects.requireNonNull(accumulator)
    Objects.requireNonNull(combiner)
    val acc = accumulator.asInstanceOf[BiConsumer[R, Any]]
    evaluate(new Accumulator[R]:
      var result: R = null.asInstanceOf[R]
      override def begin(size: Long): Unit = result = supplier.get()
      override def accept(t: Any): Unit = acc.accept(result, t)
    ).result

  def max(comparator: Comparator[? >: T]): Optional[T] = reduce(BinaryOperator.maxBy[T](comparator))

  def min(comparator: Comparator[? >: T]): Optional[T] = reduce(BinaryOperator.minBy[T](comparator))

  def count(): Long = evaluateCount()

  def toArray(): Array[Object] = toArray[Object](n => new Array[Object](n))

  def toArray[A <: Object](generator: IntFunction[Array[A]]): Array[A] =
    val spl = consume()
    val size = exactOutputSizeIfKnown(spl)
    if size >= 0 then
      checkArraySize(size)
      val array = generator.apply(size.toInt)
      if array.length != size then
        throw new IllegalStateException(s"Begin size $size is not equal to fixed size ${array.length}")
      val filler = new Sink:
        private var i = 0
        override def accept(t: Any): Unit =
          array(i) = t.asInstanceOf[A]
          i += 1
      copyInto(spl, filler, shortCircuit = false)
      array
    else
      val buffer = new RefBuffer
      copyInto(spl, buffer, shortCircuit = false)
      val array = generator.apply(buffer.count)
      System.arraycopy(buffer.elems, 0, array, 0, buffer.count)
      array

  override def toList(): List[T] =
    Collections.unmodifiableList(Arrays.asList(toArray()).asInstanceOf[List[T]])

private[stream] object ReferencePipeline:
  def head[T](spl: Spliterator[?], supplier: Supplier[? <: Spliterator[?]], flags: Int, parallel: Boolean): Stream[T] =
    new ReferencePipeline[T](null, flags, 0, null).initSource(spl, supplier, parallel)
