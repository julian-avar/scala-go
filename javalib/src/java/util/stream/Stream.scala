// Adapted from Scala Native's javalib (Apache-2.0, see javalib/LICENSE-scala-native.md).
package java.util.stream

import java.util._
import java.util.function._

/* scala-go: the operations are implemented by ReferencePipeline. The default methods here (Java's
 * defaults) only serve other implementations of Stream; they are written, as the JDK's, in terms
 * of the abstract methods.
 */
trait Stream[T] extends BaseStream[T, Stream[T]] {

  def allMatch(pred: Predicate[? >: T]): Boolean

  def anyMatch(pred: Predicate[? >: T]): Boolean

  def collect[R, A](collector: Collector[? >: T, A, R]): R

  def collect[R](
      supplier: Supplier[R],
      accumulator: BiConsumer[R, ? >: T],
      combiner: BiConsumer[R, R]
  ): R

  def count(): Long

  def distinct(): Stream[T]

  // Since: Java 9
  def dropWhile(pred: Predicate[? >: T]): Stream[T] = {
    Objects.requireNonNull(pred)
    val spliter = this.spliterator()
    val spl = new Spliterators.AbstractSpliterator[T](
      Long.MaxValue,
      spliter.characteristics() & ~(Spliterator.SIZED | Spliterator.SUBSIZED)
    ) {
      private var dropping = true
      def tryAdvance(action: Consumer[? >: T]): Boolean =
        if (!dropping) spliter.tryAdvance(action)
        else {
          var found = false
          while (!found && spliter.tryAdvance { e =>
                if (!pred.test(e)) {
                  dropping = false
                  found = true
                  action.accept(e)
                }
              }) ()
          found
        }
    }
    StreamSupport.stream(spl, isParallel()).onClose(() => close())
  }

  def filter(pred: Predicate[? >: T]): Stream[T]

  def findAny(): Optional[T]

  def findFirst(): Optional[T]

  def flatMap[R](mapper: Function[? >: T, ? <: Stream[? <: R]]): Stream[R]

  def flatMapToDouble(mapper: Function[? >: T, ? <: DoubleStream]): DoubleStream

  def flatMapToInt(mapper: Function[? >: T, ? <: IntStream]): IntStream

  def flatMapToLong(mapper: Function[? >: T, ? <: LongStream]): LongStream

  def forEach(action: Consumer[? >: T]): Unit

  def forEachOrdered(action: Consumer[? >: T]): Unit

  def limit(maxSize: Long): Stream[T]

  def map[R](mapper: Function[? >: T, ? <: R]): Stream[R]

  // Since: Java 16
  def mapMulti[R](mapper: BiConsumer[? >: T, ? >: Consumer[R]]): Stream[R] =
    flatMap { (e: T) =>
      val buffer = new ArrayList[R]()
      mapper.asInstanceOf[BiConsumer[T, Consumer[R]]].accept(e, r => buffer.add(r))
      buffer.stream()
    }

  // Since: Java 16
  def mapMultiToDouble(mapper: BiConsumer[? >: T, ? >: DoubleConsumer]): DoubleStream =
    flatMapToDouble { (e: T) =>
      val buffer = DoubleStream.builder()
      mapper.asInstanceOf[BiConsumer[T, DoubleConsumer]].accept(e, buffer)
      buffer.build()
    }

  // Since: Java 16
  def mapMultiToInt(mapper: BiConsumer[? >: T, ? >: IntConsumer]): IntStream =
    flatMapToInt { (e: T) =>
      val buffer = IntStream.builder()
      mapper.asInstanceOf[BiConsumer[T, IntConsumer]].accept(e, buffer)
      buffer.build()
    }

  // Since: Java 16
  def mapMultiToLong(mapper: BiConsumer[? >: T, ? >: LongConsumer]): LongStream =
    flatMapToLong { (e: T) =>
      val buffer = LongStream.builder()
      mapper.asInstanceOf[BiConsumer[T, LongConsumer]].accept(e, buffer)
      buffer.build()
    }

