package java.util.stream

import java.util.{Arrays, Comparator, HashSet}

/** Sinks shared by the pipelines of the four element types: buffers, stateful operations
 *  (`sorted`, `distinct`) and terminal operations.
 */
private[stream] object Ops:

  // ---- Buffers ----------------------------------------------------------------------------------

  /** A growable array of elements, read from position `pos` (for pull-based traversals, which
   *  only refill a drained buffer).
   */
  abstract class Buffer extends Sink:
    var count = 0
    var pos = 0
    final def isEmpty: Boolean = pos == count
    protected final def taken(): Unit =
      pos += 1
      if pos == count then
        pos = 0
        count = 0
    protected final def grownLength(length: Int): Int =
      if length == 0 then 16
      else if length >= AbstractPipeline.MaxArraySize / 2 then AbstractPipeline.MaxArraySize
      else length * 2
    /** Makes room for `size` elements, if the size is known. */
    def presize(size: Long): Unit

  final class RefBuffer extends Buffer:
    var elems = new Array[AnyRef](0)
    def presize(size: Long): Unit = if size > elems.length then elems = new Array[AnyRef](size.toInt)
    override def accept(t: Any): Unit =
      if count == elems.length then elems = Arrays.copyOf(elems, grownLength(count))
      elems(count) = t.asInstanceOf[AnyRef]
      count += 1
    def take(): Any =
      val e = elems(pos)
      elems(pos) = null
      taken()
      e

  final class IntBuffer extends Buffer:
    var elems = new Array[Int](0)
    def presize(size: Long): Unit = if size > elems.length then elems = new Array[Int](size.toInt)
    override def accept(v: Int): Unit =
      if count == elems.length then elems = Arrays.copyOf(elems, grownLength(count))
      elems(count) = v
      count += 1
    def take(): Int =
      val e = elems(pos)
      taken()
      e
    def toArray(): Array[Int] = if elems.length == count then elems else Arrays.copyOf(elems, count)

  final class LongBuffer extends Buffer:
    var elems = new Array[Long](0)
    def presize(size: Long): Unit = if size > elems.length then elems = new Array[Long](size.toInt)
    override def accept(v: Long): Unit =
      if count == elems.length then elems = Arrays.copyOf(elems, grownLength(count))
      elems(count) = v
      count += 1
    def take(): Long =
      val e = elems(pos)
      taken()
      e
    def toArray(): Array[Long] = if elems.length == count then elems else Arrays.copyOf(elems, count)

  final class DoubleBuffer extends Buffer:
    var elems = new Array[Double](0)
    def presize(size: Long): Unit = if size > elems.length then elems = new Array[Double](size.toInt)
    override def accept(v: Double): Unit =
      if count == elems.length then elems = Arrays.copyOf(elems, grownLength(count))
      elems(count) = v
      count += 1
    def take(): Double =
      val e = elems(pos)
      taken()
      e
    def toArray(): Array[Double] = if elems.length == count then elems else Arrays.copyOf(elems, count)

  // ---- sorted -----------------------------------------------------------------------------------

  /** Buffers all elements, then sorts them and pushes them on at the end. Reports no
   *  cancellation upstream, but stops pushing when the downstream cancels, if it asked.
   */
  abstract class SortingSink[B <: Buffer](downstream: Sink, protected final val buffer: B)
      extends Sink.Chained(downstream):
    private var cancellationWasRequested = false

    override def begin(size: Long): Unit =
      AbstractPipeline.checkArraySize(size)
      buffer.presize(size)

    override def cancellationRequested(): Boolean =
      cancellationWasRequested = true
      false

    protected def sort(): Unit
    protected def push(i: Int): Unit

    override def end(): Unit =
      sort()
      val n = buffer.count
      downstream.begin(n)
      var i = 0
      if !cancellationWasRequested then
        while i < n do
          push(i)
          i += 1
      else
        while i < n && !downstream.cancellationRequested() do
          push(i)
          i += 1
      downstream.end()

  final class RefSortingSink(downstream: Sink, comparator: Comparator[Any])
      extends SortingSink(downstream, new RefBuffer):
    override def accept(t: Any): Unit = buffer.accept(t)
    protected def sort(): Unit = Arrays.sort(buffer.elems, 0, buffer.count, comparator)
    protected def push(i: Int): Unit = downstream.accept(buffer.elems(i): Any)

  final class IntSortingSink(downstream: Sink) extends SortingSink(downstream, new IntBuffer):
    override def accept(v: Int): Unit = buffer.accept(v)
    protected def sort(): Unit = Arrays.sort(buffer.elems, 0, buffer.count)
    protected def push(i: Int): Unit = downstream.accept(buffer.elems(i))

  final class LongSortingSink(downstream: Sink) extends SortingSink(downstream, new LongBuffer):
    override def accept(v: Long): Unit = buffer.accept(v)
    protected def sort(): Unit = Arrays.sort(buffer.elems, 0, buffer.count)
    protected def push(i: Int): Unit = downstream.accept(buffer.elems(i))

  final class DoubleSortingSink(downstream: Sink) extends SortingSink(downstream, new DoubleBuffer):
    override def accept(v: Double): Unit = buffer.accept(v)
    protected def sort(): Unit = Arrays.sort(buffer.elems, 0, buffer.count)
    protected def push(i: Int): Unit = downstream.accept(buffer.elems(i))

  // ---- distinct ---------------------------------------------------------------------------------

  /** Passes on the first occurrence of each element (by `equals`), boxing primitives. */
  final class DistinctSink(downstream: Sink) extends Sink.Chained(downstream):
    private var seen: HashSet[Any] = null
    override def begin(size: Long): Unit =
      seen = new HashSet[Any]()
      downstream.begin(-1)
    override def end(): Unit =
      seen = null
      downstream.end()
    override def accept(t: Any): Unit = if seen.add(t) then downstream.accept(t)
    override def accept(v: Int): Unit = if seen.add(Integer.valueOf(v)) then downstream.accept(v)
    override def accept(v: Long): Unit = if seen.add(java.lang.Long.valueOf(v)) then downstream.accept(v)
    override def accept(v: Double): Unit = if seen.add(java.lang.Double.valueOf(v)) then downstream.accept(v)

  /** `distinct` on input sorted in natural order: compares each element with the previous one. */
  final class SortedDistinctSink(downstream: Sink) extends Sink.Chained(downstream):
    private var seenNull = false
    private var lastSeen: Any = null
    override def begin(size: Long): Unit =
      seenNull = false
      lastSeen = null
      downstream.begin(-1)
    override def end(): Unit =
      lastSeen = null
      downstream.end()
    override def accept(t: Any): Unit =
      if t == null then
        if !seenNull then
          seenNull = true
          lastSeen = null
          downstream.accept(t)
      else if lastSeen == null || !t.equals(lastSeen) then
        lastSeen = t
        downstream.accept(t)

  // ---- Terminal operations ----------------------------------------------------------------------

  /** A terminal sink with a result, and the number of elements seen, for those that count. */
  abstract class Accumulator[R] extends Sink:
    var seen = 0L
    def result: R

  /** `findFirst`/`findAny`, for any element type: keeps the first element. */
  final class FindSink extends Sink:
    var found = false
    var ref: Any = null
    var int = 0
    var long = 0L
    var double = 0.0
    override def accept(t: Any): Unit =
      if !found then
        found = true
        ref = t
    override def accept(v: Int): Unit =
      if !found then
        found = true
        int = v
    override def accept(v: Long): Unit =
      if !found then
        found = true
        long = v
    override def accept(v: Double): Unit =
      if !found then
        found = true
        double = v
    override def cancellationRequested(): Boolean = found

  /** `anyMatch`, `allMatch` and `noneMatch`: stops at the first element whose test is
   *  `stopWhen`.
   */
  abstract class MatchSink(stopWhen: Boolean) extends Sink:
    var stopped = false
    protected final def record(test: Boolean): Unit = if test == stopWhen then stopped = true
    override def cancellationRequested(): Boolean = stopped

  /** The compensated (Kahan) summation of `DoubleStream.sum`/`average` and of the `Collectors`
   *  over doubles, with the JDK's rounding: `sum` and `compensation` are the running sum and its
   *  negated low-order bits, and `simpleSum` the plain sum, used when the compensated sum turns
   *  into NaN from infinities of one sign.
   */
  final class DoubleSum:
    var sum = 0.0
    var compensation = 0.0
    var simpleSum = 0.0
    var count = 0L

    def add(value: Double): Unit =
      addCompensated(value)
      simpleSum += value
      count += 1

    private def addCompensated(value: Double): Unit =
      val tmp = value - compensation
      val velvel = sum + tmp
      compensation = (velvel - sum) - tmp
      sum = velvel

    def combine(other: DoubleSum): Unit =
      addCompensated(other.sum)
      addCompensated(-other.compensation)
      simpleSum += other.simpleSum
      count += other.count

    def result: Double =
      val tmp = sum - compensation
      if java.lang.Double.isNaN(tmp) && java.lang.Double.isInfinite(simpleSum) then simpleSum else tmp
