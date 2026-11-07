// Adapted from Scala Native's javalib (Apache-2.0, see javalib/LICENSE-scala-native.md).
package java.util.stream

import java.lang.StringBuilder
import java.util._
import java.util.function._
import java.util.stream.Collector.{Characteristics, IdentityFinish, Impl, NoCharacteristics, castingIdentity, characteristicsSet}

/* scala-go: rewritten to behave as the JDK's where programs can observe it: groupingBy and
 * partitioningBy accumulate into the downstream collectors as elements arrive (in
 * computeIfAbsent order, so HashMaps iterate as on the JDK), toMap's duplicate-key message,
 * compensated sums of doubles, reducing(op)'s Optional.ofNullable, and the order in which
 * finishers run. The concurrent collectors (groupingByConcurrent, toConcurrentMap) collect into
 * ConcurrentHashMaps.
 */
object Collectors {

  private val UnorderedIdentityFinish: Set[Characteristics] =
    characteristicsSet(Array(Characteristics.UNORDERED), identityFinish = true)

  private val ConcurrentIdentityFinish: Set[Characteristics] =
    characteristicsSet(Array(Characteristics.CONCURRENT, Characteristics.UNORDERED), identityFinish = true)

  private def collector[T, A, R](
      supplier: Supplier[A],
      accumulator: BiConsumer[A, T],
      combiner: BinaryOperator[A],
      finisher: Function[A, R],
      characteristics: Set[Characteristics]
  ): Collector[T, AnyRef, R] =
    new Impl[T, A, R](supplier, accumulator, combiner, finisher, characteristics)
      .asInstanceOf[Collector[T, AnyRef, R]]

  private def identityCollector[T, A](
      supplier: Supplier[A],
      accumulator: BiConsumer[A, T],
      combiner: BinaryOperator[A],
      characteristics: Set[Characteristics]
  ): Collector[T, AnyRef, A] =
    collector[T, A, A](supplier, accumulator, combiner, castingIdentity[A, A](), characteristics)

  // ---- Collections ------------------------------------------------------------------------------

  def toCollection[T, C <: Collection[T]](collectionFactory: Supplier[C]): Collector[T, AnyRef, C] =
    identityCollector[T, C](
      collectionFactory,
      (c, t) => c.add(t),
      (r1, r2) => { r1.addAll(r2); r1 },
      IdentityFinish
    )

  def toList[T](): Collector[T, AnyRef, List[T]] =
    identityCollector[T, ArrayList[T]](
      () => new ArrayList[T](),
      (l, t) => l.add(t),
      (l, r) => { l.addAll(r); l },
      IdentityFinish
    ).asInstanceOf[Collector[T, AnyRef, List[T]]]

  // Since: Java 10
  def toUnmodifiableList[T](): Collector[T, AnyRef, List[T]] =
    collector[T, ArrayList[T], List[T]](
      () => new ArrayList[T](),
      (l, t) => l.add(t),
      (l, r) => { l.addAll(r); l },
      l => List.of[T](l.toArray()),
      NoCharacteristics
    )

  def toSet[T](): Collector[T, AnyRef, Set[T]] =
    identityCollector[T, HashSet[T]](
      () => new HashSet[T](),
      (s, t) => s.add(t),
      (l, r) =>
        if (l.size() < r.size()) { r.addAll(l); r }
        else { l.addAll(r); l },
      UnorderedIdentityFinish
    ).asInstanceOf[Collector[T, AnyRef, Set[T]]]

  // Since: Java 10
  def toUnmodifiableSet[T](): Collector[T, AnyRef, Set[T]] =
    collector[T, HashSet[T], Set[T]](
      () => new HashSet[T](),
      (s, t) => s.add(t),
      (l, r) =>
        if (l.size() < r.size()) { r.addAll(l); r }
        else { l.addAll(r); l },
      s => Set.of[T](s.toArray()),
      characteristicsSet(Array(Characteristics.UNORDERED), identityFinish = false)
    )

  // ---- Strings ----------------------------------------------------------------------------------

