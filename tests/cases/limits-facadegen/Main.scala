import gofacades.shapes

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
