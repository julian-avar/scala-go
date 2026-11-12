// Known bug: a class of the empty package named like a file the linker generates (entry.go,
// support.go), up to case, goes to a Go file whose name collides with it on case-insensitive
// file systems, and Go rejects the package. On the JVM this prints 1. When fixed, this case
// fails: replace go-errors.txt by expected.txt (and remove go-only.txt), and update the docs.
class Entry(val n: Int)

object Main:
  def main(args: Array[String]): Unit = println(Entry(1).n)
