import scalago.go.*

// Limitation of exports (docs/_docs/interop/exports.md, "Limitations"): tuple results are a link
// error. When supported, this case fails: export one in go-library, and update the docs.
object Api:
  @GoExport def pair(): (Int, String) = (1, "one")