  def joining(): Collector[CharSequence, AnyRef, String] =
    collector[CharSequence, StringBuilder, String](
      () => new StringBuilder(),
      (sb, cs) => sb.append(cs),
      (r1, r2) => { r1.append(r2); r1 },
      sb => sb.toString(),
      NoCharacteristics
    )

  def joining(delimiter: CharSequence): Collector[CharSequence, AnyRef, String] =
    joining(delimiter, "", "")

  def joining(
      delimiter: CharSequence,
      prefix: CharSequence,
      suffix: CharSequence
  ): Collector[CharSequence, AnyRef, String] = {
    Objects.requireNonNull(delimiter)
    Objects.requireNonNull(prefix)
    Objects.requireNonNull(suffix)
    collector[CharSequence, StringJoiner, String](
      () => new StringJoiner(delimiter, prefix, suffix),
      (sj, cs) => sj.add(cs),
      (sj1, sj2) => sj1.merge(sj2),
      sj => sj.toString(),
      NoCharacteristics
    )
  }

  // ---- Adapting collectors ----------------------------------------------------------------------

  def mapping[T, U, A, R](
      mapper: Function[? >: T, ? <: U],
      downstream: Collector[? >: U, A, R]
  ): Collector[T, AnyRef, R] = {
    val ds = downstream.asInstanceOf[Collector[U, A, R]]
    val accumulator = ds.accumulator()
    collector[T, A, R](
      ds.supplier(),
      (r, t) => accumulator.accept(r, mapper.apply(t)),
      ds.combiner(),
      ds.finisher(),
      ds.characteristics()
    )
  }

  // Since: Java 9
  def flatMapping[T, U, A, R](
      mapper: Function[? >: T, ? <: Stream[? <: U]],
      downstream: Collector[? >: U, A, R]
  ): Collector[T, AnyRef, R] = {
    val ds = downstream.asInstanceOf[Collector[U, A, R]]
    val accumulator = ds.accumulator()
    collector[T, A, R](
      ds.supplier(),
      (r, t) => {
        val result = mapper.apply(t).asInstanceOf[Stream[U]]
        if (result != null) {
          var failure: Throwable = null
          try result.sequential().forEach(u => accumulator.accept(r, u))
          catch {
            case e: Throwable =>
              failure = e
              throw e
          } finally {
            if (failure == null) result.close()
            else
              try result.close()
              catch { case e: Throwable => if (e ne failure) failure.addSuppressed(e) }
          }
        }
      },
      ds.combiner(),
      ds.finisher(),
      ds.characteristics()
    )
  }

  // Since: Java 9
  def filtering[T, A, R](
      predicate: Predicate[? >: T],
      downstream: Collector[? >: T, A, R]
  ): Collector[T, AnyRef, R] = {
    val ds = downstream.asInstanceOf[Collector[T, A, R]]
    val accumulator = ds.accumulator()
    collector[T, A, R](
      ds.supplier(),
      (r, t) => if (predicate.test(t)) accumulator.accept(r, t),
      ds.combiner(),
      ds.finisher(),
      ds.characteristics()
    )
  }

  def collectingAndThen[T, A, R, RR](
      downstream: Collector[T, A, R],
      finisher: Function[R, RR]
  ): Collector[T, A, RR] = {
    var characteristics = downstream.characteristics()
    if (characteristics.contains(Characteristics.IDENTITY_FINISH)) {
      if (characteristics.size() == 1) characteristics = NoCharacteristics
      else {
        val cs = new LinkedHashSet[Characteristics](characteristics)
        cs.remove(Characteristics.IDENTITY_FINISH)
        characteristics = Collections.unmodifiableSet(cs)
      }
    }
    new Impl[T, A, RR](
      downstream.supplier(),
      downstream.accumulator(),
      downstream.combiner(),
      downstream.finisher().andThen(finisher),
      characteristics
    )
  }

  // ---- Reductions -------------------------------------------------------------------------------

  /** A mutable cell, for the reductions to one value. */
  private final class Box[T](var value: T) {
    var present = false
  }

  def counting[T](): Collector[T, AnyRef, java.lang.Long] = summingLong[T](_ => 1L)

