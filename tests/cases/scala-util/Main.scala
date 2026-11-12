import scala.annotation.{nowarn, switch, tailrec, targetName, unused}
import scala.collection.mutable.ListBuffer
import scala.util.{Random, Using}
import scala.util.boundary, boundary.break
import scala.util.chaining.*
import scala.util.control.{Breaks, TailCalls}
import scala.util.control.TailCalls.TailRec
import scala.util.matching.Regex

/** scala.util (Using, Random with a seed, Regex, chaining, boundary), scala.util.control (Breaks,
  * TailCalls), Predef's checks and helpers, annotations, DynamicVariable and MurmurHash3. */
object Main:
  def section(name: String): Unit = println(s"-- $name")

  def main(args: Array[String]): Unit =
    using()
    random()
    regex()
    chaining()
    boundaries()
    breaks()
    tailCalls()
    predef()
    annotations()
    misc()

  class Res(name: String, log: ListBuffer[String], failClose: Boolean = false) extends AutoCloseable:
    log += s"open $name"
    def read(): String = s"data of $name"
    def close(): Unit =
      log += s"close $name"
      if failClose then throw IllegalStateException(s"close $name failed")

  def using(): Unit =
    section("Using")
    val log = ListBuffer.empty[String]
    println(Using(Res("a", log))(_.read()))
    println(Using(Res("b", log))(r => throw IllegalArgumentException(s"failed reading ${r.read()}")).failed.map(_.getMessage))
    println(Using.resource(Res("c", log))(_.read().length))
    val all = Using.Manager { use =>
      val x = use(Res("x", log))
      val y = use(Res("y", log))
      s"${x.read()} + ${y.read()}"
    }
    println(all)
    println(Using.resources(Res("p", log), Res("q", log))((p, q) => p.read().length + q.read().length))
    val suppressed = Using(Res("s", log, failClose = true))(_ => throw RuntimeException("body"))
    println(suppressed.failed.map(e => s"${e.getMessage}, suppressed ${e.getSuppressed.map(_.getMessage).toList}"))
    println(Using(Res("t", log, failClose = true))(_.read()).failed.map(_.getMessage))
    println(log.mkString(", "))
    given Using.Releasable[StringBuilder] with
      def release(sb: StringBuilder): Unit = log += s"released ${sb.length}"
    log.clear()
    println(Using(new StringBuilder("abc"))(_.append("d").toString))
    println(log)

  def random(): Unit =
    section("Random with a seed")
    val r = Random(42)
    println(List.fill(5)(r.nextInt(100)))
    println(s"${r.nextInt()} ${r.nextLong()} ${r.nextBoolean()} ${r.nextDouble()} ${r.nextFloat()}")
    println(s"${r.nextGaussian()} ${r.between(10, 20)} ${r.between(1L, 1000000000000L)}")
    println(r.shuffle(List(1, 2, 3, 4, 5, 6, 7, 8)))
    println(r.alphanumeric.take(10).mkString)
    println(r.nextString(3).length + r.nextPrintableChar().toString.length)
    println(r.nextBytes(6).toList)
    val a = Random(7)
    val b = Random(7)
    println(List.fill(20)(a.nextInt()) == List.fill(20)(b.nextInt()))
    val j = java.util.Random(123L)
    println(s"${j.nextInt(10)} ${j.nextInt()} ${j.nextLong()} ${j.nextDouble()} ${j.ints(3, 0, 50).toArray.toList}")
    val unseeded = Random.nextInt(10)
    println(unseeded >= 0 && unseeded < 10)

  val Email: Regex = """(\w+)@(\w+)\.(\w+)""".r
  val Named: Regex = """(?<key>\w+)=(?<value>\d+)""".r
  val Num = """(\d+)""".r

  def regex(): Unit =
    section("Regex")
    "user@example.com" match
      case Email(user, domain, tld) => println(s"$user at $domain dot $tld")
      case _                        => println("no")
    println("contact: a@b.cd, x@y.zz" match { case Email.unanchored(u, _, _) => u; case _ => "none" })
    val m = Named.findFirstMatchIn("set width=640 now").get
    println(s"${m.group("key")} ${m.group("value")} ${m.start} ${m.end} ${m.before} | ${m.after} ${m.groupCount}")
    println(Named.findAllMatchIn("a=1 b=22 c=333").map(m => m.group(2).toInt).sum)
    println(Num.replaceAllIn("a1b22c333", m => (m.group(1).toInt * 2).toString))
    println(Num.replaceSomeIn("1 2 3 4", m => Option.when(m.matched.toInt % 2 == 0)("even")))
    println(Num.replaceFirstIn("x9y8", "#"))
    println(s"${Num.findPrefixOf("42abc")} ${Num.findPrefixOf("abc42")} ${Num.findPrefixMatchOf("7z").map(_.end)}")
    println(Num.findAllIn("a1b22c333").matchData.map(_.start).toList)
    println(s"${"""\d+""".r.matches("123")} ${"""\d+""".r.matches("12a")} ${"""\d+""".r.unanchored.matches("a12a")}")
    println(Regex.quoteReplacement("$1\\"))
    println("""(\d)(\d)""".r.replaceAllIn("12 34", "$2$1"))
    println("a, b;c".split("[,;]\\s*").toList)
    println("""(\w+) (\w+)""".r.findAllIn("ab cd ef gh").subgroups)
    val all = "k1=v1;k2=v2".split(";").collect { case s"$k=$v" => k -> v }.toMap
    println(all.toList.sorted)
    println(new Regex("""(\d+)-(\d+)""", "from", "to").findFirstMatchIn("3-7").map(m => m.group("to")))
    println(s"""${"x".r.toString} ${"[a-c]".r.pattern.pattern} ${"(a)|(b)".r.findFirstMatchIn("b").map(_.group(1))}""")

  def chaining(): Unit =
    section("chaining")
    val tapped = ListBuffer.empty[Int]
    val r = 5.pipe(_ * 2).tap(tapped += _).pipe(x => x + 1)
    println(s"$r $tapped")
    println("hello".pipe(_.toUpperCase).pipe(s => s"$s!"))
    println(List(3, 1, 2).tap(xs => println(s"before: $xs")).sorted)

  def firstNegative(xs: List[Int]): Option[Int] =
    boundary:
      for x <- xs do if x < 0 then break(Some(x))
      None

  def boundaries(): Unit =
    section("boundary, break")
    println(s"${firstNegative(List(1, -2, -3))} ${firstNegative(List(1, 2))}")
    val nested = boundary[String]:
      boundary[Int]:
        break("outer")
      "not reached"
    println(nested)
    val count = boundary:
      var i = 0
      while true do
        i += 1
        if i == 100 then break(i)
      -1
    println(count)
    def search(grid: Vector[Vector[Int]], target: Int): Option[(Int, Int)] =
      boundary:
        for (row, i) <- grid.zipWithIndex; (v, j) <- row.zipWithIndex do
          if v == target then break(Some((i, j)))
        None
    println(search(Vector(Vector(1, 2), Vector(3, 4)), 3))
    val unitBreak = ListBuffer.empty[Int]
    boundary:
      for i <- 1 to 10 do
        if i > 3 then break()
        unitBreak += i
    println(unitBreak)
    println(try boundary[String] { throw RuntimeException("not a break") } catch case e: RuntimeException => e.getMessage)

  def breaks(): Unit =
    section("Breaks")
    import Breaks.{break as brk, breakable}
    val seen = ListBuffer.empty[Int]
    breakable {
      for i <- 1 to 10 do
        if i == 4 then brk()
        seen += i
    }
    println(seen)
    val outer = Breaks()
    val inner = Breaks()
    val pairs = ListBuffer.empty[(Int, Int)]
    outer.breakable {
      for i <- 1 to 3 do
        inner.breakable {
          for j <- 1 to 3 do
            if j == 2 then inner.break()
            if i == 3 then outer.break()
            pairs += ((i, j))
        }
    }
    println(pairs)
    println(outer.tryBreakable { outer.break(); "body" }.catchBreak { "caught" })
    println(outer.tryBreakable { "finished" }.catchBreak { "caught" })

  def isEven(n: Int): TailRec[Boolean] = if n == 0 then TailCalls.done(true) else TailCalls.tailcall(isOdd(n - 1))
  def isOdd(n: Int): TailRec[Boolean] = if n == 0 then TailCalls.done(false) else TailCalls.tailcall(isEven(n - 1))
  def fib(n: Int): TailRec[BigInt] =
    if n < 2 then TailCalls.done(BigInt(n))
    else for
      a <- TailCalls.tailcall(fib(n - 1))
      b <- TailCalls.tailcall(fib(n - 2))
    yield a + b

  @tailrec def sumTo(n: Long, acc: Long = 0): Long = if n == 0 then acc else sumTo(n - 1, acc + n)

  def tailCalls(): Unit =
    section("TailCalls, @tailrec")
    println(s"${isEven(100001).result} ${isOdd(100001).result} ${fib(20).result}")
    println(TailCalls.done(1).map(_ + 1).flatMap(x => TailCalls.done(x * 10)).result)
    println(sumTo(10000000))

  def sqrtOf(x: Double): Double =
    require(x >= 0, s"negative input $x")
    math.sqrt(x).ensuring(r => math.abs(r * r - x) < 1e-9, "imprecise")

  def predef(): Unit =
    section("Predef")
    println(sqrtOf(16))
    println(try sqrtOf(-1) catch case e: IllegalArgumentException => e.getMessage)
    println(try require(false) catch case e: IllegalArgumentException => e.getMessage)
    println(try assert(1 + 1 == 3, "math is broken") catch case e: AssertionError => e.getMessage)
    println(try assert(false) catch case e: AssertionError => e.getMessage)
    println(try assume(false, "assumed") catch case e: AssertionError => e.getMessage)
    println(try 5.ensuring(_ > 10) catch case e: AssertionError => e.getMessage)
    println(try ??? catch case e: NotImplementedError => e.getMessage)
    println(try sys.error("boom") catch case e: RuntimeException => e.getMessage)
    println(s"${identity(42)} ${implicitly[Ordering[Int]].compare(1, 2)} ${summon[Numeric[Long]].zero}")
    println(locally { val x = 2; x * 21 })
    println(s"${valueOf[3]} ${valueOf["lit"]} ${classOf[String].getName} ${classOf[List[?]].getSimpleName}")
    val arrow = 1 -> "one"
    println(s"$arrow ${arrow.swap} ${(1, 2, 3).toList} ${Tuple3(1, "a", 'c').productArity}")
    println(s"${"str".getClass.getName} ${1.getClass.getName} ${Integer.valueOf(1).getClass.getName}")
    val any: Any = Seq(1, 2)
    println(s"${any.isInstanceOf[Seq[?]]} ${any.asInstanceOf[Seq[Int]].sum} ${any.##} ${"a".##}")
    printf("%s and %d%n", "printf", 7)
    print("print ")
    println()
    Console.println(s"Console ${Console.RED.length}")
    println(s"${summon[Int =:= Int](1)} ${summon[Int <:< AnyVal](5)}")

  object Codes:
    final val A = 1
    final val B = 2

  @targetName("plusPlus") def ++(a: Int, b: Int): Int = a + b
  def code(c: Int): String = (c: @switch) match
    case Codes.A => "a"
    case Codes.B => "b"
    case _       => "other"

  @deprecated("use other", "1.0") def old(): Int = 1
  @nowarn("cat=deprecation") def callsOld(): Int = old() + 1

  def annotations(): Unit =
    section("Annotations")
    println(s"${++(1, 2)} ${code(1)} ${code(2)} ${code(9)} ${callsOld()}")
    @unused val ignored = 5
    @volatile var flag = false
    flag = true
    @transient lazy val lzy = "lazy"
    println(s"$flag $lzy")
    @inline def inlined(x: Int) = x + 1
    println(inlined(1))

  def misc(): Unit =
    section("DynamicVariable, MurmurHash3, Duration")
    val dv = scala.util.DynamicVariable("default")
    println(s"${dv.value} ${dv.withValue("inner")(dv.value)} ${dv.value}")
    import scala.util.hashing.MurmurHash3
    println(s"${MurmurHash3.stringHash("hello")} ${MurmurHash3.seqHash(List(1, 2, 3))} ${MurmurHash3.bytesHash(Array[Byte](1, 2))}")
    println(s"${List(1, 2, 3).hashCode} ${Vector(1, 2, 3).hashCode} ${Set(1, 2).hashCode} ${Map(1 -> 2).hashCode} ${(1, "a").hashCode}")
    println(s"${"hello".hashCode} ${Some(1).hashCode} ${None.hashCode} ${1.5.hashCode} ${true.hashCode} ${'c'.hashCode}")
    println(scala.util.hashing.Hashing.default[String].hash("x") == "x".##)
    import scala.concurrent.duration.*
    val d = 90.seconds
    println(s"$d ${d.toMinutes} ${d.toMillis} ${d + 30.seconds} ${d * 2} ${d / 3} ${(d * 2).toCoarsest}")
    println(s"${2.hours + 15.minutes} ${(2.hours + 15.minutes).toMinutes} ${Duration(1500, MILLISECONDS).toSeconds} ${1.day.toHours}")
    println(s"${Duration("2 seconds")} ${Duration("100 ms").toNanos} ${Duration.Inf} ${Duration.Inf > 1.day} ${Duration.Zero}")
    println(s"${5.seconds < 1.minute} ${List(3.seconds, 1.second, 2.minutes).sorted} ${(1.5).seconds} ${Duration.fromNanos(1500)}")
    println(s"${10.millis.toUnit(SECONDS)} ${1.minute.unit} ${(-d).isFinite} ${Duration.Undefined.isFinite} ${3.nanos}")
