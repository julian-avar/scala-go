package app

import geometry.{*, given}
import geometry.util.bounds

// Top-level definitions (docs/_docs/spec/language.md, section 9): packages exist only in class
// names, packages may depend on each other cyclically, top-level definitions belong to
// `$package` objects, and the entry point is the class generated for a @main method, here with
// parameters (none are given).
object Settings:
  println("Settings: initialized")
  val scale = 10

package object legacy:
  def helper(n: Int): String = s"legacy helper $n"
  val legacyName = new Object {}.getClass.getName

@main def start(names: String*): Unit =
  println(s"arguments: ${names.length}")
  val path: Path = List(Point(3, 4), Point(-1, 2), Point(5, -6))
  println(s"top-level val and def: $Origin ${distance(Origin, Point(3, 4))}")
  println(s"bounds: ${bounds(path)}")
  println(s"top-level given: ${path.sorted} ${path.max}")
  println(s"top-level opaque type: ${"%.4f".format(degrees(180).radians)}")
  println("before scaling")
  println(s"cyclic packages: ${Point(1, 2).scaled}")
  println(s"top-level var: $created")
  println(s"class names: ${Point(0, 0).getClass.getName} ${Settings.getClass.getName} $holderName")
  println(s"anonymous class in a package: ${util.packageObjectName}")
  println(s"package-private: $exposeInternal")
  println(s"package object: ${legacy.helper(1)} ${legacy.legacyName}")
  println(s"main class: ${Class.forName("app.start").getName}")
