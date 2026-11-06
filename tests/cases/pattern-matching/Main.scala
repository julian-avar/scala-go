sealed trait Shape
case class Circle(radius: Double) extends Shape
case class Rect(width: Double, height: Double) extends Shape
case object Empty extends Shape

object Main {
  def area(s: Shape): Double = s match {
    case Circle(r)  => 3 * r * r
    case Rect(w, h) => w * h
    case Empty      => 0
  }

  def classify(x: Any): String = x match {
    case 0                       => "zero"
    case i: Int if i < 0         => "negative int"
    case i: Int                  => "int " + i
    case s: String if s.isEmpty  => "empty string"
    case s: String               => "string of " + s.length
    case Circle(r) if r > 10     => "big circle"
    case c: Circle               => "circle " + c.radius
    case (a, b)                  => "pair " + a + "/" + b
    case null                    => "null"
    case _                       => "other"
  }

  def day(n: Int): String = n match {
    case 1 => "Mon"
    case 2 => "Tue"
    case 3 => "Wed"
    case 4 => "Thu"
    case 5 => "Fri"
    case 6 | 7 => "weekend"
    case _ => "?"
  }

  def command(s: String): Int = s match {
    case "start" => 1
    case "stop"  => 2
    case _       => 0
  }

  def main(args: Array[String]): Unit = {
    println(area(Circle(2)) + " " + area(Rect(2, 3)) + " " + area(Empty))
    println(classify(0) + ", " + classify(-5) + ", " + classify(7))
    println(classify("") + ", " + classify("abc"))
    println(classify(Circle(11)) + ", " + classify(Circle(1)))
    println(classify((1, "x")) + ", " + classify(null) + ", " + classify(2.5))
    println(day(1) + " " + day(3) + " " + day(7) + " " + day(9))
    println(command("start") + " " + command("stop") + " " + command("x"))
    val r = Rect(1, 2)
    println(r)
    println(r == Rect(1, 2))
    println(r.copy(height = 5))
    println(Empty)
    println(Circle(1).hashCode == Circle(1).hashCode)
    val opt: Option[Int] = Some(3)
    opt match {
      case Some(v) => println("some " + v)
      case None    => println("none")
    }
  }
}
