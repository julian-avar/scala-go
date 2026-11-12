import scalago.go.*

// Known bugs of Go interop: code the linker accepts but Go rejects. Each should be a link error,
// or work. When one is fixed, this case fails: replace go-errors.txt by link-errors.txt, or move
// the use to interop-generics.
//
// A facade of a generic function whose Scala types give one type parameter two Go types: T is
// int from GoSlice[GoInt], and int64 from the function's Long (interop-generics declares
// `f: GoInt => GoString`). Go's type inference fails on the generated call.
@GoImport("example.com/infer")
object infer:
  def Map(xs: GoSlice[GoInt], f: Long => GoString): GoSlice[GoString] = native

object Main:
  def main(args: Array[String]): Unit =
    println(infer.Map(GoSlice.of(GoInt(1)), n => go"#$n").length)
