import scalago.go.*

// Limitation (docs/_docs/spec/interop.md, "Generic Go functions"): Scala cannot express Go's
// constraints, so the linker checks the type arguments of a generic facade against them, and a
// type argument outside the constraint is a link error. `any` is not cmp.Ordered, and a slice
// is not comparable. Types within the constraints: interop-generics.
@GoImport("slices")
object slices:
  def Max[E](x: GoSlice[E]): E = native
  def Contains[E](s: GoSlice[E], v: E): Boolean = native

object Main:
  def ordered(): Unit = println(slices.Max(GoSlice.of[Any]("a", "b")))
  def comparable(): Unit = println(slices.Contains[GoSlice[GoInt]](GoSlice.of(GoSlice.of(GoInt(1))), GoSlice.of(GoInt(1))))

  def main(args: Array[String]): Unit =
    ordered()
    comparable()
