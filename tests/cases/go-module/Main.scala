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
