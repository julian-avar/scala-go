import scalago.go.*

// Limitations of exports (docs/_docs/interop/exports.md, "Limitations"): the plugin rejects these.
// When one is supported, this case fails: export it in interop-exports (with a Go consumer that
// uses it), and update the docs. Tuple results: limits-interop-exports-tuples.
object Api:
  @GoExport def identity[T](x: T): T = x

/** Go code would implement this trait. */
@GoExport trait Greeter:
  @GoExport def greet(name: String): String

@GoExport class Point(x: Int, y: Int):
  @GoExport def this() = this(0, 0)
  @GoExport def sum(): Int = x + y
