// Case classes, tuples, options and boxes (docs/_docs/spec/data.md, "Primitive types", "Case
// classes, tuples, options and collections", "Classes implemented natively"): generated members
// of case classes, tuples of every arity up to 22 and beyond (TupleXXL), tuples of primitives
// (the compiler's specialized tuple classes), generic tuple operations, boxing and unboxing
// through generic code, and the hijacked classes reached through their supertypes.
case class Point(x: Int, y: Int):
  def +(o: Point): Point = Point(x + o.x, y + o.y)
case class Named(name: String, tags: List[String] = Nil, weight: Double = 1.0)
case class Nested(p: Point, n: Option[Named])
case class Many(a: Byte, b: Short, c: Char, d: Int, e: Long, f: Float, g: Double, h: Boolean, i: Unit, j: String)
case class Var(xs: Int*)
case class Gen[A, B](a: A, b: B)
case class Curried(a: Int)(val b: Int)
case object Empty
final case class Money(cents: Long):
  override def toString: String = f"$$${cents / 100}%d.${cents % 100}%02d"
sealed trait Expr
case class Num(v: Double) extends Expr
case class Add(l: Expr, r: Expr) extends Expr
case class Mul(l: Expr, r: Expr) extends Expr

object Main:
  def p(what: String)(body: => Any): Unit = println(s"$what: ${String.valueOf(body)}")
  def eval(e: Expr): Double = e match
    case Num(v)    => v
    case Add(l, r) => eval(l) + eval(r)
    case Mul(l, r) => eval(l) * eval(r)
  def box[A](a: A): Any = a
  def roundTrip[A](a: A): A = List(a).head

  def main(args: Array[String]): Unit =
    // Case classes: toString, equality, copy, unapply, product members
    val pt = Point(1, 2)
    p("toString")(s"$pt ${Named("n")} ${Nested(pt, Some(Named("x", List("a"))))} ${Empty} ${Money(12345)} ${Var(1, 2)} ${Gen('c', 2.5f)}")
    p("Many toString")(Many(1, 2, 'c', 4, 5L, 6.5f, 7.25, true, (), null))
    p("copy")(s"${pt.copy(y = 9)} ${Named("a").copy(weight = 2)} ${pt.copy()} ${pt.copy() == pt} ${pt.copy() eq pt}")
    p("defaults")(Named("d"))
    p("operator method")(pt + Point(10, 20))
    p("unapply")({ val Point(a, b) = pt; a * 10 + b })
    p("nested patterns")(Nested(pt, Some(Named("z", List("t1", "t2")))) match
      case Nested(Point(1, y), Some(Named(n, first :: _, _))) => s"$y $n $first"
      case _                                                   => "no")
    p("varargs pattern")(Var(1, 2, 3) match { case Var(a, rest*) => s"$a ${rest.toList}"; case _ => "?" })
    p("productArity, productPrefix")(s"${pt.productArity} ${pt.productPrefix} ${Many(1, 2, 'c', 4, 5L, 6.5f, 7.25, true, (), "").productArity} ${Empty.productArity} ${Empty.productPrefix}")
    p("productElement")(s"${pt.productElement(0)} ${Named("q").productElement(2)}")
    p("productIterator")(Many(1, 2, 'c', 4, 5L, 6.5f, 7.25, true, (), "s").productIterator.map(x => s"$x:${x.getClass.getSimpleName}").toList)
    p("productElementNames")(Named("n").productElementNames.toList)
    p("productElement out of range")(scala.util.Try(pt.productElement(2)).failed.get.getClass.getName)
    p("second parameter list")({ val c1 = Curried(1)(2); val c2 = Curried(1)(3); s"$c1 ${c1 == c2} ${c2.b}" })
    p("companion apply and tupled")(s"${Point.apply(3, 4)} ${(Point.apply).tupled((5, 6))}")
    p("case class in Set and Map")(s"${Set(Point(1, 1), Point(1, 1), Point(2, 2)).size} ${Map(Point(0, 0) -> "origin")(Point(0, 0))}")
    p("ordering by fields")(List(Point(2, 1), Point(1, 5), Point(1, 2)).sortBy(p => (p.x, p.y)))
    p("eval sealed tree")(eval(Add(Num(1.5), Mul(Num(2), Num(3)))))
    p("isInstanceOf Product, Serializable")(s"${(pt: Any).isInstanceOf[Product]} ${(pt: Any).isInstanceOf[java.io.Serializable]} ${(Empty: Any).isInstanceOf[Product]}")

    // Tuples: every arity up to 22, and beyond
    val t2 = (1, "two")
    val t3 = (1, 2L, 3.0)
    val t5 = ('a', 1.toByte, 2.toShort, 3.5f, false)
    val t10 = (1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
    val t22 = (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, "22")
    val t23 = (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, "23")
    val t25 = 'x' *: t23 :* 25L
    p("tuple toString")(s"$t2 $t3 $t5 $t10")
    p("Tuple22")(s"$t22 ${t22._22} ${t22._1 + t22._21} ${t22.productArity}")
    p("Tuple23 (TupleXXL)")(s"$t23 ${t23.size} ${t23(22)} ${t23.productArity}")
    p("Tuple25")(s"${t25.size} ${t25.head} ${t25(24)} ${t25.toList.takeRight(2)}")
    p("Tuple23 equality and hash")(s"${t23 == (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, "23")} ${t23.hashCode == t23.toList.hashCode}")
    p("tuple accessors")(s"${t2._1} ${t2._2} ${t3._2 + 1} ${t5._1} ${t5._4 * 2} ${t5._5}")
    p("swap")(t2.swap)
    p("tuple equality")(s"${t2 == (1, "two")} ${t3 == (1, 2L, 3.0)} ${(1, 2) == (1L, 2.0)}")
    p("tuple patterns")(List((1, 'a'), (2, 'b')).map { case (n, c) => s"$c$n" })
    p("nested tuples")({ val ((a, b), (c, d)) = ((1, 2), (3, 4)); a + b * c + d })
    p("generic tuple ops")(s"${t3.size} ${t3.head} ${t3.tail} ${t3.toList} ${t2 ++ t3} ${t3.drop(1)} ${t3.take(2)} ${t3.splitAt(1)}")
    p("*: and EmptyTuple")(s"${1 *: "a" *: EmptyTuple} ${EmptyTuple.size} ${(1 *: EmptyTuple) == Tuple1(1)}")
    p("Tuple.fromArray, fromProduct")(s"${Tuple.fromArray(Array[Object]("a", "b"))} ${Tuple.fromProductTyped(pt)}")
    p("tuple map")((1, 2.5, "s").map[[X] =>> String]([X] => (x: X) => x.toString * 2))
    p("zip of tuples")((1, 2).zip(("a", "b")))
    p("named tuples")({ val nt = (name = "Bob", age = 42); s"${nt.name} ${nt.age} ${nt.toTuple}" })

    // Tuples of primitives: specialized classes in the compiler, generic at run time
    def minMax(xs: Array[Int]): (Int, Int) = (xs.min, xs.max)
    def divmod(a: Long, b: Long): (Long, Long) = (a / b, a % b)
    def charPair(c: Char): (Char, Char) = (c.toUpper, c.toLower)
    def dbl(d: Double): (Double, Int) = (d * 2, d.toInt)
    def boolPair(b: Boolean): (Boolean, Boolean) = (b, !b)
    p("specialized tuples")(s"${minMax(Array(3, -1, 7))} ${divmod(-7, 2)} ${charPair('q')} ${dbl(1.25)} ${boolPair(true)}")
    val (mn, mx) = minMax(Array(5, 9))
    p("destructured")(mn + mx)
    p("tuple element classes")(dbl(2).productIterator.map(_.getClass.getName).toList)

    // Option, Either and Try values
    p("Option")(s"${Some(1).map(_ + 1)} ${Option.empty[Int].getOrElse(0)} ${Option(null)} ${Some(Some(1)).flatten} ${Some(3).filter(_ > 5)}")
    p("Option of primitives")(s"${Some('c').map(_.toInt)} ${Some(2.5).fold(0.0)(_ * 2)} ${Some(true).contains(true)}")
    p("Either")(s"${Right(2).map(_ * 2)} ${Left[String, Int]("e").map(_ * 2)} ${Right(1).flatMap(x => Left(s"bad $x"))} ${Either.cond(false, 1, "no")}")
    p("Try")(s"${scala.util.Try(1 / args.length).isFailure} ${scala.util.Try(1).get}")

    // Boxing through generic code: every primitive type round trips
    p("boxed classes")(List(box(1.toByte), box(2.toShort), box('c'), box(4), box(5L), box(6.5f), box(7.25), box(true), box(())).map(_.getClass.getName))
    p("round trips")(s"${roundTrip(Byte.MinValue)} ${roundTrip(Short.MaxValue)} ${roundTrip('\uffff').toInt} ${roundTrip(Int.MinValue)} ${roundTrip(Long.MaxValue)} ${roundTrip(Float.MinPositiveValue)} ${roundTrip(-0.0)} ${roundTrip(Double.NaN)} ${roundTrip(())}")
    p("generic container of primitives")({ val g = Gen(1, 'x'); val h = g.copy(b = 2.5); s"$h ${h.a + 1} ${h.b * 2}" })
    p("unboxing null through generics")({ def zero[T](using n: Numeric[T]): T = n.zero; val l: List[Int] = List(null.asInstanceOf[Int]); s"${l.head} ${zero[Long]} ${zero[Double]}" })
    p("Integer.valueOf, intValue")(s"${Integer.valueOf(7).intValue + 1} ${java.lang.Long.valueOf(8).longValue} ${java.lang.Double.valueOf(1.5).doubleValue} ${Character.valueOf('k').charValue}")
    p("Predef conversions")({ val j: java.lang.Integer = 5; val s: Int = j; val jd: java.lang.Double = 2.5; s"${s + 1} ${jd + 1}" })
    p("boxes in java collections")({ val al = java.util.ArrayList[Any](); al.add(1); al.add(2L); al.add('c'); al.add(1.5f); al.toString })
    p("BoxedUnit")(s"${box(()) == scala.runtime.BoxedUnit.UNIT} ${box(())} ${scala.runtime.BoxedUnit.UNIT.toString}")
    p("Class values")(s"${classOf[String] eq "a".getClass} ${classOf[Point].getName} ${classOf[Array[Int]].getName} ${classOf[java.math.BigInteger].getName}")

    // Natively implemented classes through their supertypes
    val nums: List[Number] = List(Integer.valueOf(1), java.lang.Long.valueOf(2), java.lang.Double.valueOf(3.5), java.lang.Float.valueOf(4.5f),
      java.lang.Byte.valueOf(5.toByte), java.lang.Short.valueOf(6.toShort), java.math.BigInteger.valueOf(7), java.math.BigDecimal.valueOf(8.25))
    p("Number.doubleValue")(nums.map(_.doubleValue))
    p("Number.intValue")(nums.map(_.intValue))
    p("Number.toString")(nums.map(_.toString))
    val comps: List[Comparable[Any]] = List[Any]("b", Integer.valueOf(2), java.lang.Double.valueOf(2.0), Character.valueOf('b'), java.lang.Boolean.TRUE,
      java.math.BigInteger.TWO).map(_.asInstanceOf[Comparable[Any]])
    p("Comparable.compareTo")(comps.zip(List[Any]("a", 3, 2.0, 'c', false, java.math.BigInteger.ONE)).map((c, o) => Integer.signum(c.compareTo(o))))
    val seqs: List[CharSequence] = List("abc", new java.lang.StringBuilder("defg"), new StringBuilder("hi"))
    p("CharSequence")(seqs.map(cs => s"${cs.length}:${cs.charAt(0)}:${cs.subSequence(0, 2)}:$cs"))
    p("hashCode via Any")(List[Any]("s", 1, 1L, 1.5, true, 'c', java.math.BigInteger.TEN).map(_.hashCode))
    p("equals via Any")(List[(Any, Any)](("s", "s"), (1, 1), (1, 1L), (java.math.BigInteger.ONE, java.math.BigInteger.valueOf(1)), ('a', 'a')).map((a, b) => a.equals(b)))
    p("BigInt and BigDecimal wrappers")(s"${BigInt(2).pow(100)} ${BigInt("-123456789012345678901234567890") * 2} ${BigDecimal("1.10") + BigDecimal("2.205")} ${BigInt(10).bigInteger.getClass.getName}")
