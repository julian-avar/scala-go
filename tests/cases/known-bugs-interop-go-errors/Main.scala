import scalago.go.*

// Known bugs of Go interop: code the linker accepts but Go rejects. Each should be a link error,
// or work. When one is fixed, this case fails: replace go-errors.txt by link-errors.txt, or move
// the use to interop-generics.
//
// A generic facade instantiated with a type argument that does not satisfy the Go constraint
// (any is not cmp.Ordered): Scala cannot express the constraint, and the linker does not check
// it, so Go's compiler rejects the generated call.
@GoImport("slices")
object slices:
  def Max[E](x: GoSlice[E]): E = native

object Main:
  def main(args: Array[String]): Unit =
    println(slices.Max(GoSlice.of[Any]("a", "b")))
