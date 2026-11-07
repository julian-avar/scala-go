package java.util.stream

import java.util.{Objects, Spliterator}
import java.util.function.Supplier

/** A stage of a stream pipeline: the source (the head, without `previous`) or an intermediate
 *  operation. This follows the JDK's design, for sequential evaluation: a terminal operation
 *  wraps its sink in one sink per stage (`opWrapSink`, last stage first) and pushes the source's
 *  elements through the chain, with the source spliterator's `forEachRemaining` (or `tryAdvance`
 *  until cancellation, if an operation short-circuits). A traversal allocates one sink per stage,
 *  nothing per element. `iterator()` and `spliterator()` on a stage pull elements through the
 *  same sinks (see [[WrappingSpliterator]]).
 *
 *  An operation's stage is created with the function that makes its sink (`wrap`), which can
 *  look at the input's `flags` (those of `previous`).
 *
 *  `flags` are the known properties of the stage's output: the `Spliterator` characteristics
 *  `SIZED`, `DISTINCT`, `SORTED` (in natural order) and `ORDERED`, plus whether an operation
 *  short-circuits or adjusts the size (`skip`, `limit`). Each operation sets and clears some.
 *
 *  Parallel streams run sequentially: `parallel()` only sets the flag `isParallel()` reports.
 */