  def minBy[T](comparator: Comparator[? >: T]): Collector[T, AnyRef, Optional[T]] =
    reducing[T](BinaryOperator.minBy[T](comparator))

  def maxBy[T](comparator: Comparator[? >: T]): Collector[T, AnyRef, Optional[T]] =
    reducing[T](BinaryOperator.maxBy[T](comparator))

  def summingInt[T](mapper: ToIntFunction[? >: T]): Collector[T, AnyRef, Integer] =
    collector[T, Array[Int], Integer](
      () => new Array[Int](1),
      (a, t) => a(0) += mapper.applyAsInt(t),
      (a, b) => { a(0) += b(0); a },
      a => Integer.valueOf(a(0)),
      NoCharacteristics
    )

  def summingLong[T](mapper: ToLongFunction[? >: T]): Collector[T, AnyRef, java.lang.Long] =
    collector[T, Array[Long], java.lang.Long](
      () => new Array[Long](1),
      (a, t) => a(0) += mapper.applyAsLong(t),
      (a, b) => { a(0) += b(0); a },
      a => java.lang.Long.valueOf(a(0)),
      NoCharacteristics
    )

  /** Compensated (Kahan) summation, as the JDK's. */
  def summingDouble[T](mapper: ToDoubleFunction[? >: T]): Collector[T, AnyRef, java.lang.Double] =
    collector[T, Ops.DoubleSum, java.lang.Double](
      () => new Ops.DoubleSum,
      (a, t) => a.add(mapper.applyAsDouble(t)),
      (a, b) => { a.combine(b); a },
      a => java.lang.Double.valueOf(a.result),
      NoCharacteristics
    )

  def averagingInt[T](mapper: ToIntFunction[? >: T]): Collector[T, AnyRef, java.lang.Double] =
    collector[T, Array[Long], java.lang.Double](
      () => new Array[Long](2),
      (a, t) => { a(0) += mapper.applyAsInt(t); a(1) += 1 },
      (a, b) => { a(0) += b(0); a(1) += b(1); a },
      a => java.lang.Double.valueOf(if (a(1) == 0) 0.0 else a(0).toDouble / a(1)),
      NoCharacteristics
    )

  def averagingLong[T](mapper: ToLongFunction[? >: T]): Collector[T, AnyRef, java.lang.Double] =
    collector[T, Array[Long], java.lang.Double](
      () => new Array[Long](2),
      (a, t) => { a(0) += mapper.applyAsLong(t); a(1) += 1 },
      (a, b) => { a(0) += b(0); a(1) += b(1); a },
      a => java.lang.Double.valueOf(if (a(1) == 0) 0.0 else a(0).toDouble / a(1)),
      NoCharacteristics
    )

  def averagingDouble[T](mapper: ToDoubleFunction[? >: T]): Collector[T, AnyRef, java.lang.Double] =
    collector[T, Ops.DoubleSum, java.lang.Double](
      () => new Ops.DoubleSum,
      (a, t) => a.add(mapper.applyAsDouble(t)),
      (a, b) => { a.combine(b); a },
      a => java.lang.Double.valueOf(if (a.count == 0) 0.0 else a.result / a.count),
      NoCharacteristics
    )

  def reducing[T](identity: T, op: BinaryOperator[T]): Collector[T, AnyRef, T] =
    collector[T, Box[T], T](
      () => new Box[T](identity),
      (a, t) => a.value = op.apply(a.value, t),
      (a, b) => { a.value = op.apply(a.value, b.value); a },
      a => a.value,
      NoCharacteristics
    )

  def reducing[T](op: BinaryOperator[T]): Collector[T, AnyRef, Optional[T]] = {
    Objects.requireNonNull(op)
    collector[T, Box[T], Optional[T]](
      () => new Box[T](null.asInstanceOf[T]),
      (a, t) =>
        if (a.present) a.value = op.apply(a.value, t)
        else {
          a.value = t
          a.present = true
        },
      (a, b) => {
        if (b.present) {
          if (a.present) a.value = op.apply(a.value, b.value)
          else {
            a.value = b.value
            a.present = true
          }
        }
        a
      },
      a => Optional.ofNullable(a.value),
      NoCharacteristics
    )
  }

