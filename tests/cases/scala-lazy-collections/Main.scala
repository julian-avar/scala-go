import scala.collection.{mutable, immutable, BuildFrom, Factory, View}
import scala.jdk.CollectionConverters.*

/** Lazy collections (LazyList, views, iterators), builders and factories, and conversions between
  * Scala collections and to and from Java collections. */
object Main:
  def section(name: String): Unit = println(s"-- $name")

  def main(args: Array[String]): Unit =
    lazyLists()
    views()
    iterators()
    buildersAndFactories()
    customCollection()
    conversions()
    javaConversions()

  def lazyLists(): Unit =
    section("LazyList")
    lazy val fibs: LazyList[BigInt] = BigInt(0) #:: BigInt(1) #:: fibs.zip(fibs.tail).map(_ + _)
    println(fibs.take(15).toList)
    println(fibs(100))
    def sieve(s: LazyList[Int]): LazyList[Int] = s.head #:: sieve(s.tail.filter(_ % s.head != 0))
    println(sieve(LazyList.from(2)).take(12).toList)
    var evaluated = List.empty[Int]
    val traced = LazyList.from(1).map { i => evaluated ::= i; i * i }
    println(traced.takeWhile(_ < 30).toList)
    println(evaluated.reverse)
    val partial = LazyList(1, 2, 3).map(_ * 2)
    println(partial)
    partial.head
    println(partial)
    println(partial.force)
    println(LazyList.iterate(1)(_ * 2).drop(10).head)
    println(LazyList.continually("x").take(3).mkString)
    println(LazyList.unfold(5)(n => Option.when(n > 0)((n, n - 1))).toList)
    println(LazyList.tabulate(5)(i => ('a' + i).toChar).mkString)
    println((1 #:: 2 #:: LazyList.empty).toList ++ LazyList.fill(2)(0))
    println(s"${LazyList.empty[Int].isEmpty} ${LazyList.from(0).isEmpty}")
    val lazyMatch = LazyList(1, 2, 3) match
      case a #:: b #:: _ => a + b
      case _             => -1
    println(lazyMatch)
    // A deep LazyList is consumed without stack growth.
    println(LazyList.range(0L, 1000000L).filter(_ % 3 == 0).sum)
    var sideEffects = 0
    lazy val cached = LazyList.from(1).map { i => sideEffects += 1; i }
    cached.take(5).toList
    cached.take(5).toList
    println(s"evaluated once: $sideEffects")
    println(s"${LazyList(3, 1, 2).sorted.toList} ${LazyList(1, 2, 3).reverse.toList}")
    println(LazyList.from(1).scanLeft(0)(_ + _).take(6).toList)

  def views(): Unit =
    section("View")
    var calls = 0
    val v = (1 to 1000000).view.map { i => calls += 1; i * 2 }.filter(_ % 3 == 0)
    println(s"before: $calls")
    println(v.take(3).toList)
    println(s"after: $calls")
    println(List(1, 2, 3).view.map(_ + 1).reverse.toList)
    println((1 to 10).view.slice(2, 5).map(_ * 10).toVector)
    println(Vector(1, 2, 3).view.zip(List("a", "b")).toList)
    println(Map(1 -> "a", 2 -> "b").view.mapValues(_.toUpperCase).toMap.toList.sorted)
    println(View.fromIteratorProvider(() => Iterator(1, 2)).toList)
    println(View.iterate(1, 5)(_ * 10).toList)
    println((1 to 3).view.flatMap(i => List.fill(i)(i)).mkString)
    println(List(1, 2, 3).view.concat(List(4)).appended(5).prepended(0).toList)
    val strict = List(1, 2, 3).view.map(_ * 2).to(List)
    println(strict)
    println(Array(3, 1, 2).view.sorted.toList)
    println((1 to 5).view.scanLeft(0)(_ + _).toList)
    println(List(1, 2, 3, 4).view.groupBy(_ % 2).view.mapValues(_.toList).toList.sortBy(_._1))
    println((1 to 4).view.tapEach(_ => calls += 1).sum)

  def iterators(): Unit =
    section("Iterator")
    val it = Iterator(1, 2, 3, 4, 5)
    println(s"${it.next()} ${it.hasNext} ${it.toList} ${it.hasNext}")
    println(Iterator.range(0, 10, 3).toList)
    println(Iterator.iterate(1)(_ * 3).takeWhile(_ < 100).toList)
    println(Iterator.continually(7).take(3).toList)
    println(Iterator.unfold(1)(n => Option.when(n < 50)((n, n * 2))).toList)
    println(Iterator.tabulate(4)(i => i * i).toList)
    println(s"${Iterator.fill(2)("z").mkString} ${Iterator.single(1).toList} ${Iterator.empty[Int].hasNext}")
    println(Iterator.from(10).drop(5).next())
    println((1 to 10).iterator.grouped(4).map(_.toList).toList)
    println((1 to 6).iterator.sliding(3, 2).map(_.toList).toList)
    println((1 to 10).iterator.sliding(4, 4).withPartial(false).map(_.toList).toList)
    val (left, right) = Iterator(1, 2, 3).duplicate
    println(s"${left.toList} ${right.map(_ * 10).toList}")
    val buffered = Iterator(1, 2, 3).buffered
    println(s"${buffered.head} ${buffered.head} ${buffered.next()} ${buffered.head}")
    val (small, big) = Iterator(1, 5, 2, 8, 3).partition(_ < 4)
    println(s"${small.toList} ${big.toList}")
    val (prefix, suffix) = Iterator(1, 2, 5, 1).span(_ < 3)
    println(s"${prefix.toList} ${suffix.toList}")
    println(Iterator(1, 2) ++ Iterator(3) concat List(4))
    println((Iterator(1, 2) ++ Iterator(3) ++ List(4)).toList)
    println(Iterator("a", "b", "c").zipWithIndex.toList)
    println(Iterator(1, 2, 3).zipAll(Iterator("x"), 0, "-").toList)
    println(Iterator(1, 2, 3, 4).scanLeft(0)(_ + _).toList)
    println(s"${Iterator(3, 1, 2).distinct.toList} ${Iterator(1, 1, 2, 3, 3).distinctBy(_ / 2).toList}")
    println(s"${Iterator(1, 2, 3, 4, 5).slice(1, 3).toList} ${Iterator(1, 2, 3).padTo(5, 0).toList}")
    println(s"${Iterator(1, 2, 3).sameElements(List(1, 2, 3))} ${Iterator(1, 2, 3).indexOf(2)}")
    println(Iterator("x", "y").collect { case "y" => "Y" }.toList)
    println(s"${Iterator(4, 2, 9).maxOption} ${Iterator.empty[Int].minOption} ${Iterator(1, 2, 3).size}")
    println(Iterator(1, 2).patch(1, Iterator(9, 9), 0).toList)
    println(try Iterator.empty[Int].next() catch case e: NoSuchElementException => s"empty: ${e.getMessage}")
    val custom = new Iterator[Int]:
      private var n = 0
      def hasNext = n < 3
      def next() = { n += 1; n * 100 }
    println(custom.map(_ + 1).toList)
    var count = 0
    val lazily = Iterator.from(1).map { i => count += 1; i }.filter(_ % 2 == 0)
    println(s"${lazily.take(2).toList} $count")
    println(s"${List(1, 2, 3).reverseIterator.toList} ${Vector(1, 2).iterator.knownSize}")

  def buildersAndFactories(): Unit =
    section("Builders and factories")
    val b = List.newBuilder[Int]
    b += 1
    b ++= List(2, 3)
    b.addOne(4)
    println(b.result())
    b.clear()
    println(b.result())
    val vb = Vector.newBuilder[String]
    vb.sizeHint(10)
    for s <- "a b c".split(" ") do vb += s
    println(vb.result())
    val mb = Map.newBuilder[String, Int]
    mb += "a" -> 1
    mb += "a" -> 2
    println(mb.result())
    val sb = Set.newBuilder[Int]
    sb ++= List(3, 3, 1)
    println(sb.result().toList.sorted)
    val ab = Array.newBuilder[Double]
    ab += 1.5
    ab += 2.5
    println(ab.result().mkString(","))
    val mapped = mutable.ArrayBuffer.newBuilder[Int].mapResult(_.sum)
    mapped ++= 1 to 10
    println(mapped.result())
    println((new StringBuilder).addAll("abc").addOne('d').result())

    def fill[C](n: Int)(f: Factory[Int, C]): C = f.fromSpecific(1 to n)
    println(fill(3)(List))
    println(fill(3)(Vector))
    println(fill(3)(immutable.TreeSet))
    println(fill(3)(mutable.ArrayBuffer))
    println(fill(3)(Array).mkString(","))

    def doubled[CC[X] <: Iterable[X], C](xs: CC[Int])(using bf: BuildFrom[CC[Int], Int, C]): C =
      bf.fromSpecific(xs)(xs.map(_ * 2))
    println(doubled(List(1, 2)))
    println(doubled(Vector(3, 4)))
    println(doubled(Set(5)))

    println(s"${List.from(Iterator(1, 2))} ${Vector.empty[Int]} ${List.concat(List(1), Vector(2))}")
    println(s"${Seq.empty[Int]} ${IndexedSeq(1).appended(2)} ${Iterable(1, 2)} ${collection.Seq(3)}")
    println(s"${Map.from(List(1 -> "a"))} ${Set.from("aab")} ${immutable.SortedMap.from(Map(2 -> "b", 1 -> "a"))}")

  /** A collection of our own, built on IterableOnce and StrictOptimizedIterableOps. */
  final class Ring private (elems: Vector[Int])
      extends immutable.Iterable[Int]
      with collection.IterableOps[Int, immutable.Iterable, Ring]
      with collection.IterableFactoryDefaults[Int, immutable.Iterable]:
    def iterator: Iterator[Int] = elems.iterator
    def rotate(n: Int): Ring =
      if elems.isEmpty then this else
        val k = ((n % elems.size) + elems.size) % elems.size
        new Ring(elems.drop(k) ++ elems.take(k))
    override protected def fromSpecific(coll: IterableOnce[Int]): Ring = Ring.fromSpecific(coll)
    override protected def newSpecificBuilder: mutable.Builder[Int, Ring] = Ring.newBuilder
    override def empty: Ring = Ring.empty
    override def className = "Ring"

  object Ring extends collection.SpecificIterableFactory[Int, Ring]:
    def empty: Ring = new Ring(Vector.empty)
    def newBuilder: mutable.Builder[Int, Ring] = Vector.newBuilder[Int].mapResult(new Ring(_))
    def fromSpecific(it: IterableOnce[Int]): Ring = new Ring(Vector.from(it))

  def customCollection(): Unit =
    section("A custom collection")
    val r = Ring(1, 2, 3, 4)
    println(r)
    println(r.rotate(1))
    println(r.rotate(-1).filter(_ != 2))
    println(r.map(_.toString))
    println(r.take(2).rotate(1))
    println(s"${r.sum} ${r.size} ${r.toList} ${(1 to 3).to(Ring)}")

  def conversions(): Unit =
    section("Conversions")
    val xs = List(3, 1, 2, 3)
    println(xs.toVector)
    println(xs.toSet.toList.sorted)
    println(xs.toArray.mkString(","))
    println(xs.toBuffer)
    println(xs.toIndexedSeq)
    println(xs.toSeq)
    println(xs.iterator.to(LazyList))
    println(xs.to(immutable.TreeSet))
    println(xs.to(mutable.ListBuffer))
    println(xs.to(immutable.Queue))
    println(xs.zipWithIndex.toMap.toList.sorted)
    println(xs.zip(xs).to(immutable.TreeMap))
    println(xs.to(mutable.ArrayDeque))
    println(xs.to(immutable.ArraySeq))
    println(s"${"hello".toList} ${"hello".toSet.toList.sorted} ${"hi".toVector}")
    println(s"${Array(1, 2).toList} ${Some(1).toList} ${None.toList}")
    println(s"${Map(1 -> 2).toList} ${Map(1 -> 2).toSeq} ${Map(1 -> 2).toVector}")
    println(Seq(1 -> "a", 2 -> "b").toMap.get(2))
    println(xs.toVector.toList == xs)

  def javaConversions(): Unit =
    section("CollectionConverters")
    val jl: java.util.List[Int] = List(1, 2, 3).asJava
    println(s"${jl.size} ${jl.get(1)} ${jl}")
    println(try { jl.add(4); "added" } catch case e: UnsupportedOperationException => "immutable")
    val buf = mutable.ArrayBuffer(1, 2)
    val jbuf = buf.asJava
    jbuf.add(3)
    jbuf.set(0, 10)
    println(s"$buf $jbuf")
    val al = new java.util.ArrayList[String]()
    al.add("x")
    al.add("y")
    val sl = al.asScala
    sl += "z"
    println(s"$sl ${al.size} ${sl.map(_.toUpperCase)}")
    val jm = new java.util.TreeMap[String, Int]()
    jm.put("b", 2)
    jm.put("a", 1)
    val sm = jm.asScala
    sm("c") = 3
    println(s"$sm ${jm} ${sm.get("a")}")
    val mm = mutable.Map("k" -> 1)
    val jmm = mm.asJava
    jmm.put("j", 2)
    println(s"${mm.toList.sorted} ${jmm.get("k")} ${jmm.containsKey("j")}")
    println(Map("x" -> 1).asJava.get("x"))
    val js = new java.util.TreeSet[Int]()
    js.add(3)
    js.add(1)
    println(s"${js.asScala.toList} ${Set(5).asJava.contains(5)}")
    println(java.util.Arrays.asList(1, 2, 3).asScala.sum)
    println(List("a", "b").asJava.iterator.asScala.toList)
    println(Iterator(1, 2).asJava.next())
    println(s"${java.util.List.of(4, 5).asScala.toList} ${(Seq(1, 2).asJava.asScala eq null)}")
    val roundTrip = buf.asJava.asScala
    println(roundTrip eq buf)
    val jstream = java.util.List.of("p", "q").stream()
    println(jstream.iterator.asScala.mkString)
    import scala.jdk.StreamConverters.*
    println(List(1, 2, 3).asJavaSeqStream.map(_ * 2).toScala(List))
    println(java.util.stream.IntStream.range(0, 4).toScala(Vector))
    import scala.jdk.OptionConverters.*
    println(s"${Some(3).toJava} ${java.util.Optional.of("o").toScala} ${java.util.Optional.empty[Int].toScala}")
    import scala.jdk.FunctionConverters.*
    val f: java.util.function.IntUnaryOperator = ((x: Int) => x + 1).asJava
    println(s"${f.applyAsInt(1)} ${f.asScala(2)}")
