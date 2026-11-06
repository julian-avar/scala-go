import scalago.go.*

class Square(side: Double) extends greet.Shape:
  def Area(): Double = side * side
  def Name(): String = "square"

case class Circle(r: Double) extends greet.Shape:
  def Area(): Double = 3 * r * r
  def Name(): String = s"circle($r)"

object Main:
  def main(args: Array[String]): Unit =
    val g = greet.New("Hello")
    println(g.Greet("Ada"))
    g.Greeting = "Hi"
    println(g.Greet("Grace") + " " + g.Count() + " " + g.Greeting)

    val shapes: Array[greet.Shape] = Array(Square(2), Circle(1))
    greet.Describe(shapes).foreach(println)
    val (largest, found) = greet.Largest(Square(1), Circle(2), Square(3))
    println(s"$found ${largest.Name()}")
    largest match
      case Circle(r) => println(s"a circle of radius $r")
      case other     => println(s"not a circle: ${other.Name()}")
    println(greet.Largest()._2)

    println(greet.Divide(17, 5))
    try greet.Divide(1, 0)
    catch case e: GoException => println(s"${e.getClass.getSimpleName}: ${e.getMessage}")

    val xs = Array(1L, 2L, 3L)
    val ys = greet.Scale(xs, x => x * 10)
    println(s"${xs.mkString(",")} ${ys.mkString(",")} ${xs eq ys}")

    val boiling = greet.Boiling()
    println(boiling.Fahrenheit())
    val p = greet.Move(greet.Origin(), 3, 4)
    println(s"${p.X} ${p.Y} ${p.Dist2()}")
    println(greet.shout("go".toGoString).toJavaString)

    try greet.Panic("boom")
    catch case e: RuntimeException => println(s"recovered: ${e.getMessage}")

    // Go data shared with Go code, and Go funcs as Scala functions.
    val words = GoSlice.of("a b a c a".split(" ").map(GoString(_))*)
    val counts = greet.Counts(words)
    println(counts.iterator.map((k, v) => s"${k.toJavaString}=$v").toSeq.sorted.mkString(" "))
    counts(GoString("z")) = 10
    println(s"total ${greet.Total(counts.asInstanceOf[GoMap[GoString, GoInt]])}")
    val add2 = greet.Adder(2)
    println(s"${add2(40)} ${List(1L, 2L).map(add2)}")
    val check = greet.Checker()
    check("fine")
    try check("")
    catch case e: GoException => println(s"Go func failed: ${e.getMessage}")
    println(s"squares: ${greet.Squares(4).iterator.toList}")
    val ch = GoChan.make[Long](3)
    ch.send(1); ch.send(2); ch.send(3); ch.close()
    println(s"sum: ${greet.Sum(ch)}")
    val n = GoPtr.to(GoInt(41))
    greet.Increment(n)
    println(s"incremented: ${n.get.toLong}")
    val ints = GoSlice.of(GoInt(1), GoInt(2), GoInt(3))
    greet.Reverse(ints)
    println(s"reversed in place: ${ints.iterator.map(_.toLong).mkString(",")}")
