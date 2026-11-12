import scalago.go.*

// Known bug (docs/roadmap.md, M0): `@GoExport` on a `val` or a field is ignored, without an error.
// Exported vals and fields are not supported (docs/_docs/interop/exports.md), so the plugin should
// reject them. When it does, this case fails: replace go-errors.txt by compile-errors.txt.
object Api:
  @GoExport val answer: Int = 42

@GoExport class Point(@GoExport val x: Int):
  @GoExport def sum(): Int = x
