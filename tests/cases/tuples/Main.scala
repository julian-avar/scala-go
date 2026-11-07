// Tuples of primitives: the compiler emits Scala 2's specialized tuple classes for them
// (Tuple2$mcII$sp, _1$mcI$sp), which the IR maps to the generic tuples.
object Main:
  def minMax(xs: Seq[Int]): (Int, Int) = (xs.min, xs.max)
  def divMod(a: Long, b: Long): (Long, Long) = (a / b, a % b)
  def stats(xs: Seq[Double]): (Double, Double) = (xs.sum / xs.size, xs.max)
  def charAndFlag(c: Char): (Char, Boolean) = (c, c.isUpper)
  def mixed(i: Int): (Int, String) = (i, i.toString * 2)

  def main(args: Array[String]): Unit =
    val (lo, hi) = minMax(Seq(3, 9, -2))
    println(s"$lo $hi")
    val t = divMod(17L, 5L)
    println(t._1 + t._2)
    println(stats(Seq(1.5, 2.5)))
    println(charAndFlag('Q'))
    println(mixed(7))
    println(Tuple1(42)._1 + 1)
    val pairs = List((1, 2), (3, 4), (5, 6))
    println(pairs.map { case (a, b) => a * b }.sum)
    println(pairs.unzip)
    println((1 to 3).zip(4 to 6).map(p => p._1 + p._2))
    println(Map(1 -> 2, 3 -> 4).map((k, v) => k + v).toList)
    val swapped: (Int, Int) = minMax(Seq(1, 2)).swap
    println(swapped)
    val any: Any = (1, 2)
    any match
      case (a: Int, b: Int) => println(s"ints $a $b")
      case _                => println("other")
    println((1, 2) == (1, 2) && (1, 2).hashCode == (1, 2).hashCode)
