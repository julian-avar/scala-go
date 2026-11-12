import scalago.go.*

/** Exported functions: Go code calls them as `scalagolib.Greet(...)`. */
object Api:
  @GoExport def greet(name: String): String = s"Hello, $name!"

  @GoExport def sum(xs: GoSlice[GoInt]): GoInt = GoInt(xs.iterator.map(_.toLong).sum)

  /** `@throws`: a Scala exception becomes the Go error result. */
  @GoExport @throws[NumberFormatException] def parse(s: String): Int = s.trim.toInt

  /** Go funcs are Scala functions. */
  @GoExport def twice(f: Int => Int, x: Int): Int = f(f(x))

  /** Without `@throws`, a Scala exception is a Go panic. */
  @GoExport def fail(message: String): Unit = throw new IllegalStateException(message)

  @GoExport def wordCounts(text: String): GoMap[GoString, GoInt] =
    val counts = GoMap.make[GoString, GoInt]
    for w <- text.split(" ") if w.nonEmpty do
      val key = GoString(w)
      counts(key) = GoInt(counts.getOrElse(key, GoInt(0)).toLong + 1)
    counts

  @GoExport def describe(shape: Shape): String = f"${shape.name()} of area ${shape.area()}%.2f"

  @GoExport def largest(shapes: GoSlice[Shape]): Shape =
    shapes.iterator.maxByOption(_.area()).orNull

/** An exported class hierarchy: Go code gets interface values holding the Scala objects. */
@GoExport abstract class Shape:
  @GoExport def area(): Double
  @GoExport def name(): String = "shape"

@GoExport class Circle(radius: Double) extends Shape:
  def area(): Double = math.Pi * radius * radius
  override def name(): String = "circle"

@GoExport class Square(side: Double) extends Shape:
  def area(): Double = side * side
  override def name(): String = "square"

/** Called from many goroutines: `synchronized` makes it safe. */
@GoExport class Counter(start: Int):
  private var count = start
  @GoExport def increment(): Unit = synchronized { count += 1 }
  @GoExport("Value") def current: Int = synchronized(count)
  @GoExport def same(other: Counter): Boolean = this eq other
