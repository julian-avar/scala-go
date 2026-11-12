package geometry
package util

// A nested package clause: members of `geometry` are visible without an import.
object Scale:
  def factor: Int = app.Settings.scale

def bounds(path: Path): (Point, Point) =
  (Point(path.map(_.x).min, path.map(_.y).min), Point(path.map(_.x).max, path.map(_.y).max))

def packageObjectName: String = new Object {}.getClass.getName
