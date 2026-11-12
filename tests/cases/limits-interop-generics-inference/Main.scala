import scalago.go.*
import scalago.gostd.slices

// A limitation of generic facades (docs/_docs/interop/facades.md, "Generics"): a compile error.
// When it compiles, this case fails: move the use to interop-gostd-generics, and update the docs.
object Main:
  def main(args: Array[String]): Unit =
    // GoSlice.of in an argument whose element type is a type parameter: Scala cannot choose its
    // Go element type (GoType[E]). Write GoSlice.of[GoString](...).
    println(slices.Max(GoSlice.of(GoString("a"), GoString("b"))))
