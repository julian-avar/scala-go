package java.util.stream

import java.util.function.{Consumer, DoubleConsumer, IntConsumer, LongConsumer}

/** Receives the elements of a traversal. A pipeline pushes its source's elements through a chain
 *  of sinks, one per intermediate operation, ending in the terminal operation's sink, as in the
 *  JDK: `begin` (with the exact number of elements, or -1) and `end` bracket the traversal, and a
 *  short-circuiting traversal stops pulling from the source once `cancellationRequested`.
 *
 *  Sinks are untyped: a sink accepts the element type of the stage it belongs to, so primitive
 *  stages pass primitives without boxing. A reference sink given a primitive (a source of
 *  primitives behind a `Stream`) boxes it.
 */
private[stream] abstract class Sink extends Consumer[Any], IntConsumer, LongConsumer, DoubleConsumer:
  def begin(size: Long): Unit = ()
  def end(): Unit = ()
  def cancellationRequested(): Boolean = false

  def accept(t: Any): Unit = throw new IllegalStateException("called wrong accept method")
  def accept(v: Int): Unit = accept(Integer.valueOf(v): Any)
  def accept(v: Long): Unit = accept(java.lang.Long.valueOf(v): Any)
  def accept(v: Double): Unit = accept(java.lang.Double.valueOf(v): Any)

private[stream] object Sink:
  /** The sink of an intermediate operation, which passes begin, end and cancellation on. */
  abstract class Chained(protected final val downstream: Sink) extends Sink:
    override def begin(size: Long): Unit = downstream.begin(size)
    override def end(): Unit = downstream.end()
    override def cancellationRequested(): Boolean = downstream.cancellationRequested()

  /** Calls a consumer with each element. */
  final class OfConsumer(action: Consumer[Any]) extends Sink:
    override def accept(t: Any): Unit = action.accept(t)

  final class OfIntConsumer(action: IntConsumer) extends Sink:
    override def accept(v: Int): Unit = action.accept(v)

  final class OfLongConsumer(action: LongConsumer) extends Sink:
    override def accept(v: Long): Unit = action.accept(v)

  final class OfDoubleConsumer(action: DoubleConsumer) extends Sink:
    override def accept(v: Double): Unit = action.accept(v)
