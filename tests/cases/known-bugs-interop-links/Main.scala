import scalago.go.*

// Known bugs of Go interop that are link errors, but should link and run. When one is fixed, this
// case fails: move the use to interop-conversions (with its output), and update the docs.
@GoImport("example.com/defined")
object defined:
  def Count(n: GoSlice[GoString]): Int = native
  def Size(t: GoMap[GoString, GoInt]): Int = native

object Main:
  def main(args: Array[String]): Unit =
    // docs/_docs/spec/interop.md, "Scala to Go": GoSlice and GoMap values are "converted when G
    // is a defined type with the same underlying type". Should print "2 1".
    println(s"${defined.Count(GoSlice.of(go"a", go"b"))} ${defined.Size(GoMap.of(go"k" -> GoInt(1)))}")