private[stream] abstract class AbstractPipeline(
    private[stream] final val previous: AbstractPipeline,
    setFlags: Int,
    clearFlags: Int,
    wrap: Sink => Sink
):
  import AbstractPipeline.*

  private[stream] final val head: AbstractPipeline = if previous == null then this else previous.head

  private[stream] final val flags: Int =
    if previous == null then setFlags else (previous.flags & ~clearFlags) | setFlags

  /** Whether an operation was chained to this stage, or a terminal operation ran on it. */
  private[stream] final var linkedOrConsumed = false

  // State of the head stage only.
  private var sourceSpliterator: Spliterator[?] = null
  private var sourceSupplier: Supplier[? <: Spliterator[?]] = null
  private var closeAction: Runnable = null
  private var parallelFlag = false
  /** Whether the traversal whose sinks are being created short-circuits (the terminal operation
   *  or a stage does), which flatMap's sinks need to know, as in the JDK.
   */
  private var shortCircuitTraversal = false

  if previous != null then
    if previous.linkedOrConsumed then throw linkedException()
    previous.linkedOrConsumed = true

  /** Wraps `downstream` in this operation's sink (`wrap`; the head has none). */
  private[stream] final def opWrapSink(downstream: Sink): Sink = wrap(downstream)

  /** The number of elements this operation outputs for `previousSize` inputs (size-adjusting
   *  operations override it).
   */
  private[stream] def exactOutputSize(previousSize: Long): Long = previousSize

  /** Pushes all remaining elements of `spl` into `sink` (called on the head, which knows the
   *  source's element type).
   */
  private[stream] def pushAll(spl: Spliterator[?], sink: Sink): Unit

  /** Pushes one element of `spl` into `sink`, if there is one. */
  private[stream] def pushOne(spl: Spliterator[?], sink: Sink): Boolean

  /** Makes this stage the head, with its source. */
  private[stream] final def initSource(spl: Spliterator[?], supplier: Supplier[? <: Spliterator[?]],
      parallel: Boolean): this.type =
    sourceSpliterator = spl
    sourceSupplier = supplier
    parallelFlag = parallel
    this

  // ---- BaseStream -------------------------------------------------------------------------------

  def isParallel(): Boolean = head.parallelFlag

  protected final def setParallel(parallel: Boolean): Unit = head.parallelFlag = parallel

  protected final def addCloseHandler(closeHandler: Runnable): Unit =
    if linkedOrConsumed then throw linkedException()
    Objects.requireNonNull(closeHandler)
    val h = head
    h.closeAction =
      if h.closeAction == null then closeHandler else composeWithExceptions(h.closeAction, closeHandler)

  def close(): Unit =
    linkedOrConsumed = true
    sourceSpliterator = null
    sourceSupplier = null
    val h = head
    if h.closeAction != null then
      val action = h.closeAction
      h.closeAction = null
      action.run()

  /** For `unordered()`: whether the output is ordered, so that an operation is needed. */
  protected final def isOrdered: Boolean = (flags & Spliterator.ORDERED) != 0

  // ---- Evaluation -------------------------------------------------------------------------------

  /** Takes the source spliterator, for a terminal operation (or `iterator()`) on this stage. */
  private[stream] final def consume(): Spliterator[?] =
    if linkedOrConsumed then throw linkedException()
    linkedOrConsumed = true
    val h = head
    if h.sourceSpliterator != null then
      val spl = h.sourceSpliterator
      h.sourceSpliterator = null
      spl
    else if h.sourceSupplier != null then
      val spl = h.sourceSupplier.get()
      h.sourceSupplier = null
      spl
    else throw new IllegalStateException("source already consumed or closed")

  /** `sink` wrapped in the sinks of the stages up to this one, for a traversal that
   *  short-circuits or not.
   */
  private[stream] final def wrapSink(sink: Sink, shortCircuit: Boolean): Sink =
    head.shortCircuitTraversal = shortCircuit
    var s = sink
    var stage = this
    while stage.previous != null do
      s = stage.opWrapSink(s)
      stage = stage.previous
    s

  /** Pushes the elements of `spl` through the stages into `sink`. */
  private[stream] final def copyInto(spl: Spliterator[?], sink: Sink, shortCircuit: Boolean): Unit =
    val cancellable = shortCircuit || (flags & ShortCircuit) != 0
    val wrapped = wrapSink(sink, cancellable)
    wrapped.begin(spl.getExactSizeIfKnown())
    if cancellable then
      while !wrapped.cancellationRequested() && head.pushOne(spl, wrapped) do ()
    else head.pushAll(spl, wrapped)
    wrapped.end()

  /** Runs a terminal operation, which collects its result in `sink`. */
  protected final def evaluate[S <: Sink](sink: S): S =
    copyInto(consume(), sink, shortCircuit = false)
    sink

  /** Runs a short-circuiting terminal operation. */
  protected final def evaluateShortCircuit[S <: Sink](sink: S): S =
    copyInto(consume(), sink, shortCircuit = true)
    sink

  /** The number of elements the pipeline outputs for `spl`, if known without traversing it. */
  private[stream] final def exactOutputSizeIfKnown(spl: Spliterator[?]): Long =
    val size = if (flags & Spliterator.SIZED) != 0 then spl.getExactSizeIfKnown() else -1L
    if size == -1L || (flags & SizeAdjusting) == 0 then size else adjustedSize(this, size)

  private def adjustedSize(stage: AbstractPipeline, sourceSize: Long): Long =
    if stage.previous == null then sourceSize else stage.exactOutputSize(adjustedSize(stage.previous, sourceSize))

  /** `count()`: the size if it is known, without running the operations (as the JDK does since
   *  Java 9), otherwise the number of elements a traversal yields.
   */
  protected final def evaluateCount(): Long =
    val spl = consume()
    val known = exactOutputSizeIfKnown(spl)
    if known != -1L then known
    else
      val counter = new CountingSink
      copyInto(spl, counter, shortCircuit = false)
      counter.count

  /** The characteristics of a spliterator over this stage's output, given the source's. */
  private[stream] final def outputCharacteristics(source: Spliterator[?]): Int =
    val c = flags & (Spliterator.DISTINCT | Spliterator.SORTED | Spliterator.ORDERED)
    if (flags & Spliterator.SIZED) != 0 then c | (source.characteristics() & (Spliterator.SIZED | Spliterator.SUBSIZED))
    else c

