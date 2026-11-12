import gofacades.shapes
import scalago.go.{GoByte, GoInt, GoSlice}

// Limitations of generated facades (docs/_docs/interop/generated-facades.md, "What is left out"):
// facade-left-out.txt lists the members of shapes/ that FacadeGen leaves out. When it generates
// one, this case fails: use the member here, and update the docs.
object Main:
  def main(args: Array[String]): Unit =
    println(shapes.Add(1, 2))
    // Struct fields of func type are generated.
    val h = new shapes.Handlers
    h.OnEvent = event => println(s"event: $event")
    shapes.Fire(h, "from Go")
    h.OnEvent("from Scala")
    // Kept: arrays as slices of copies, struct{} as Unit, uint64 constants as their bits.
    println(shapes.Digest().iterator.mkString(","))
    println(shapes.Done().iterator.toList)
    println(shapes.MaxUint64)
    println(shapes.IP(GoSlice.of(GoByte(1), GoByte(2))).Len())
    // Kept: generic functions with type sets, and generic types.
    // Kept: complex numbers, and values of types Scala code cannot name (unexported, internal).
    println(s"${shapes.Complex()} ${shapes.Unexported() != null} ${shapes.Internal() != null}")
    println(s"${shapes.Sum(GoSlice.of[GoInt](GoInt(1), GoInt(2)))} ${shapes.Sum(GoSlice.of[Double](0.5, 0.25))} ${shapes.NewBox(5).Value}")
