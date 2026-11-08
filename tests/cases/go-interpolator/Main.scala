import scalago.go.*

/** The `go"..."` interpolator: GoStrings written like `s"..."`. */
object Main {
  final class Point(val x: Int, val y: Int) {
    override def toString: String = s"($x, $y)"
  }

  def show(label: String, g: GoString): Unit =
    println(s"$label: ${g.toJavaString} [${g.byteLength} bytes]")

  def main(args: Array[String]): Unit = {
    // Literals and escapes, as with s"..."
    show("literal", go"hello")
    show("empty", go"")
    show("escapes", go"tab\there \"quoted\" back\\slash \u00e9 $$5")
    show("newline", go"a\nb")
    show("unicode", go"grüße, 世界, 😀")
    show("surrogate pair escape", go"\uD83D\uDE00")
    show("lone surrogate", go"[\uD800]")
    println(go"same" == GoString("same"))
    println(go"tab\t" == s"tab\t".toGoString)

    // Interpolated values, rendered like s"..."
    val i = 42
    val l = -7L
    val d = 1.0 / 3
    val f = 2.5f
    val c = 'ß'
    val b = true
    val nothing: String = null
    val obj: AnyRef = null
    val name = "wörld 😀"
    val g = go"Gö"
    show("int", go"i = $i, i + 1 = ${i + 1}")
    show("long, double, float", go"$l $d $f ${1e21} ${Double.NaN}")
    show("char, boolean, unit", go"$c $b ${()}")
    show("nulls", go"$nothing $obj ${null}")
    show("string", go"hello, $name!")
    show("GoString", go"<$g$g>")
    show("GoString as Any", go"${g: Any}")
    show("object", go"p = ${new Point(1, 2)}, list = ${List(1, 2)}, opt = ${Option("x")}")
    show("nested", go"[${go"in $i"}]")
    show("only value", go"$i")
    show("adjacent", go"$i$l$name")

    // Arguments are evaluated (and rendered) left to right, once each
    val buf = scala.collection.mutable.ListBuffer(1, 2, 3)
    var calls = 0
    def next(): Int = { calls += 1; calls }
    show("order", go"$buf ${buf.remove(0)} $buf ${next()} ${next()}")
    show("in a loop", (1 to 3).map(n => go"#$n").reduce((a, b) => go"$a,$b"))
    show("match", go"${i match { case 42 => "answer"; case _ => "other" }}!")
    println(go"printed: $i")
  }
}