  def reducing[T, U](
      identity: U,
      mapper: Function[? >: T, ? <: U],
      op: BinaryOperator[U]
  ): Collector[T, AnyRef, U] =
    collector[T, Box[U], U](
      () => new Box[U](identity),
      (a, t) => a.value = op.apply(a.value, mapper.apply(t)),
      (a, b) => { a.value = op.apply(a.value, b.value); a },
      a => a.value,
      NoCharacteristics
    )

  def summarizingInt[T](mapper: ToIntFunction[? >: T]): Collector[T, AnyRef, IntSummaryStatistics] =
    identityCollector[T, IntSummaryStatistics](
      () => new IntSummaryStatistics(),
      (r, t) => r.accept(mapper.applyAsInt(t)),
      (l, r) => { l.combine(r); l },
      IdentityFinish
    )

  def summarizingLong[T](mapper: ToLongFunction[? >: T]): Collector[T, AnyRef, LongSummaryStatistics] =
    identityCollector[T, LongSummaryStatistics](
      () => new LongSummaryStatistics(),
      (r, t) => r.accept(mapper.applyAsLong(t)),
      (l, r) => { l.combine(r); l },
      IdentityFinish
    )

  def summarizingDouble[T](mapper: ToDoubleFunction[? >: T]): Collector[T, AnyRef, DoubleSummaryStatistics] =
    identityCollector[T, DoubleSummaryStatistics](
      () => new DoubleSummaryStatistics(),
      (r, t) => r.accept(mapper.applyAsDouble(t)),
      (l, r) => { l.combine(r); l },
      IdentityFinish
    )

  // Since: Java 12
  def teeing[T, R1, R2, R](
      downstream1: Collector[? >: T, ?, R1],
      downstream2: Collector[? >: T, ?, R2],
      merger: BiFunction[? >: R1, ? >: R2, R]
  ): Collector[T, AnyRef, R] = {
    val ds1 = Objects.requireNonNull(downstream1).asInstanceOf[Collector[T, Any, R1]]
    val ds2 = Objects.requireNonNull(downstream2).asInstanceOf[Collector[T, Any, R2]]
    Objects.requireNonNull(merger)
    val s1 = ds1.supplier()
    val s2 = ds2.supplier()
    val a1 = ds1.accumulator()
    val a2 = ds2.accumulator()
    val c1 = ds1.combiner()
    val c2 = ds2.combiner()
    val f1 = ds1.finisher()
    val f2 = ds2.finisher()

    val cs1 = ds1.characteristics()
    val cs2 = ds2.characteristics()
    val shared = new java.util.ArrayList[Characteristics]()
    if (cs1.contains(Characteristics.UNORDERED) && cs2.contains(Characteristics.UNORDERED))
      shared.add(Characteristics.UNORDERED)
    if (cs1.contains(Characteristics.CONCURRENT) && cs2.contains(Characteristics.CONCURRENT))
      shared.add(Characteristics.CONCURRENT)
    val characteristics =
      if (shared.isEmpty()) NoCharacteristics
      else characteristicsSet(shared.toArray(new Array[Characteristics](0)), identityFinish = false)

    collector[T, Array[Any], R](
      () => Array[Any](s1.get(), s2.get()),
      (p, t) => { a1.accept(p(0), t); a2.accept(p(1), t) },
      (p, q) => { p(0) = c1.apply(p(0), q(0)); p(1) = c2.apply(p(1), q(1)); p },
      p => merger.apply(f1.apply(p(0)), f2.apply(p(1))),
      characteristics
    )
  }

  // ---- Maps -------------------------------------------------------------------------------------

  def groupingBy[T, K](
      classifier: Function[? >: T, ? <: K]
  ): Collector[T, AnyRef, Map[K, List[T]]] =
    groupingBy[T, K, AnyRef, List[T]](classifier, toList[T]())

