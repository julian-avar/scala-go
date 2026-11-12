// String conversion (docs/_docs/spec/language.md, "String conversion"): concatenation,
// interpolation, String.valueOf, toString, StringBuilder.append and println of every primitive
// type and special value; Double.toString/Float.toString shortest round-trip forms; the s, f and
// raw interpolators. Values pass through `id` so the compiler cannot fold the conversions.
case class Point(x: Int, y: Double)
case class Wrapper(p: Point, label: String, opt: Option[Int])
case object Singleton
enum Color:
  case Red, Green
class Hashed:
  override def hashCode: Int = 0xBEEF
class Anonymous
class Plain(n: Int):
  override def toString: String = s"Plain#$n"

object Main:
  def id[A](x: A): A = x
  def d(x: Double): Double = x
  def f(x: Float): Float = x

  def main(args: Array[String]): Unit =
    val doubles = List(0.0, -0.0, 1.0, -1.0, 100.0, 0.1, 0.5, 1.0 / 3, 2.0 / 3, 0.001, 0.0001, 1.0e-3, 9.999e-4,
      1234567.0, 9999999.0, 1.0e7, 10000001.0, 12345678.9, 1.0e21, 1.0e22, 1.0e23, 123456789012345680.0,
      1.0e-5, 1.234e-7, 4.9e-324, 2.2250738585072014e-308, 2.225073858507201e-308, 1.7976931348623157e308,
      9007199254740992.0, 9007199254740993.0, 0.30000000000000004, 5.0e-324 * 3, 1.0e100, 3.141592653589793,
      2.718281828459045, 1.0e15, 1.0e16, 123.456, -987.654e-10, 2.0e-3, 1.5e300 * 1.5e10,
      Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity)
    for x <- doubles do println(s"double ${d(x)} | ${String.valueOf(d(x))} | ${java.lang.Double.toString(d(x))}")
    val floats = List(0.0f, -0.0f, 1.0f, 0.1f, 0.2f, 0.3f, 1.1f, 1.0f / 3, 100.0f, 1.0e7f, 9999999.0f, 1.0e-3f, 1.0e-4f,
      16777216.0f, 16777217.0f, 3.4028235e38f, 1.4e-45f, 1.17549435e-38f, 3.14159265f, 1.0e10f, 123456.79f,
      33554432.0f, 8.5e-44f, Float.NaN, Float.PositiveInfinity, Float.NegativeInfinity)
    for x <- floats do println(s"float ${f(x)} | ${String.valueOf(f(x))} | ${java.lang.Float.toString(f(x))}")

    // Integers, booleans, chars
    println(s"ints ${id(Int.MinValue)} ${id(Int.MaxValue)} ${id(0)} ${id(-1)}")
    println(s"longs ${id(Long.MinValue)} ${id(Long.MaxValue)} ${id(-1L)}")
    println(s"bytes ${id(Byte.MinValue)} ${id(Byte.MaxValue)} shorts ${id(Short.MinValue)} ${id(Short.MaxValue)}")
    println(s"booleans ${id(true)} ${id(false)} ${String.valueOf(id(true))} ${java.lang.Boolean.toString(false)}")
    println(s"chars ${id('a')} ${id('\u00e9')} ${id('\u20ac')} ${String.valueOf(id('Z'))} ${Character.toString(id('7'))}")
    println("char concat " + 'x' + id('y') + 'z'.toInt)
    println("char + int (Int) " + ('a' + 1) + " " + ('a' + 1).toChar)
    println("supplementary " + "\uD83D\uDE00" + " " + new String(Array('\uD83D', '\uDE01')))

    // null, Unit and references
    val nullString: String = null
    val nullAny: Any = null
    val nullRef: Plain = null
    println("null " + nullString + " " + nullAny + " " + nullRef + " " + String.valueOf(nullAny: Any))
    println(s"interpolated null $nullString $nullRef")
    println("unit " + () + " " + id(()) + " " + String.valueOf(()) + " " + ().toString)
    val unitResult: Any = println("(side effect)")
    println(s"unit result $unitResult")
    println("default toString " + Hashed() + " " + { val a = Anonymous(); a.toString == "Anonymous@" + Integer.toHexString(a.hashCode) })
    println("primitive array toString prefix " + Array(1).toString.takeWhile(_ != '@') + " " + Array(1.0).toString.takeWhile(_ != '@') + " " + Array('c').toString.takeWhile(_ != '@'))
    println("objects " + Plain(1) + " " + Point(1, 2.5) + " " + Singleton + " " + Color.Green + " " + Wrapper(Point(0, -0.0), "w", Some(3)))
    println("boxes " + (id(1): Any) + " " + (id(1L): Any) + " " + (id(1.0f): Any) + " " + (id('c'): Any) + " " + (id(1.toByte): Any))
    println("Option " + Some(1.0) + " " + None + " " + Some(null) + " " + Some(()))
    println("collections " + List(1, 2) + " " + Vector("a") + " " + Map(1 -> "one") + " " + Set(1.5) + " " + Nil + " " + List(List(1), Nil))
    println("tuples " + (1, "a") + " " + (1, (2.0, 'c'), null) + " " + Tuple1(1) + " " + EmptyTuple)
    println("Either/Try " + Left(1) + " " + Right("r") + " " + scala.util.Try(1) + " " + scala.util.Failure(new Exception("m")))
    println("ranges " + (1 to 3) + " " + (1 until 10 by 3) + " " + " " + ('a' to 'c'))
    println("array contents " + Array(1, 2).mkString("[", ",", "]") + " " + Array("a", null).mkString(","))
    println("StringBuilder " + new StringBuilder("sb").append(1).append('c').append(2.5).append(true).append(null: String).append(-0.0f))
    val jsb = new java.lang.StringBuilder()
    jsb.append(1).append(2L).append(1.5f).append(0.1).append('x').append(false).append(null: AnyRef).append(Array('h', 'i'))
    println("java StringBuilder " + jsb)
    println("valueOf char array " + String.valueOf(Array('a', 'b', 'c')) + " " + String.valueOf(Array('a', 'b', 'c'), 1, 2))
    println("toString methods " + 42.toString + " " + 42L.toString + " " + 4.2.toString + " " + 'q'.toString + " " + true.toString)
    println("boxed toString " + Integer.valueOf(-5).toString + " " + java.lang.Long.valueOf(5).toString + " " + java.lang.Double.valueOf(1e-5).toString)
    println("Integer.toString " + Integer.toString(-123) + " " + java.lang.Long.toString(1L << 40) + " " + java.lang.Short.toString(-7: Short) + " " + java.lang.Byte.toString(8: Byte))
    println("+= on String var " + { var s = "a"; s += 1; s += 'b'; s += 2.0; s += null; s += (); s })

    // Interpolators
    val name = "Ann"
    val n = 42
    val pi = 3.14159
    println(s"s: $name is $n, ${n + 1} next, $$ sign, ${name.length}${name}")
    println(s"s: nested ${s"<${n * 2}>"} and ${if n > 0 then "pos" else "neg"} and ${List(1, 2).map(_ * 2)}")
    println(s"s: escapes \t| \"quoted\" \\ A")
    println(raw"raw: \t| \n \\ $name ${n + 1} A")
    println(f"f: $n%d|$n%5d|$n%-5d|$n%05d|${-n}%d|${n.toLong}%d")
    println(f"f: $name%s|$name%8s|$name%-8s|${name.toUpperCase}%s|${null}%s")
    println(f"f: $pi%.2f|$pi%8.3f|$pi%-8.1f|${-pi}%.0f|${2.5}%.0f|${1e10}%.1f|${0.125}%.2f")
    println(f"f: ${255}%x|${255}%X|${255}%08x|${8}%o|${'c'}%c|${true}%b|${null}%b|100%%")
    println(f"f: ${1.5e-5}%e|${123456.0}%.3e|${12345678901L}%d|${-0.0}%.1f|${Double.NaN}%f|${Double.PositiveInfinity}%f")
    println(f"f: ${1.5f}%.3f|${3: Byte}%d|${4: Short}%3d|${java.lang.Integer.valueOf(9)}%d")
    println(f"f: ${"x"}%s${1}%d${'y'}%s")
    println(String.format("format: %s=%d (%.1f%%) %s", "k", 7, 12.25, List(1)))
    println("format: %5s|%-5s|%c".format("ab", "cd", 'e'))
    println("multi\nline\tstring with \u00e9 and \\ backslash")
    println("""triple "quoted" \n stays""")
    println("""|stripped
               |margin""".stripMargin)
