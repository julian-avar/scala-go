/** Small CPU benchmarks covering what typical Scala code spends its time on: allocation and
 *  pattern matching, numeric loops, immutable and mutable collections, strings, virtual
 *  dispatch, closures, exceptions, monitors, java.util.stream and java.time. Each prints its
 *  time and a checksum (which must agree between the JVM and Go).
 */
import scala.collection.mutable

sealed trait Tree
final case class Node(l: Tree, r: Tree) extends Tree
case object Leaf extends Tree

trait Shape { def area: Double }
final class Circle(r: Double) extends Shape { def area = math.Pi * r * r }
final class Rect(w: Double, h: Double) extends Shape { def area = w * h }
final class Sq(s: Double) extends Shape { def area = s * s }

object Main:
  def make(d: Int): Tree = if d == 0 then Leaf else Node(make(d - 1), make(d - 1))
  def check(t: Tree): Int = t match
    case Node(l, r) => 1 + check(l) + check(r)
    case Leaf       => 1

  def binaryTrees(): Long =
    var total = 0L
    for i <- 0 until 20 do total += check(make(18))
    total

  def nbodyLike(): Double =
    val n = 1000
    val xs = Array.tabulate(n)(i => i * 0.5)
    val vs = new Array[Double](n)
    var step = 0
    while step < 200 do
      var i = 0
      while i < n do
        var j = 0
        var acc = 0.0
        while j < n do
          val d = xs(j) - xs(i)
          acc += d / (d * d + 1.0)
          j += 1
        vs(i) += acc * 1e-6
        i += 1
      i = 0
      while i < n do { xs(i) += vs(i); i += 1 }
      step += 1
    xs.sum

  def collections(): Long =
    var sum = 0L
    for k <- 0 until 30 do
      val xs = (1 to 100000).toList
      val m = xs.groupBy(_ % 1000).view.mapValues(_.sum).toMap
      val v = xs.toVector.map(_ * 2).filter(_ % 3 == 0)
      sum += m.values.sum + v.length + xs.sorted(Ordering[Int].reverse).head
    sum

  def mutableColls(): Long =
    val m = mutable.HashMap.empty[Int, Int]
    val b = mutable.ArrayBuffer.empty[Int]
    for i <- 0 until 2000000 do
      m(i % 50000) = m.getOrElse(i % 50000, 0) + i
      b += i
    m.size.toLong + b.sum

  def strings(): Int =
    var total = 0
    for k <- 0 until 200 do
      val sb = new StringBuilder
      for i <- 0 until 10000 do sb.append(i).append(',')
      val s = sb.toString
      total += s.split(",").length + s.indexOf("9999") + s.hashCode % 7
    total

  def dispatch(): Double =
    val shapes: Array[Shape] = Array.tabulate(1000)(i => (i % 3) match
      case 0 => Circle(i)
      case 1 => Rect(i, 2)
      case _ => Sq(i))
    var acc = 0.0
    for _ <- 0 until 20000 do
      var i = 0
      while i < shapes.length do { acc += shapes(i).area; i += 1 }
    acc

  def closures(): Long =
    var acc = 0L
    for k <- 0 until 50 do
      acc += (0 until 200000).iterator.map(_ + k).filter(_ % 3 != 0).foldLeft(0L)(_ + _)
    acc

  def exceptions(): Int =
    var n = 0
    for i <- 0 until 200000 do
      try { if i % 2 == 0 then throw new IllegalStateException("x") else n += 1 }
      catch case _: IllegalStateException => n += 2
    n

  def synchronizedLoop(): Int =
    val lock = new Object
    var n = 0
    for i <- 0 until 2000000 do lock.synchronized { n += 1 }
    n

  val boxedInts: java.util.List[Integer] =
    val l = new java.util.ArrayList[Integer]()
    for i <- 0 until 200000 do l.add(i)
    l

  /** A java.util.stream pipeline over boxed elements, into a collector. */
  def streams(): Long =
    var acc = 0L
    for k <- 0 until 50 do
      val out = boxedInts.stream().map(i => i * 2 + k).filter(i => i % 3 != 0)
        .collect(java.util.stream.Collectors.toList())
      acc += out.size + out.get(out.size - 1)
    acc

  /** Primitive streams: nothing boxed per element. */
  def intStreams(): Long =
    var acc = 0L
    for k <- 0 until 20 do
      acc += java.util.stream.IntStream.range(0, 1000000).map(_ * 3 + k).filter(_ % 7 != 0).asLongStream().sum()
      acc += java.util.stream.LongStream.rangeClosed(1, 1000000).map(_ ^ k).max().getAsLong
    acc

  def collectors(): Long =
    import java.util.stream.Collectors
    var acc = 0L
    for k <- 0 until 20 do
      val groups = boxedInts.stream().collect(Collectors.groupingBy((i: Integer) => i % 1000, Collectors.counting()))
      val joined = boxedInts.stream().limit(20000).map(_.toString).collect(Collectors.joining(","))
      acc += groups.size + groups.get(k) + joined.length
    acc

  def localDates(): Long =
    var d = java.time.LocalDate.of(2000, 1, 1)
    var acc = 0L
    for i <- 0 until 3000000 do
      d = d.plusDays(1 + i % 3)
      acc += d.getDayOfMonth + d.getDayOfWeek.getValue
    acc + d.toEpochDay

  def instants(): Long =
    var t = java.time.Instant.EPOCH
    val step = java.time.Duration.ofMillis(1234)
    var total = java.time.Duration.ZERO
    for i <- 0 until 3000000 do
      t = t.plus(step)
      if i % 1000 == 0 then total = total.plus(java.time.Duration.between(java.time.Instant.EPOCH, t))
    t.getEpochSecond + total.getSeconds

  def dateFormat(): Long =
    val f = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    var dt = java.time.LocalDateTime.of(2024, 1, 1, 0, 0)
    var acc = 0L
    for i <- 0 until 100000 do
      val s = dt.format(f)
      acc += java.time.LocalDateTime.parse(s, f).getMinute + s.length
      dt = dt.plusMinutes(7)
    acc

  def time[A](name: String)(f: => A): Unit =
    val t0 = System.nanoTime()
    val r = f
    val ms = (System.nanoTime() - t0) / 1000000
    println(f"$name%-16s $ms%6d ms  ($r)")

  def main(args: Array[String]): Unit =
    val rounds = if args.nonEmpty then args(0).toInt else 3
    for round <- 1 to rounds do
      println(s"-- round $round")
      time("binaryTrees")(binaryTrees())
      time("nbodyLike")(nbodyLike())
      time("collections")(collections())
      time("mutableColls")(mutableColls())
      time("strings")(strings())
      time("dispatch")(dispatch())
      time("closures")(closures())
      time("exceptions")(exceptions())
      time("synchronized")(synchronizedLoop())
      time("streams")(streams())
      time("intStreams")(intStreams())
      time("collectors")(collectors())
      time("localDates")(localDates())
      time("instants")(instants())
      time("dateFormat")(dateFormat())
