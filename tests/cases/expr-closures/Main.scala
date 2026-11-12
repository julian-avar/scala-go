import java.util.function.{BiFunction, BinaryOperator, Consumer, DoubleUnaryOperator, IntBinaryOperator, IntPredicate,
  IntUnaryOperator, LongSupplier, Predicate, Supplier, ToIntFunction, UnaryOperator, Function as JFunction}

// Functions and closures (docs/_docs/spec/language.md, "Functions and closures"; data.md,
// "Functions and lambdas"): captured vals and vars of every primitive type (scala.runtime.*Ref),
// closures in loops, currying, eta-expansion, placeholders, arities 0 to 22, andThen/compose,
// tupled/curried, partial functions, SAM conversion to Scala and Java interfaces, by-name
// parameters. Not here: reading a captured var in the same expression as a call that changes it
// (known-bugs-misc), lambda toString (limits-runtime).
trait Visitor:
  def visit(n: Int): String
trait Reducer[A]:
  def reduce(a: A, b: A): A
  def name: String = "reducer"
abstract class Handler:
  def handle(s: String): Int
  def twice(s: String): Int = handle(s) * 2
@FunctionalInterface trait Callback:
  def call(): Unit

class Counter(start: Int):
  private var n = start
  def incrementer: () => Int = () => { n += 1; n }
  def adder(k: Int): Int => Int = x => x + k + n

