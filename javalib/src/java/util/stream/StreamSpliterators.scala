package java.util.stream

import java.util.{Comparator, Objects, Spliterator}
import java.util.function.{Consumer, DoubleConsumer, IntConsumer, LongConsumer}

import Ops.{Buffer, DoubleBuffer, IntBuffer, LongBuffer, RefBuffer}

/** Spliterators over the output of pipeline stages. */
private[stream] object StreamSpliterators:

  // ---- Over a pipeline's output -----------------------------------------------------------------

  /** A spliterator over the output of a pipeline stage: it pushes source elements through the
   *  stages' sinks into a buffer, one source element at a time, as elements are pulled.
   *  `forEachRemaining` before any `tryAdvance` runs the whole pipeline into the action instead.
   */
  private abstract class Wrapping[T, B <: Buffer](pipeline: AbstractPipeline, source: Spliterator[?], buffer: B)
      extends Spliterator[T]:
    private var wrapped: Sink = null
    private var finished = false

    /** Whether the buffer has an element, after pushing source elements until it has one or the
     *  traversal is over.
     */
    protected final def fill(): Boolean =
      if wrapped == null then
        wrapped = pipeline.wrapSink(buffer, (pipeline.flags & AbstractPipeline.ShortCircuit) != 0)
        wrapped.begin(source.getExactSizeIfKnown())
      while buffer.isEmpty && !finished do
        if wrapped.cancellationRequested() || !pipeline.head.pushOne(source, wrapped) then
          finished = true
          wrapped.end()
      !buffer.isEmpty

    /** Runs the rest of the pipeline into `sink`, if nothing was pulled yet (then returns true). */
    protected final def pushAllInto(sink: Sink): Boolean =
      if wrapped != null || finished then false
      else
        finished = true
        pipeline.copyInto(source, sink, shortCircuit = false)
        true

    def trySplit(): Spliterator[T] = null

    def estimateSize(): Long =
      val exact = pipeline.exactOutputSizeIfKnown(source)
      if exact >= 0 then exact else source.estimateSize()

    override def getExactSizeIfKnown(): Long =
      if (characteristics() & Spliterator.SIZED) != 0 then pipeline.exactOutputSizeIfKnown(source) else -1L

    def characteristics(): Int = pipeline.outputCharacteristics(source)

    override def getComparator(): Comparator[? >: T] =
      if (characteristics() & Spliterator.SORTED) != 0 then null else throw new IllegalStateException()

  def wrapRef[T](pipeline: AbstractPipeline, source: Spliterator[?]): Spliterator[T] =
    val buffer = new RefBuffer
    new Wrapping[T, RefBuffer](pipeline, source, buffer):
      def tryAdvance(action: Consumer[? >: T]): Boolean =
        Objects.requireNonNull(action)
        val has = fill()
        if has then action.accept(buffer.take().asInstanceOf[T])
        has
      override def forEachRemaining(action: Consumer[? >: T]): Unit =
        Objects.requireNonNull(action)
        if !pushAllInto(new Sink.OfConsumer(action.asInstanceOf[Consumer[Any]])) then
          while tryAdvance(action) do ()

  def wrapInt(pipeline: AbstractPipeline, source: Spliterator[?]): Spliterator.OfInt =
    val buffer = new IntBuffer
    new Wrapping[Integer, IntBuffer](pipeline, source, buffer) with Spliterator.OfInt:
      override def trySplit(): Spliterator.OfInt = null
      def tryAdvance(action: IntConsumer): Boolean =
        Objects.requireNonNull(action)
        val has = fill()
        if has then action.accept(buffer.take())
        has
      override def forEachRemaining(action: IntConsumer): Unit =
        Objects.requireNonNull(action)
        if !pushAllInto(new Sink.OfIntConsumer(action)) then
          while tryAdvance(action) do ()

  def wrapLong(pipeline: AbstractPipeline, source: Spliterator[?]): Spliterator.OfLong =
    val buffer = new LongBuffer
    new Wrapping[java.lang.Long, LongBuffer](pipeline, source, buffer) with Spliterator.OfLong:
      override def trySplit(): Spliterator.OfLong = null
      def tryAdvance(action: LongConsumer): Boolean =
        Objects.requireNonNull(action)
        val has = fill()
        if has then action.accept(buffer.take())
        has
      override def forEachRemaining(action: LongConsumer): Unit =
        Objects.requireNonNull(action)
        if !pushAllInto(new Sink.OfLongConsumer(action)) then
          while tryAdvance(action) do ()

  def wrapDouble(pipeline: AbstractPipeline, source: Spliterator[?]): Spliterator.OfDouble =
    val buffer = new DoubleBuffer
    new Wrapping[java.lang.Double, DoubleBuffer](pipeline, source, buffer) with Spliterator.OfDouble:
      override def trySplit(): Spliterator.OfDouble = null
      def tryAdvance(action: DoubleConsumer): Boolean =
        Objects.requireNonNull(action)
        val has = fill()
        if has then action.accept(buffer.take())
        has
      override def forEachRemaining(action: DoubleConsumer): Unit =
        Objects.requireNonNull(action)
        if !pushAllInto(new Sink.OfDoubleConsumer(action)) then
          while tryAdvance(action) do ()
