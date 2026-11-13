import scalago.go.*

// Known bugs (docs/roadmap.md, M0 "Known bugs"): each line prints what scala-go does today; the
// comment says what the JVM does. Fixing a bug makes this case fail: move the check to a case
// that runs on both the JVM and Go, and remove the bug from the roadmap and the specification.
//
// Not here: the fused multiply-add (only on arm64 and GOAMD64=v3, so the output would depend on
// the platform), `try throw e finally return x` (go vet rejects the generated code) and facades
// of pointer-receiver methods on non-struct Go types (go build fails).
object Main:
  def attempt(what: String)(body: => Any): Unit =
    val result =
      try String.valueOf(body)
      catch case e: Throwable => s"throws ${e.getClass.getName}${Option(e.getMessage).fold("")(": " + _)}"
    println(s"$what: $result")

  def main(args: Array[String]): Unit =
    // JVM: true, true (compared as Float)
    val long: Any = 16777217L
    val int: Any = 16777217
    val float: Any = 16777216f
    attempt("(16777217L: Any) == (16777216f: Any)")(long == float)
    attempt("(16777217: Any) == (16777216f: Any)")(int == float)
    // JVM: true (ScalaNumber on the right)
    attempt("(1: Any) == BigInt(1)")((1: Any) == BigInt(1))
    attempt("(1: Any) == BigDecimal(1)")((1: Any) == BigDecimal(1))

    // JVM: false, false, "null"
    val nullString: String = null
    attempt("nullString == \"a\"")(nullString == "a")
    attempt("\"a\" == nullString")("a" == nullString)
    attempt("nullString match")(nullString match
      case "a" => "a"
      case _   => "other")

    // JVM (no Go values there): true
    val slice: AnyRef = GoSlice.make[Int](1).asInstanceOf[AnyRef]
    attempt("slice eq slice")(slice eq slice)
    val map: AnyRef = GoMap.make[Int, Int].asInstanceOf[AnyRef]
    attempt("map eq map")(map eq map)

    // JVM: throws ClassCastException
    attempt("\"a\".asInstanceOf[Integer]")(("a": Any).asInstanceOf[Integer].getClass.getName)

    // JVM: 0 + 1 = 1 (the left operand is read before the call)
    var i = 0
    def inc(): Int = { i += 1; i }
    attempt("i + inc()")(i + inc())

    // JVM: throws java.lang.ArrayIndexOutOfBoundsException: Index -1 out of bounds for length 1
    val ints = Array(1)
    val minusOne = -1
    attempt("ints(-1)")(ints(minusOne))

