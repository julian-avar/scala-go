// Known bug: a string literal containing `math.` after a non-identifier character makes the
// linker import Go's "math" package into the file holding the literals (`Linker.goFile` searches
// the generated code, literals included, for `math.`), and go vet rejects the unused import. On
// the JVM this prints `math.abs`. When fixed, this case fails: replace go-errors.txt by
// expected.txt (and remove go-only.txt), and remove the bug from the roadmap.
object Main:
  def main(args: Array[String]): Unit = println("math.abs")