  def groupingBy[T, K, A, D](
      classifier: Function[? >: T, ? <: K],
      downstream: Collector[? >: T, A, D]
  ): Collector[T, AnyRef, Map[K, D]] =
    groupingBy[T, K, D, A, HashMap[K, D]](classifier, () => new HashMap[K, D](), downstream)
      .asInstanceOf[Collector[T, AnyRef, Map[K, D]]]

  def groupingBy[T, K, D, A, M <: Map[K, D]](
      classifier: Function[? >: T, ? <: K],
      mapFactory: Supplier[M],
      downstream: Collector[? >: T, A, D]
  ): Collector[T, AnyRef, M] = {
    Objects.requireNonNull(classifier)
    Objects.requireNonNull(mapFactory)
    val ds = Objects.requireNonNull(downstream).asInstanceOf[Collector[T, A, D]]
    val downstreamSupplier = ds.supplier()
    val downstreamAccumulator = ds.accumulator()
    val downstreamCombiner = ds.combiner()
    val newContainer: Function[K, A] = _ => downstreamSupplier.get()
    val accumulator: BiConsumer[Map[K, A], T] = (m, t) => {
      val key = Objects.requireNonNull(classifier.apply(t), "element cannot be mapped to a null key")
      downstreamAccumulator.accept(m.computeIfAbsent(key, newContainer), t)
    }
    val merger: BinaryOperator[Map[K, A]] = (m1, m2) => {
      m2.forEach((k, v) => m1.merge(k, v, downstreamCombiner))
      m1
    }
    val supplier = mapFactory.asInstanceOf[Supplier[Map[K, A]]]
    if (ds.characteristics().contains(Characteristics.IDENTITY_FINISH))
      collector[T, Map[K, A], M](supplier, accumulator, merger, castingIdentity[Map[K, A], M](), IdentityFinish)
    else {
      val downstreamFinisher = ds.finisher().asInstanceOf[Function[A, A]]
      collector[T, Map[K, A], M](
        supplier,
        accumulator,
        merger,
        m => {
          m.replaceAll((_, v) => downstreamFinisher.apply(v))
          m.asInstanceOf[M]
        },
        NoCharacteristics
      )
    }
  }

  /** The map of `partitioningBy`: always both keys, `false` first. */
  private final class Partition[T](val forTrue: T, val forFalse: T) extends AbstractMap[java.lang.Boolean, T] {
    override def containsKey(key: Any): Boolean = key.isInstanceOf[java.lang.Boolean]

    override def get(key: Any): T = key match {
      case b: java.lang.Boolean => if (b.booleanValue()) forTrue else forFalse
      case _                    => null.asInstanceOf[T]
    }

    def entrySet(): Set[Map.Entry[java.lang.Boolean, T]] =
      new AbstractSet[Map.Entry[java.lang.Boolean, T]] {
        def iterator(): Iterator[Map.Entry[java.lang.Boolean, T]] = {
          val falseEntry: Map.Entry[java.lang.Boolean, T] =
            new AbstractMap.SimpleImmutableEntry[java.lang.Boolean, T](java.lang.Boolean.FALSE, forFalse)
          val trueEntry: Map.Entry[java.lang.Boolean, T] =
            new AbstractMap.SimpleImmutableEntry[java.lang.Boolean, T](java.lang.Boolean.TRUE, forTrue)
          List.of[Map.Entry[java.lang.Boolean, T]](falseEntry, trueEntry).iterator()
        }
        def size(): Int = 2
      }
  }

  def partitioningBy[T](
      predicate: Predicate[? >: T]
  ): Collector[T, AnyRef, Map[java.lang.Boolean, List[T]]] =
    partitioningBy[T, List[T], AnyRef](predicate, toList[T]())

