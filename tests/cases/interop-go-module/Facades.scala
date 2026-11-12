import scalago.go.*

@GoImport("example.com/greet")
object greet:
  def New(greeting: String): Greeter = native
  def Describe(shapes: Array[Shape]): Array[String] = native
  def Largest(shapes: Shape*): (Shape, Boolean) = native
  def Divide(a: Int, b: Int): (Int, Int) = native
  def Scale(xs: Array[Long], f: Long => Long): Array[Long] = native
  def Boiling(): Celsius = native
  def Origin(): Point = native
  def Move(p: Point, dx: Int, dy: Int): Point = native
  @GoName("Shout") def shout(s: GoString): GoString = native
  def Panic(msg: String): Unit = native
  def Counts(words: GoSlice[GoString]): GoMap[GoString, Long] = native
  def Adder(n: Int): Long => Long = native
  def Checker(): String => Unit = native
  def Squares(n: Int): GoChan[Long] = native
  def Sum(c: GoChan[Long]): Long = native
  def Increment(p: GoPtr[GoInt]): Unit = native
  def Reverse(xs: GoSlice[GoInt]): Unit = native
  def Total(m: GoMap[GoString, GoInt]): Int = native

  class Greeter:
    def Greeting: String = native
    def Greeting_=(g: String): Unit = native
    def Greet(name: String): String = native
    def Count(): Int = native

  trait Shape:
    def Area(): Double
    def Name(): String

  class Celsius:
    def Fahrenheit(): Double = native

  class Point:
    def X: Int = native
    def Y: Int = native
    def Dist2(): Int = native
