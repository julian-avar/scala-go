abstract class Shape(val name: String) {
  def area: Double
  def describe(): String = name + " with area " + area
}

class Rect(w: Double, h: Double) extends Shape("rect") {
  def area: Double = w * h
}

class Square(side: Double) extends Rect(side, side) {
  override def describe(): String = "square! " + super.describe()
  override def toString: String = "Square(" + side + ")"
}

class Counter {
  private var count = 0
  def increment(): Counter = { count += 1; this }
  def value: Int = count
}

object Registry {
  val greeting: String = "registry ready"
  var lookups = 0
  def lookup(): Int = { lookups += 1; lookups }
}

object Main {
  def main(args: Array[String]): Unit = {
    val shapes = Array[Shape](new Rect(2, 3), new Square(2))
    var i = 0
    while (i < shapes.length) {
      println(shapes(i).describe())
      i += 1
    }
    println(shapes(1))
    println(new Counter().increment().increment().value)
    println(Registry.greeting)
    Registry.lookup()
    println(Registry.lookup())
    println(args.length)
  }
}