  def partitioningBy[T, D, A](
      predicate: Predicate[? >: T],
      downstream: Collector[? >: T, A, D]
  ): Collector[T, AnyRef, Map[java.lang.Boolean, D]] = {
    Objects.requireNonNull(predicate)
    val ds = Objects.requireNonNull(downstream).asInstanceOf[Collector[T, A, D]]
    val downstreamAccumulator = ds.accumulator()
    val op = ds.combiner()
    collector[T, Partition[A], Map[java.lang.Boolean, D]](
      () => new Partition[A](ds.supplier().get(), ds.supplier().get()),
      (p, t) => downstreamAccumulator.accept(if (predicate.test(t)) p.forTrue else p.forFalse, t),
      (l, r) => new Partition[A](op.apply(l.forTrue, r.forTrue), op.apply(l.forFalse, r.forFalse)),
      if (ds.characteristics().contains(Characteristics.IDENTITY_FINISH))
        p => p.asInstanceOf[Map[java.lang.Boolean, D]]
      else {
        val finisher = ds.finisher()
        p => new Partition[D](finisher.apply(p.forTrue), finisher.apply(p.forFalse))
      },
      if (ds.characteristics().contains(Characteristics.IDENTITY_FINISH)) IdentityFinish
      else NoCharacteristics
    )
  }

  private def duplicateKeyException(k: Any, u: Any, v: Any): IllegalStateException =
    new IllegalStateException(
      "Duplicate key " + k + " (attempted merging values " + u + " and " + v + ")"
    )

  // (javalib's ConcurrentHashMap bounds its type parameters by AnyRef; erased, they are the same.)
  private def newConcurrentMap[K, V](): concurrent.ConcurrentMap[K, V] =
    new concurrent.ConcurrentHashMap[AnyRef, AnyRef]().asInstanceOf[concurrent.ConcurrentMap[K, V]]

  def toConcurrentMap[T, K, U](
      keyMapper: Function[? >: T, ? <: K],
      valueMapper: Function[? >: T, ? <: U]
  ): Collector[T, AnyRef, concurrent.ConcurrentMap[K, U]] = {
    Objects.requireNonNull(keyMapper)
    Objects.requireNonNull(valueMapper)
    identityCollector[T, concurrent.ConcurrentMap[K, U]](
      () => newConcurrentMap[K, U](),
      (map, element) => {
        val k = keyMapper.apply(element)
        val v = Objects.requireNonNull(valueMapper.apply(element))
        val u = map.putIfAbsent(k, v)
        if (u != null) throw duplicateKeyException(k, u, v)
      },
      (m1, m2) => {
        m2.forEach { (k, v) =>
          val u = m1.putIfAbsent(k, Objects.requireNonNull(v))
          if (u != null) throw duplicateKeyException(k, u, v)
        }
        m1
      },
      ConcurrentIdentityFinish
    ).asInstanceOf[Collector[T, AnyRef, concurrent.ConcurrentMap[K, U]]]
  }

  def toConcurrentMap[T, K, U](
      keyMapper: Function[? >: T, ? <: K],
      valueMapper: Function[? >: T, ? <: U],
      mergeFunction: BinaryOperator[U]
  ): Collector[T, AnyRef, concurrent.ConcurrentMap[K, U]] =
    toConcurrentMap[T, K, U, concurrent.ConcurrentMap[K, U]](keyMapper, valueMapper, mergeFunction,
      () => newConcurrentMap[K, U]())
      .asInstanceOf[Collector[T, AnyRef, concurrent.ConcurrentMap[K, U]]]

  def toConcurrentMap[T, K, U, M <: concurrent.ConcurrentMap[K, U]](
      keyMapper: Function[? >: T, ? <: K],
      valueMapper: Function[? >: T, ? <: U],
      mergeFunction: BinaryOperator[U],
      mapFactory: Supplier[M]
  ): Collector[T, AnyRef, M] =
    toMap[T, K, U, M](keyMapper, valueMapper, mergeFunction, mapFactory)

  def groupingByConcurrent[T, K](
      classifier: Function[? >: T, ? <: K]
  ): Collector[T, AnyRef, concurrent.ConcurrentMap[K, List[T]]] =
    groupingByConcurrent[T, K, AnyRef, List[T]](classifier, toList[T]())

  def groupingByConcurrent[T, K, A, D](
      classifier: Function[? >: T, ? <: K],
      downstream: Collector[? >: T, A, D]
  ): Collector[T, AnyRef, concurrent.ConcurrentMap[K, D]] =
    groupingBy[T, K, D, A, concurrent.ConcurrentMap[K, D]](classifier, () => newConcurrentMap[K, D](), downstream)
      .asInstanceOf[Collector[T, AnyRef, concurrent.ConcurrentMap[K, D]]]

