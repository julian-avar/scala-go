import scalago.go.*

object Main {
  def main(args: Array[String]): Unit = {
    val s = "héllo, wörld"
    val g = s.toGoString
    // UTF-16 code units vs UTF-8 bytes
    println(s"${s.length} chars, ${g.byteLength} bytes")
    println(g.toJavaString == s)
    val joined = g.concat(GoString("!"))
    println(joined.toJavaString)
    println(joined.byteAt(0))
    println(GoString("abc").sameAs("abc".toGoString))
    println(GoString.empty.byteLength)

    // Printed, compared and hashed by content, as Go strings (on the JVM too)
    println(g)
    println(GoString("abc") == "abc".toGoString)
    println(Set(GoString("a"), GoString("a"), GoString("b")).size)
    println(GoString("x").hashCode == GoString("x").hashCode)
    println(GoString.valueOf(g: Any) == g)
    val words = List("pear", "Zebra", "äpfel", "apple", "fig").map(_.toGoString)
    println(words.sorted.mkString(" "))
    val order = Ordering[GoString]
    println(s"${order.compare(GoString("a"), GoString("b"))} ${order.compare(GoString("b"), GoString("a"))} ${order.compare(GoString("a"), GoString("a"))}")
    // Unpaired surrogates become U+FFFD (Go's conversion; the JDK's encoder would write '?')
    println("[\uD800]".toGoString.byteLength)
  }
}
