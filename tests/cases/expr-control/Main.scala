import scala.annotation.{switch, tailrec}

// Other expressions (docs/_docs/spec/language.md, "Other expressions"): while loops, return,
// assignments and compound assignments on every primitive type, fields and array elements, if
// and match as expressions (with widening of their branches), switches on Int, Char and String
// literals, self-recursive tail calls, synchronized blocks as expressions.
class Holder:
  var i = 10
  var d = 1.5
  var s = "s"
  private var secret = 1
  def bump(): Int = { secret <<= 2; secret }

object Main:
  def p(what: String)(body: => Any): Unit = println(s"$what: ${String.valueOf(body)}")

  def find(xs: Array[Int], target: Int): Int =
    var i = 0
    while i < xs.length do
      if xs(i) == target then return i
      i += 1
    -1

  def findInNested(grid: Array[Array[Int]], target: Int): (Int, Int) =
    for r <- grid.indices; c <- grid(r).indices do
      if grid(r)(c) == target then return (r, c)
    (-1, -1)

  def earlyUnit(n: Int, log: StringBuilder): Unit =
    if n < 0 then return
    log ++= s"ran $n;"

  @tailrec def sumTo(n: Long, acc: Long): Long = if n == 0 then acc else sumTo(n - 1, acc + n)
  def countDown(n: Int): Int = if n <= 0 then n else countDown(n - 1) // tail call without @tailrec
  @tailrec def gcd(a: Int, b: Int): Int = b match
    case 0 => a
    case _ => gcd(b, a % b)

  def dayName(d: Int): String = (d: @switch) match
    case 1 => "Mon"
    case 2 => "Tue"
    case 3 | 4 | 5 => "midweek"
    case 6 | 7 => "weekend"
    case _ => "?"

  def charKind(c: Char): String = (c: @switch) match
    case 'a' | 'e' | 'i' | 'o' | 'u' => "vowel"
    case ' ' | '\t' | '\n'           => "space"
    case '0'                         => "zero"
    case _ if c.isDigit              => "digit"
    case _                           => "other"

  def command(s: String): Int = s match
    case "start"         => 1
    case "stop" | "halt" => 2
    case ""              => 0
    case other if other.startsWith("x") => 3
    case _               => -1

  def longMatch(l: Long): String = l match
    case 0L              => "zero"
    case Long.MaxValue   => "max"
    case x if x < 0      => "negative"
    case _               => "positive"

  def main(args: Array[String]): Unit =
    // while, return
    p("find")(s"${find(Array(5, 6, 7), 7)} ${find(Array(5), 1)} ${find(Array(), 1)}")
    p("return from for")(s"${findInNested(Array(Array(1, 2), Array(3, 4)), 3)} ${findInNested(Array(Array(1)), 9)}")
    val log = new StringBuilder
    earlyUnit(-1, log); earlyUnit(2, log)
    p("return in Unit method")(log)
    var n = 0
    var iterations = 0
    while { iterations += 1; n < 5 } do n += 2
    p("while with block condition")(s"$n $iterations")
    var k = 10
    while ({ k -= 3; k > 0 }) ()
    p("do-while idiom")(k)
    var outer = 0
    var pairs = 0
    while outer < 4 do
      var inner = 0
      while inner < outer do
        pairs += 1
        inner += 1
      outer += 1
    p("nested whiles")(pairs)
    p("while is Unit")({ var x = 0; val u: Unit = while x < 1 do x += 1; u })

    // Compound assignments on every primitive type
    var i = 7
    i += 3; i -= 1; i *= 4; i /= 3; i %= 7; i <<= 3; i >>= 1; i |= 0x100; i &= 0x1F0; i ^= 0xFF; i >>>= 2
    p("Int compound")(i)
    var neg = -64
    neg >>= 2; val a1 = neg; neg >>>= 28; val a2 = neg
    p("Int >>= and >>>= on negative")(s"$a1 $a2")
    var l = 1L
    l += Int.MaxValue; l *= 4; l -= 1; l /= 3; l %= 1000000; l <<= 40; l >>>= 3; l ^= -1L
    p("Long compound")(l)
    var d = 1.0
    d += 0.5; d *= 3; d -= 0.25; d /= 2; d %= 1.5
    p("Double compound")(d)
    var f = 1.0f
    f += 0.1f; f *= 3; f /= 7; f -= 1
    p("Float compound")(f)
    var b: Byte = 100
    b = (b + 100).toByte
    b = (b * 3).toByte
    p("Byte reassignment wraps")(b)
    var sh: Short = 30000
    sh = (sh + sh).toShort
    p("Short reassignment wraps")(sh)
    var c = 'a'
    c = (c + 25).toChar
    c = (c - 1).toChar
    p("Char reassignment")(c)
    var flag = true
    flag &= false; val f1 = flag; flag |= true; val f2 = flag; flag ^= true
    p("Boolean compound")(s"$f1 $f2 $flag")
    var str = "a"
    str += "b"; str += 1; str += 'c'; str += 2.5
    p("String +=")(str)
    var lst = List(1)
    lst ::= 0; lst :+= 2; lst ++= List(3)
    p("List ::= :+= ++=")(lst)
    val h = Holder()
    h.i += 5; h.i *= 2; h.d *= 4; h.s += "!"
    p("field compound")(s"${h.i} ${h.d} ${h.s} ${h.bump()} ${h.bump()}")
    val arr = Array(1, 2, 3)
    arr(0) += 10; arr(1) *= 5; arr(2) <<= 4; arr(arr.length - 1) -= 1
    p("array element compound")(arr.toList)
    val darr = Array(1.0, 2.0)
    darr(1) /= 4
    val carr = Array('a', 'b')
    carr(0) = (carr(0) + 2).toChar
    val barr = Array[Byte](127)
    barr(0) = (barr(0) + 1).toByte
    p("primitive array element updates")(s"${darr.toList} ${carr.mkString} ${barr(0)}")
    val matrix = Array.ofDim[Int](2, 3)
    matrix(1)(2) += 7; matrix(0)(0) -= 1
    p("2D element compound")(matrix.map(_.mkString(",")).mkString("/"))
    p("assignment value is Unit")({ var x = 0; val u = { x = 5 }; s"$u $x" })
    var (q, r) = (17 / 5, 17 % 5)
    q += 1
    p("tuple var pattern")(s"$q $r")

    // if and match as expressions, with numeric widening of branches
    val cond = args.length == 0
    val w1 = if cond then 1 else 2L
    val w2 = if !cond then 1 else 2.5
    val w3 = if cond then 'a' else 1
    val w4: Any = if cond then 1 else "one"
    p("if branch widening")(s"$w1 $w2 $w3 $w4 ${(w1: Any).getClass.getSimpleName} ${(w3: Any).getClass.getSimpleName}")
    val m1 = args.length match
      case 0 => 1
      case _ => 2.0
    p("match branch widening")(s"$m1 ${(m1: Any).getClass.getSimpleName}")
    p("if without else is Unit")({ var hit = 0; val u = if !cond then { hit = 1 }; s"$u $hit" })
    p("nested if expression")(List(-5, 0, 5, 50).map(x => if x < 0 then "neg" else if x == 0 then "zero" else if x < 10 then "small" else "big"))
    p("if in arithmetic")(10 + (if cond then 1 else 2) * 3)
    p("match in arithmetic")(100 - (args.length match { case 0 => 1; case _ => 2 }) * 10)
    p("block as expression")({ val x = 3; val y = 4; x * x + y * y })

    // Switches and literal matches
    p("Int switch")((0 to 8).map(dayName))
    p("Char switch")("ae 09x\t".map(charKind))
    p("String match")(List("start", "stop", "halt", "", "xyz", "other").map(command))
    p("Long match")(List(0L, Long.MaxValue, -3L, 8L).map(longMatch))
    p("Double match")(List(0.0, -0.0, Double.NaN, 1.5).map {
      case 0.0 => "zero"
      case 1.5 => "one and a half"
      case x if x.isNaN => "NaN"
      case _ => "other"
    })
    p("Boolean match")(List(true, false).map { case true => "yes"; case false => "no" })
    p("tuple match")(List((1, "a"), (2, "b"), (1, "z")).map {
      case (1, "a") => "exact"
      case (1, _)   => "first"
      case (_, s)   => s"other $s"
    })
    p("MatchError")(scala.util.Try(5 match { case 1 => "one" }).failed.get)

    // Tail recursion and deep loops
    p("tailrec sum")(sumTo(1000000L, 0))
    p("tail call without annotation")(countDown(1000000))
    p("tailrec gcd")(gcd(1071, 462))
    var total = 0L
    var x = 0
    while x < 1000000 do
      total += x
      x += 1
    p("million iterations")(total)

    // synchronized as an expression
    val lock = new Object
    p("synchronized value")(lock.synchronized { lock.synchronized { 41 + 1 } })
    p("synchronized with exception")({ val r = scala.util.Try(lock.synchronized { throw IllegalStateException("in lock") }); s"${r.failed.get.getMessage} ${lock.synchronized("relocked")}" })
