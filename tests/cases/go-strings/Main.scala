import scalago.go.*

object Main {
  def main(args: Array[String]): Unit = {
    val s = "héllo, wörld"
    val g = s.toGoString
    // UTF-16 code units vs UTF-8 bytes
    println(s.length + " chars, " + g.byteLength + " bytes")
    println(g.toJavaString == s)
    val joined = g.concat(GoString("!"))
    println(joined.toJavaString)
    println(joined.byteAt(0))
    println(GoString("abc").sameAs("abc".toGoString))
    println(GoString.empty.byteLength)
  }
}
