import scala.collection.{immutable, mutable}

/** Library types around functions and data: PartialFunction, Function and FunctionN methods,
  * Product, tuple operations, Equiv, and the specialized maps (IntMap, LongMap). */
object Main:
  def section(name: String): Unit = println(s"-- $name")

  def main(args: Array[String]): Unit =
    partialFunctions()
    functions()
    products()
    tuples()
    specializedMaps()

  def partialFunctions(): Unit =
    section("PartialFunction")
    val small: PartialFunction[Int, String] = { case x if x < 10 => s"small $x" }
    val big: PartialFunction[Int, String] = { case x if x >= 100 => s"big $x" }
    val both = small.orElse(big)
    println(s"${small.isDefinedAt(5)} ${small.isDefinedAt(50)} ${both.isDefinedAt(500)} ${both(500)}")
    println(List(1, 50, 200).collect(both))
    println(s"${both.lift(50)} ${both.lift(5)} ${small.applyOrElse(50, (x: Int) => s"default $x")}")
    println(s"${small.andThen(_.toUpperCase)(3)} ${small.andThen(big.compose[String](_.length * 100)).lift(1)}")
    val unlifted = Function.unlift((x: Int) => Option.when(x % 2 == 0)(x / 2))
    println(s"${unlifted.isDefinedAt(3)} ${unlifted(8)} ${List(1, 2, 3, 4).collect(unlifted)}")
    val parse: PartialFunction[String, Int] = { case s if s.toIntOption.isDefined => s.toInt }
    println(List("1", "x", "3").collect(parse))
    println(s"${PartialFunction.condOpt(4) { case 4 => "four" }} ${PartialFunction.cond(5) { case 4 => true }}")
    println(try small(50) catch case e: MatchError => s"MatchError ${e.getMessage}")
    val fromFunction = PartialFunction.fromFunction((x: Int) => x + 1)
    println(s"${fromFunction.isDefinedAt(-1)} ${PartialFunction.empty[Int, Int].lift(1)}")
    println(Map(1 -> "a").andThen(_.toUpperCase)(1) + " " + List("x", "y").lift(1) + " " + List("x").lift(5))

  def functions(): Unit =
    section("Function")
    val add = (a: Int, b: Int) => a + b
    val inc = (x: Int) => x + 1
    val twice = (x: Int) => x * 2
    println(s"${add.curried(2)(3)} ${add.tupled((4, 5))} ${Function.uncurried(add.curried)(1, 2)}")
    println(s"${(inc andThen twice)(5)} ${(inc compose twice)(5)} ${Function.chain(List(inc, twice, inc))(1)}")
    println(s"${Function.tupled(add)((7, 8))} ${Function.untupled((p: (Int, Int)) => p._1 * p._2)(3, 4)}")
    println(s"${Function.const(9)(1)} ${identity[String]("id")} ${(() => 42)()}")
    val add3 = (a: Int, b: Int, c: Int) => a * 100 + b * 10 + c
    println(s"${add3.curried(1)(2)(3)} ${add3.tupled((3, 2, 1))}")
    val memo = mutable.Map.empty[Int, BigInt]
    lazy val fib: Int => BigInt = n => memo.getOrElseUpdate(n, if n < 2 then BigInt(n) else fib(n - 1) + fib(n - 2))
    println(fib(90))
    val f: Function1[Int, Int] = new Function1[Int, Int]:
      def apply(x: Int): Int = x - 1
      override def toString = "minus one"
    println(s"$f ${f(10)} ${List(1, 2).map(f)}")
    def byName(x: => Int): Int = x + x
    var evaluations = 0
    println(s"${byName({ evaluations += 1; 5 })} $evaluations")
    val ctx: String ?=> Int = summon[String].length
    println(ctx(using "context"))

  case class Point(x: Int, y: Int)
  case class Person(name: String, age: Int, tags: List[String])

  def products(): Unit =
    section("Product")
    val p = Person("Ann", 31, List("a"))
    println(s"${p.productArity} ${p.productPrefix} ${p.productIterator.toList} ${p.productElementNames.toList}")
    println(s"${p.productElement(1)} ${p.productElementName(0)} ${p.canEqual(p)} ${p.copy(age = 32)}")
    println(s"${Point(1, 2) == Point(1, 2)} ${Point(1, 2).hashCode == Point(1, 2).hashCode} ${Point(1, 2).equals(Point(2, 1))}")
    println(s"${(Point.apply).tupled((3, 4))} ${Point.apply(5, 6)} ${Point.unapply(Point(7, 8))}")
    println(s"${Some(1).productArity} ${(1, "a").productPrefix} ${Nil.productArity} ${None.productPrefix}")
    val mirror = summon[deriving.Mirror.ProductOf[Point]]
    println(mirror.fromProduct((9, 10)))
    println(Tuple.fromProductTyped(Point(11, 12)))
    println(scala.runtime.ScalaRunTime._toString(Point(1, 1)))
    println(try p.productElement(5) catch case e: IndexOutOfBoundsException => e.getClass.getSimpleName)

  def tuples(): Unit =
    section("Tuples")
    val t = (1, "two", 3.0)
    println(s"${t._1} ${t._2} ${t._3} ${t.size} ${t.toList} ${t.toArray.length} ${t.productIterator.mkString("|")}")
    println(s"${t.head} ${t.tail} ${t.last} ${t.init} ${t.take(2)} ${t.drop(1)} ${t(1)}")
    println(s"${t ++ (true, 'c')} ${0 *: t} ${t :* "end"} ${t.reverse} ${t.splitAt(1)}")
    println(s"${(1, 2).swap} ${(1, 2).zip(("a", "b"))} ${Tuple()} ${EmptyTuple.size} ${Tuple1("one")}")
    println(s"${(1, 2, 3).map[[X] =>> String]([X] => (x: X) => s"<$x>")}")
    println(s"${Tuple.fromArray(Array(1, 2, 3))} ${Tuple.fromIArray(IArray("a"))} ${(1, 2) == (1, 2)} ${(1, 2).hashCode == (1, 2).hashCode}")
    val big = (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25)
    println(s"${big.size} ${big(24)} ${big.toList.sum} ${big.take(3)} ${big.drop(22)}")
    val (a, b, c) = t
    println(s"$a $b $c")
    val List((k1, v1), _*) = List("k" -> 1): @unchecked
    println(s"$k1 $v1")
    println(List((3, "c"), (1, "a"), (2, "b")).sorted.map(_._2).mkString)
    println(List(1, 2, 3).zip(List("a", "b", "c")).toMap.get(2))

  def specializedMaps(): Unit =
    section("IntMap, LongMap, mutable.LongMap")
    val im = immutable.IntMap(3 -> "c", 1 -> "a", -5 -> "neg")
    println(s"${im} ${im.get(1)} ${(im + (2 -> "b")).keys.toList.sorted} ${im.firstKey} ${im.lastKey}")
    println(s"${im.unionWith(immutable.IntMap(1 -> "A"), (_, x, y) => x + y)} ${im.filter(_._1 > 0).size}")
    println(s"${im.modifyOrRemove((k, v) => if k > 0 then Some(v * 2) else None)}")
    val lm = immutable.LongMap(10000000000L -> 1, 5L -> 2)
    println(s"$lm ${lm(5L)} ${lm.updated(7L, 3).size} ${lm.firstKey}")
    val mlm = mutable.LongMap.empty[String]
    for i <- 1L to 1000L do mlm(i * 3) = i.toString
    mlm -= 3L
    println(s"${mlm.size} ${mlm(2997L)} ${mlm.get(3L)} ${mlm.getOrElse(4L, "none")} ${mlm.keys.max}")
    println(mlm.filter(_._1 < 12).toList.sorted)
    val words = new immutable.WrappedString("wrapped")
    println(s"$words ${words.reverse} ${words.unwrap.length} ${"plain".toSeq.getClass.getSimpleName}")
    val ub = mutable.UnrolledBuffer(1, 2, 3)
    ub += 4
    ub.insert(0, 0)
    println(s"$ub ${ub.length} ${ub(2)}")
