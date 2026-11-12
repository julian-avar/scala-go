import scalago.go.*
import scalago.gostd.reflect

// A limitation of generic facades (docs/_docs/interop/facades.md, "Generics"): a compile error.
// When it compiles, this case fails: move the use to interop-gostd-generics, and update the docs.
object Main:
  def main(args: Array[String]): Unit =
    // A type argument Scala cannot infer is Nothing: no Go type stands for it. Give it.
    println(reflect.TypeFor().String())