object Main:
  def p(what: String)(body: => Any): Unit = println(s"$what: ${String.valueOf(body)}")

  def add(a: Int, b: Int): Int = a + b
  def curried(a: Int)(b: Int)(c: Int): Int = a * 100 + b * 10 + c
  def generic[A, B](x: A)(f: A => B): B = f(x)
  def overloaded(x: Int): String = s"int $x"
  def overloaded(x: String): String = s"string $x"
  def poly[T](xs: List[T]): Int = xs.size
  def byName(n: Int)(body: => Int): List[Int] = List.fill(n)(body)
  def visitAll(v: Visitor): String = (1 to 3).map(v.visit).mkString(",")
  def withCallback(cb: Callback): Unit = cb.call()
  def varargs(xs: Int*): Int = xs.sum

  def main(args: Array[String]): Unit =
    // Captured vars of every primitive type, mutated inside and outside the closure
    var i = 1; var l = 1L; var d = 1.0; var f = 1.0f; var b: Byte = 1; var s: Short = 1; var c = 'a'; var z = false
    var str = "s"; var obj: List[Int] = Nil
    val mutate = () =>
      i += 1; l *= 3; d /= 4; f += 0.5f; b = (b + 1).toByte; s = (s * 7).toShort; c = (c + 1).toChar; z = !z
      str += "!"; obj = i :: obj
    mutate(); mutate()
    p("captured vars after closure")(s"$i $l $d $f $b $s $c $z $str $obj")
    i = 100; c = 'x'
    val read = () => s"$i $c"
    p("closure sees outer writes")(read())
    i = 200
    p("closure sees later writes")(read())

    // Each closure created in a loop captures its own val
    val fs = for k <- 1 to 3 yield () => k * 10
    p("loop closures")(fs.map(_()))
    val buf = scala.collection.mutable.ArrayBuffer.empty[() => Int]
    var j = 0
    while j < 3 do
      val snapshot = j
      buf += (() => snapshot)
      j += 1
    p("while-loop closures over a val")(buf.map(_()))
    val shared = scala.collection.mutable.ArrayBuffer.empty[() => Int]
    var m = 0
    while m < 3 do
      shared += (() => m)
      m += 1
    p("while-loop closures over the var")(shared.map(_()))

    // Closures capturing this, private fields, and other closures
    val counter = Counter(10)
    val inc = counter.incrementer
    inc(); inc()
    p("closure over private field")(s"${inc()} ${counter.adder(5)(1)}")
    val twice = (g: Int => Int) => (x: Int) => g(g(x))
    val plus3 = (x: Int) => x + 3
    p("closure returning closure")(twice(plus3)(1))
    p("closure capturing closures")({ val h = twice(twice(plus3)); h(0) })
    def makeAcc(): (Int => Int, () => Int) =
      var total = 0
      ((x: Int) => { total += x; total }, () => total)
    val (accAdd, accGet) = makeAcc()
    accAdd(5); accAdd(7)
    p("two closures sharing a var")(accGet())
    lazy val expensive = { println("(evaluated once)"); 42 }
    val useLazy = () => expensive + 1
    p("lazy val in closure")(s"${useLazy()} ${useLazy()}")

    // Currying, partial application, eta-expansion, placeholders
    p("curried method")(curried(1)(2)(3))
    val c1 = curried(1)
    val c12 = c1(2)
    p("partial application of curried")(s"${c12(3)} ${c1(5)(6)}")
    val addF = add
    val add5 = add(5, _)
    val addR = add(_, 10)
    p("eta-expansion")(s"${addF(1, 2)} ${add5(1)} ${addR(1)}")
    val addC = addF.curried
    p("curried function")(addC(3)(4))
    val tup = addF.tupled
    p("tupled")(tup((6, 7)))
    p("Function.untupled")(Function.untupled(tup)(1, 1))
    p("overloaded method values")(s"${List(1, 2).map(overloaded)} ${List("a").map(overloaded)}")
    p("polymorphic method value")(List(List(1), List("a", "b")).map(poly))
    p("generic HOF")(generic(21)(_ * 2))
    p("placeholders")(s"${List(1, 2, 3).reduce(_ + _)} ${List(3, 1, 2).sortWith(_ > _)} ${List("a", "bb").map(_.length)}")
    p("placeholder in expression")(List(1, 2, 3).map(_ * 2 + 1))
    p("method value of an object method")(List("x", "y").map(_.toUpperCase).map(String.valueOf))
    p("varargs through a function")({ val v: Seq[Int] => Int = xs => varargs(xs*); v(Seq(1, 2, 3)) })

    // andThen / compose
    val inc1 = (x: Int) => x + 1
    val dbl = (x: Int) => x * 2
    val toStr = (x: Int) => s"<$x>"
    p("andThen, compose")(s"${(inc1 andThen dbl)(5)} ${(inc1 compose dbl)(5)} ${(inc1 andThen dbl andThen toStr)(1)}")
    p("identity, const")(s"${identity(3)} ${Function.const(7)(99)}")
    p("Function.chain")(Function.chain(List(inc1, dbl, inc1))(1))

    // Arities 0 to 22
    val f0 = () => 0
    val f1 = (a: Int) => a
    val f2 = (a: Int, b: Int) => a + b
    val f3 = (a: Int, b: Int, c: Int) => a + b + c
    val f5 = (a: Int, b: Long, c: Double, d: String, e: Char) => s"$a$b$c$d$e"
    val f10 = (a: Int, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int, h: Int, i: Int, j: Int) => a + b + c + d + e + f + g + h + i + j
    val f22 = (a1: Int, a2: Int, a3: Int, a4: Int, a5: Int, a6: Int, a7: Int, a8: Int, a9: Int, a10: Int, a11: Int,
      a12: Int, a13: Int, a14: Int, a15: Int, a16: Int, a17: Int, a18: Int, a19: Int, a20: Int, a21: Int, a22: String) =>
      s"${a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8 + a9 + a10 + a11 + a12 + a13 + a14 + a15 + a16 + a17 + a18 + a19 + a20 + a21}$a22"
    p("arities")(s"${f0()} ${f1(1)} ${f2(1, 2)} ${f3(1, 2, 3)} ${f5(1, 2L, 3.0, "d", 'e')} ${f10(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)}")
    p("arity 22")(f22(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, "!"))
    val f22any: Any = f22
    p("arity 22 is a Function22")(f22any.isInstanceOf[Function22[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]])
    p("f3.curried, f3.tupled")(s"${f3.curried(1)(2)(3)} ${f3.tupled((4, 5, 6))}")
    p("method with 22 params as a function")({ def m(a1: Int, a2: Int, a3: Int, a4: Int, a5: Int, a6: Int, a7: Int, a8: Int, a9: Int, a10: Int, a11: Int,
      a12: Int, a13: Int, a14: Int, a15: Int, a16: Int, a17: Int, a18: Int, a19: Int, a20: Int, a21: Int, a22: Int) = a1 * a22
      val g = m
      g(2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 21) })

    // Specialized primitive signatures, through generic code
    val di = (x: Double) => x * 1.5
    val li = (x: Long) => x << 2
    val bi = (x: Int) => x > 2
    val ui = (x: Int) => ()
    p("specialized functions")(s"${di(2.0)} ${li(3L)} ${bi(3)} ${ui(1)}")
    p("specialized through generics")(s"${List(1.0, 2.0).map(di)} ${List(1L).map(li)} ${List(1, 3).filter(bi)} ${generic(4.0)(di)}")
    val anyF: Any => Any = x => x
    p("Any => Any with primitives")(s"${anyF(1)} ${anyF('c')} ${anyF(())}")

    // Partial functions
    val pf: PartialFunction[Any, String] =
      case i: Int if i > 0 => s"positive $i"
      case s: String        => s"string $s"
    val pf2: PartialFunction[Any, String] = { case _ => "fallback" }
    p("isDefinedAt")(List[Any](1, -1, "a", 2.0).map(pf.isDefinedAt))
    p("lift")(List[Any](1, -1).map(pf.lift))
    p("orElse")(List[Any](1, -1, 2.0).map(pf.orElse(pf2)))
    p("applyOrElse")(pf.applyOrElse(-5, (x: Any) => s"default $x"))
    p("collect")(List[Any](1, "x", -2, 3).collect(pf))
    p("pf andThen")((pf andThen (_.length))(7))
    p("MatchError")(scala.util.Try(pf(-1)).failed.get.getClass.getName)

    // SAM conversion: Scala traits and abstract classes, Java functional interfaces
    p("Scala SAM trait")(visitAll(n => "v" * n))
    val red: Reducer[Int] = (a, b) => a max b
    p("generic SAM with a default method")(s"${red.reduce(3, 9)} ${red.name}")
    val h: Handler = _.length
    p("SAM abstract class")(s"${h.handle("abc")} ${h.twice("abcd")}")
    var called = 0
    withCallback(() => called += 1)
    withCallback(() => called += 10)
    p("Unit SAM mutating a var")(called)
    val r: Runnable = () => called = -1
    r.run()
    p("Runnable")(called)
    val cmp: java.util.Comparator[String] = (a, b) => a.length - b.length
    p("Comparator")(List("ccc", "a", "bb").sorted(using Ordering.comparatorToOrdering(using cmp)))
    p("Comparator combinators")({ val al = java.util.ArrayList[String](java.util.List.of("bb", "a", "ab", "c")); al.sort(cmp.thenComparing(java.util.Comparator.reverseOrder[String]())); al })
    val jf: JFunction[Int, String] = x => s"j$x"
    val jsup: Supplier[String] = () => "supplied"
    val jcons: Consumer[String] = x => called = x.length
    val jpred: Predicate[String] = _.isEmpty
    val jbi: BiFunction[Int, Int, Int] = _ * _
    val jun: UnaryOperator[String] = _.reverse
    val jbin: BinaryOperator[Int] = _ + _
    jcons.accept("four")
    p("java.util.function")(s"${jf.apply(1)} ${jsup.get} $called ${jpred.test("")} ${jpred.negate.test("")} ${jbi.apply(6, 7)} ${jun.apply("abc")} ${jbin.apply(1, 2)}")
    p("java Function andThen/compose")(jf.andThen(_ + "!").compose[Int](_ + 1).apply(1))
    val iu: IntUnaryOperator = _ + 1
    val ib: IntBinaryOperator = _ max _
    val ip: IntPredicate = _ % 2 == 0
    val du: DoubleUnaryOperator = _ / 2
    val ls: LongSupplier = () => Long.MaxValue
    val ti: ToIntFunction[String] = _.length
    p("primitive functional interfaces")(s"${iu.applyAsInt(1)} ${iu.andThen(iu).applyAsInt(1)} ${ib.applyAsInt(3, 8)} ${ip.test(4)} ${ip.or(_ => true).test(3)} ${du.applyAsDouble(3)} ${ls.getAsLong} ${ti.applyAsInt("abc")}")
    p("java streams with lambdas")(java.util.stream.IntStream.rangeClosed(1, 5).filter(ip).map(iu).sum())
    p("Scala function as a Java interface")({ val sf: Int => Int = _ * 3; val asJava: JFunction[Int, Int] = sf(_); asJava.apply(5) })

    // By-name parameters: evaluated at each use
    var evals = 0
    p("by-name evaluated each time")(byName(3) { evals += 1; evals })
    p("by-name not evaluated")({ byName(0) { evals += 100; evals }; evals })
    def lazyOr(a: Boolean, b: => Boolean): Boolean = a || b
    p("by-name short circuit")({ var hit = false; lazyOr(true, { hit = true; true }); hit })

    // Recursion through function values and local functions
    lazy val fib: Int => BigInt = n => if n < 2 then n else fib(n - 1) + fib(n - 2)
    p("recursive function value")(fib(20))
    def even(n: Int): Boolean = if n == 0 then true else odd(n - 1)
    def odd(n: Int): Boolean = if n == 0 then false else even(n - 1)
    p("mutually recursive local defs")(s"${even(10)} ${odd(7)}")
    p("Y-ish fixpoint")({ def fix[A, B](f: (A => B) => A => B): A => B = a => f(fix(f))(a); fix[Int, Int](rec => n => if n == 0 then 1 else n * rec(n - 1))(10) })
    p("function equality is identity")({ val g = (x: Int) => x; s"${g == g} ${g == ((x: Int) => x)}" })
