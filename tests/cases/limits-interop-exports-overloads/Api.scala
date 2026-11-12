import scalago.go.*

// Limitation of exports (docs/_docs/interop/exports.md, "Limitations"): Go has no overloading, so
// two exports under one Go name are a link error. Distinct names: interop-exports.
object Api:
  @GoExport("Twice") def twice(x: Int): Int = 2 * x
  @GoExport("Twice") def twice(x: String): String = x + x