  def groupingByConcurrent[T, K, A, D, M <: concurrent.ConcurrentMap[K, D]](
      classifier: Function[? >: T, ? <: K],
      mapFactory: Supplier[M],
      downstream: Collector[? >: T, A, D]
  ): Collector[T, AnyRef, M] =
    groupingBy[T, K, D, A, M](classifier, mapFactory, downstream)

  def toMap[T, K, U](
      keyMapper: Function[? >: T, ? <: K],
      valueMapper: Function[? >: T, ? <: U]
  ): Collector[T, AnyRef, Map[K, U]] = {
    Objects.requireNonNull(keyMapper)
    Objects.requireNonNull(valueMapper)
    identityCollector[T, HashMap[K, U]](
      () => new HashMap[K, U](),
      (map, element) => {
        val k = keyMapper.apply(element)
        val v = Objects.requireNonNull(valueMapper.apply(element))
        val u = map.putIfAbsent(k, v)
        if (u != null) throw duplicateKeyException(k, u, v)
      },
      (m1, m2) => {
        m2.forEach { (k, v) =>
          val u = m1.putIfAbsent(k, Objects.requireNonNull(v))
          if (u != null) throw duplicateKeyException(k, u, v)
        }
        m1
      },
      IdentityFinish
    ).asInstanceOf[Collector[T, AnyRef, Map[K, U]]]
  }

  def toMap[T, K, U](
      keyMapper: Function[? >: T, ? <: K],
      valueMapper: Function[? >: T, ? <: U],
      mergeFunction: BinaryOperator[U]
  ): Collector[T, AnyRef, Map[K, U]] =
    toMap[T, K, U, HashMap[K, U]](keyMapper, valueMapper, mergeFunction, () => new HashMap[K, U]())
      .asInstanceOf[Collector[T, AnyRef, Map[K, U]]]

  def toMap[T, K, U, M <: Map[K, U]](
      keyMapper: Function[? >: T, ? <: K],
      valueMapper: Function[? >: T, ? <: U],
      mergeFunction: BinaryOperator[U],
      mapFactory: Supplier[M]
  ): Collector[T, AnyRef, M] = {
    Objects.requireNonNull(keyMapper)
    Objects.requireNonNull(valueMapper)
    Objects.requireNonNull(mergeFunction)
    Objects.requireNonNull(mapFactory)
    identityCollector[T, M](
      mapFactory,
      (map, element) => map.merge(keyMapper.apply(element), valueMapper.apply(element), mergeFunction),
      (m1, m2) => {
        m2.forEach((k, v) => m1.merge(k, v, mergeFunction))
        m1
      },
      IdentityFinish
    )
  }

  // Since: Java 10
  def toUnmodifiableMap[T, K, U](
      keyMapper: Function[? >: T, ? <: K],
      valueMapper: Function[? >: T, ? <: U]
  ): Collector[T, AnyRef, Map[K, U]] = {
    Objects.requireNonNull(keyMapper, "keyMapper")
    Objects.requireNonNull(valueMapper, "valueMapper")
    collectingAndThen[T, AnyRef, Map[K, U], Map[K, U]](
      toMap[T, K, U](keyMapper, valueMapper),
      map => Map.copyOf[K, U](map)
    )
  }

  // Since: Java 10
  def toUnmodifiableMap[T, K, U](
      keyMapper: Function[? >: T, ? <: K],
      valueMapper: Function[? >: T, ? <: U],
      mergeFunction: BinaryOperator[U]
  ): Collector[T, AnyRef, Map[K, U]] = {
    Objects.requireNonNull(keyMapper, "keyMapper")
    Objects.requireNonNull(valueMapper, "valueMapper")
    Objects.requireNonNull(mergeFunction, "mergeFunction")
    collectingAndThen[T, AnyRef, Map[K, U], Map[K, U]](
      toMap[T, K, U](keyMapper, valueMapper, mergeFunction),
      map => Map.copyOf[K, U](map)
    )
  }
}
