package geometry

// Top-level definitions of package `geometry`: they belong to the object `Geometry$package`.
val Origin: Point = Point(0, 0)
var created = 0
def distance(a: Point, b: Point): Double = math.sqrt(math.pow(a.x - b.x, 2) + math.pow(a.y - b.y, 2))
type Path = List[Point]
opaque type Angle = Double
def degrees(d: Double): Angle = d
extension (a: Angle) def radians: Double = math.toRadians(a)
given Ordering[Point] = Ordering.by(p => (p.x, p.y))
def holderName: String = new Object {}.getClass.getName + " / " + Holder.name
object Holder:
  def name: String = getClass.getName

case class Point(x: Int, y: Int):
  created += 1
  // Refers to a package that refers back to this one.
  def scaled: Point = Point(x * util.Scale.factor, y * util.Scale.factor)

private[geometry] def internal = "package-private"
def exposeInternal: String = internal