  def mapToDouble(mapper: ToDoubleFunction[? >: T]): DoubleStream

  def mapToInt(mapper: ToIntFunction[? >: T]): IntStream

  def mapToLong(mapper: ToLongFunction[? >: T]): LongStream

  def max(comparator: Comparator[? >: T]): Optional[T]

  def min(comparator: Comparator[? >: T]): Optional[T]

  def noneMatch(pred: Predicate[? >: T]): Boolean

  def peek(action: Consumer[? >: T]): Stream[T]

  def reduce(accumulator: BinaryOperator[T]): Optional[T]

  def reduce(identity: T, accumulator: BinaryOperator[T]): T

  def reduce[U](
      identity: U,
      accumulator: BiFunction[U, ? >: T, U],
      combiner: BinaryOperator[U]
  ): U

  def skip(n: Long): Stream[T]

  def sorted(): Stream[T]

  def sorted(comparator: Comparator[? >: T]): Stream[T]

  // Since: Java 9
  def takeWhile(pred: Predicate[? >: T]): Stream[T] = {
    Objects.requireNonNull(pred)
    val spliter = this.spliterator()
    val spl = new Spliterators.AbstractSpliterator[T](
      Long.MaxValue,
      spliter.characteristics() & ~(Spliterator.SIZED | Spliterator.SUBSIZED)
    ) {
      private var taking = true
      def tryAdvance(action: Consumer[? >: T]): Boolean = {
        var taken = false
        taking && spliter.tryAdvance { e =>
          if (pred.test(e)) {
            taken = true
            action.accept(e)
          } else taking = false
        } && taken
      }
    }
    StreamSupport.stream(spl, isParallel()).onClose(() => close())
  }

  def toArray(): Array[Object]

  def toArray[A <: Object](generator: IntFunction[Array[A]]): Array[A]

  // Since: Java 16
  def toList(): List[T] =
    Collections.unmodifiableList(new ArrayList[T](Arrays.asList(this.toArray()).asInstanceOf[List[T]]))
}

object Stream {
  trait Builder[T] extends Consumer[T] {
    def accept(t: T): Unit
    def add(t: T): Builder[T] = {
      accept(t)
      this
    }
    def build(): Stream[T]
  }

  def builder[T](): Builder[T] = new Streams.RefBuilder[T]

  def concat[T](a: Stream[? <: T], b: Stream[? <: T]): Stream[T] =
    Streams.concat(a, b)

  def empty[T](): Stream[T] =
    StreamSupport.stream(Spliterators.emptySpliterator[T](), false)

  def generate[T](s: Supplier[? <: T]): Stream[T] = {
    Objects.requireNonNull(s)
    StreamSupport.stream(new Streams.Generate[T](s), false)
  }

  // Since: Java 9
  def iterate[T](
      seed: T,
      hasNext: Predicate[? >: T],
      next: UnaryOperator[T]
  ): Stream[T] = {
    Objects.requireNonNull(next)
    Objects.requireNonNull(hasNext)
    StreamSupport.stream(new Streams.Iterate[T](seed, hasNext, next), false)
  }

  def iterate[T](seed: T, f: UnaryOperator[T]): Stream[T] = {
    Objects.requireNonNull(f)
    StreamSupport.stream(new Streams.Iterate[T](seed, null, f), false)
  }

  def of[T](values: Array[Object]): Stream[T] =
    Arrays.stream(values).asInstanceOf[Stream[T]]

  def of[T](t: Object): Stream[T] =
    Arrays.stream(Array[Object](t)).asInstanceOf[Stream[T]]

  // Since: Java 9
  def ofNullable[T](t: T): Stream[T] =
    if (t == null) Stream.empty[T]()
    else Stream.of[T](t.asInstanceOf[Object])
}