private[stream] object AbstractPipeline:
  final val ShortCircuit = 0x10000000
  final val SizeAdjusting = 0x20000000

  /** Flags of an intermediate operation that changes neither size nor order, but may change the
   *  values (`map`).
   */
  final val NotSortedOrDistinct = Spliterator.SORTED | Spliterator.DISTINCT
  final val NotSortedDistinctOrSized = NotSortedOrDistinct | Spliterator.SIZED

  /** The flags of a source: its `SIZED`, `DISTINCT`, `ORDERED` characteristics, and `SORTED` if
   *  sorted in natural order.
   */
  def sourceFlags(characteristics: Int, spl: Spliterator[?]): Int =
    var c = characteristics & (Spliterator.SIZED | Spliterator.DISTINCT | Spliterator.ORDERED | Spliterator.SORTED)
    if (c & Spliterator.SORTED) != 0 && spl != null && spl.getComparator() != null then c &= ~Spliterator.SORTED
    c

  def sourceFlags(spl: Spliterator[?]): Int = sourceFlags(spl.characteristics(), spl)

  def linkedException(): IllegalStateException =
    new IllegalStateException("stream has already been operated upon or closed")

  /** Runs `a`, then `b`, even if `a` throws; an exception of `b` is suppressed by `a`'s. */
  def composeWithExceptions(a: Runnable, b: Runnable): Runnable = () =>
    try a.run()
    catch
      case e1: Throwable =>
        try b.run()
        catch case e2: Throwable => if e1 ne e2 then e1.addSuppressed(e2)
        throw e1
    b.run()

  /** Closes `a` then `b`, as `Stream.concat` does. */
  def composedClose(a: BaseStream[?, ?], b: BaseStream[?, ?]): Runnable = () =>
    try a.close()
    catch
      case e1: Throwable =>
        try b.close()
        catch case e2: Throwable => if e1 ne e2 then e1.addSuppressed(e2)
        throw e1
    b.close()

  /** The argument check of `skip` and `limit`. */
  def checkCount(n: Long): Unit =
    if n < 0 then throw new IllegalArgumentException(java.lang.Long.toString(n))

  private final class CountingSink extends Sink:
    var count = 0L
    override def accept(t: Any): Unit = count += 1
    override def accept(v: Int): Unit = count += 1
    override def accept(v: Long): Unit = count += 1
    override def accept(v: Double): Unit = count += 1

  /** The sink of `skip(skip)` and `limit(limit)` (`limit` is -1 for none), for any element type. */
  abstract class SliceSink(downstream: Sink, skip: Long, limit: Long) extends Sink.Chained(downstream):
    protected var toSkip: Long = skip
    protected var toTake: Long = limit

    override def begin(size: Long): Unit =
      downstream.begin(sliceSize(size, skip, limit))

    /** Whether to pass the next element on. */
    protected final def take(): Boolean =
      if toSkip == 0 then
        if toTake != 0 then
          if toTake > 0 then toTake -= 1
          true
        else false
      else
        toSkip -= 1
        false

    override def cancellationRequested(): Boolean = toTake == 0 || downstream.cancellationRequested()

  /** The output size of `skip` and `limit` (-1 for none) for an input of `size` (-1: unknown). */
  def sliceSize(size: Long, skip: Long, limit: Long): Long =
    if size < 0 then -1L
    else Math.min(Math.max(size - skip, 0L), if limit >= 0 then limit else Long.MaxValue)

  /** Flags of `skip(n)` (limit -1) or `limit(n)`. */
  def sliceFlags(limit: Long): Int = SizeAdjusting | (if limit != -1 then ShortCircuit else 0)

  /** The sink of `flatMap` and its variants: pushes the elements of the stream each input element
   *  maps to, then closes that stream. In a short-circuiting pipeline, it stops pulling from the
   *  inner stream once the downstream cancels.
   */
  abstract class FlatMapSink(downstream: Sink, pipeline: AbstractPipeline) extends Sink.Chained(downstream):
    private val shortCircuit = pipeline.head.shortCircuitTraversal
    private var cancel = false

    /** Passes the inner streams' elements on, without their begin and end. */
    private val inner: Sink = new Sink:
      override def accept(t: Any): Unit = downstream.accept(t)
      override def accept(v: Int): Unit = downstream.accept(v)
      override def accept(v: Long): Unit = downstream.accept(v)
      override def accept(v: Double): Unit = downstream.accept(v)
      override def cancellationRequested(): Boolean = FlatMapSink.this.cancellationRequested()

    override def begin(size: Long): Unit = downstream.begin(-1)

    override def cancellationRequested(): Boolean =
      cancel || {
        cancel = downstream.cancellationRequested()
        cancel
      }

    /** Pushes the elements of `result` (null for none) and closes it. */
    protected final def push(result: BaseStream[?, ?]): Unit =
      if result != null then
        var failure: Throwable = null
        try
          result match
            case p: AbstractPipeline => p.copyInto(p.consume(), inner, shortCircuit)
            case _ =>
              val spl = result.sequential().spliterator().asInstanceOf[Spliterator[Any]]
              if shortCircuit then while !cancellationRequested() && spl.tryAdvance(inner) do ()
              else spl.forEachRemaining(inner)
        catch
          case e: Throwable =>
            failure = e
            throw e
        finally
          if failure == null then result.close()
          else
            try result.close()
            catch case e: Throwable => if e ne failure then failure.addSuppressed(e)

  /** The JDK's limit on stream sizes that `sorted` and `toArray` buffer. */
  final val MaxArraySize = Int.MaxValue - 8

  def checkArraySize(size: Long): Unit =
    if size >= MaxArraySize then throw new IllegalArgumentException("Stream size exceeds max array size")
