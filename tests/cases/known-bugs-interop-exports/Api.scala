import scalago.go.*

// Known bugs (docs/roadmap.md, M0) in exports: two methods exported under one Go name link, and
// the generated Go declares the function twice. It should be a link error. When fixed, this case
// fails: replace go-errors.txt by link-errors.txt, and remove the bug from the roadmap.
object Api:
  @GoExport("Twice") def twice(x: Int): Int = 2 * x
  @GoExport("Twice") def twice(x: String): String = x + x
