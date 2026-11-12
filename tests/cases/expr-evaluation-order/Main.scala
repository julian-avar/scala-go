import scala.collection.mutable.ArrayBuffer

// Evaluation order (docs/_docs/spec/language.md, "Evaluation order"): operands, arguments and
// receivers left to right, each once; operands that need Go statements of their own (if, match,
// try, blocks) do not move the operands before them. Each line prints the trace of a call.
object Main:
  val log = ArrayBuffer.empty[String]

  def t[A](label: String, v: A): A = { log += label; v }

  def show(what: String)(body: => Any): Unit =
    log.clear()
    val r = try String.valueOf(body) catch case e: Exception => s"throws ${e.getClass.getSimpleName}"
    println(s"$what = $r [${log.mkString(" ")}]")

  def f3(a: Int, b: Int, c: Int): Int = a * 100 + b * 10 + c
  def named(first: Int, second: Int, third: Int = t("default", 3)): String = s"$first$second$third"
  def byName(cond: Boolean)(x: => Int): Int = if cond then x + x else 0

  class Box(var n: Int):
    def add(k: Int): Box = { log += s"add$k"; n += k; this }
    def get: Int = n

  final class Shown(name: String):
    override def toString: String = { log += s"toString-$name"; name }

  def main(args: Array[String]): Unit =
    show("arguments")(f3(t("a", 1), t("b", 2), t("c", 3)))
    show("a + b * c")(t("a", 1) + t("b", 2) * t("c", 3))
    show("(a - b) - c")(t("a", 10) - t("b", 2) - t("c", 3))
    show("a - (b - c)")(t("a", 10) - (t("b", 2) - t("c", 3)))
    show("receiver first")(t("recv", "abc").substring(t("from", 1), t("to", 2)))
    show("method chain")(Box(0).add(t("x", 1).toInt).add(t("y", 2)).get)
    show("named, out of order")(named(second = t("second", 2), first = t("first", 1)))
    show("named, with default")(named(t("p1", 1), t("p2", 2)))

    // Operands that need statements: an if, a match, a block, a try.
    show("call + if")(t("left", 1) + (if t("cond", true) then t("then", 10) else t("else", 20)))
    show("call + match")(t("left", 1) + (t("sel", 2) match
      case 1 => t("case1", 100)
      case _ => t("default", 200)))
    show("call + block")(t("left", 1) + { log += "block"; val x = t("inner", 5); x * 2 })
    show("call + try")(t("left", 1) + (try t("body", Integer.parseInt("x")) catch case _: NumberFormatException => t("handler", 7)))
    show("args with statements")(f3(t("a", 1), if t("c?", false) then 0 else t("b", 2), { log += "blk"; 3 }))

    // Short-circuit operators skip the right operand; & | ^ on Boolean evaluate both.
    show("false && x")(t("l", false) && t("r", true))
    show("true && x")(t("l", true) && t("r", false))
    show("true || x")(t("l", true) || t("r", false))
    show("false || x")(t("l", false) || t("r", true))
    show("false & x")(t("l", false) & t("r", true))
    show("true | x")(t("l", true) | t("r", false))
    show("true ^ x")(t("l", true) ^ t("r", true))
    show("nested && ||")((t("a", false) && t("b", true)) || (t("c", true) && t("d", true)))

    // String concatenation: every operand is evaluated, then converted.
    show("concat")("" + t("o1", Shown("A")) + "-" + t("o2", Shown("B")) + "-" + t("n", 3))
    show("interpolation")(s"${t("x", Shown("X"))}/${t("y", 2)}/${t("z", Shown("Z"))}")

    // Assignments: target (array, index) before the value.
    val arr = Array(10, 20, 30)
    show("arr(i) = v")({ t("arr", arr)(t("idx", 1)) = t("value", 99); arr.mkString(",") })
    show("arr(i) += v")({ t("arr", arr)(t("idx", 2)) += t("delta", 5); arr.mkString(",") })
    val box = Box(1)
    show("obj.field = v")({ t("obj", box).n = t("value", 42); box.n })
    show("obj.field *= v")({ t("obj", box).n *= t("factor", 2); box.n })

    // By-name arguments are evaluated at each use, not at the call.
    show("by-name twice")(byName(t("cond", true))(t("arg", 4)))
    show("by-name unused")(byName(t("cond", false))(t("arg", 4)))

    // Division by zero: the dividend is evaluated first; the right operand of a failed
    // operation is still evaluated.
    val zero = args.length
    show("dividend then divisor")(t("dividend", 7) / t("divisor", zero))
    show("throwing left operand")(t("left", Integer.parseInt("?")) + t("right", 1))

    // Constructor arguments, tuple and collection elements.
    show("constructor args")({ val b = new Box(t("init", 3)); b.add(t("k", 1)).get })
    show("tuple elements")((t("_1", 1), t("_2", "two"), t("_3", 3.0)))
    show("List elements")(List(t("e1", 1), t("e2", 2), t("e3", 3)))
    show("Array elements")(Array(t("e1", 1), t("e2", 2)).sum)

    // Compound expressions with side effects on locals (not captured).
    var x = 1
    x += { x *= 10; x }
    println(s"x += { x *= 10; x } -> $x")
    var y = 5
    val z = y + { y = 100; y }
    println(s"y + { y = 100; y } -> $z")
    var k = 0
    val ks = List({ k += 1; k }, { k += 1; k }, { k += 1; k })
    println(s"k increments -> $ks")
    // A while condition is re-evaluated on each iteration.
    var calls = 0
    def more(): Boolean = { calls += 1; calls < 4 }
    while more() do ()
    println(s"while condition calls -> $calls")
