import java.util as ju
import java.util.function as jf
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

// String.CASE_INSENSITIVE_ORDER is not available yet (a java.lang static field).
val CaseInsensitive: java.util.Comparator[String] = (a, b) => a.compareToIgnoreCase(b)

/** java.util.function from Scala lambdas, Comparator combinators, default methods taking lambdas,
  * scala.jdk converters, and Scala classes extending javalib abstract collections.
  */
object Main:
  def section(name: String): Unit = println(s"== $name")

  def attempt(label: String)(body: => Any): Unit =
    try println(s"$label: ${body}")
    catch case e: Exception => println(s"$label: threw ${e.getClass.getName}")

  case class Emp(name: String, dept: String, age: Int, salary: Long, rating: Double)

  val staff: List[Emp] = List(
    Emp("Mia", "eng", 34, 120000L, 4.5),
    Emp("Ann", "ops", 29, 80000L, 3.9),
    Emp("Bob", "eng", 41, 135000L, 4.1),
    Emp("Zed", "ops", 29, 82000L, 4.8),
    Emp("Cat", "hr", 52, 99000L, 3.9),
    Emp("Dan", "eng", 34, 120000L, 4.0),
    Emp("Eve", "hr", 23, 61000L, 4.9),
  )

  def sorted(cmp: ju.Comparator[Emp]): String =
    val l = new ju.ArrayList[Emp]()
    staff.foreach(l.add)
    l.sort(cmp)
    l.asScala.map(_.name).mkString(" ")

  def main(args: Array[String]): Unit =
    functions()
    primitives()
    comparators()
    defaultMethods()
    converters()
    userClasses()

  def functions(): Unit =
    section("Function / BiFunction / UnaryOperator / BinaryOperator")
    val inc: jf.Function[Integer, Integer] = x => x + 1
    val dbl: jf.Function[Integer, Integer] = x => x * 2
    println(s"apply ${inc.apply(5)} andThen ${inc.andThen(dbl).apply(5)} compose ${inc.compose(dbl).apply(5)}")
    val toStr: jf.Function[Integer, String] = i => s"<$i>"
    println(s"chain ${inc.andThen(dbl).andThen(toStr).apply(10)}")
    println(s"identity ${jf.Function.identity[String]().apply("same")} ${jf.Function.identity[String]().andThen((s: String) => s.length).apply("four")}")
    val add: jf.BiFunction[Integer, Integer, Integer] = (a, b) => a + b
    println(s"BiFunction ${add.apply(3, 4)} andThen ${add.andThen(toStr).apply(3, 4)}")
    val up: jf.UnaryOperator[String] = _.toUpperCase
    println(s"UnaryOperator ${up.apply("abc")} identity ${jf.UnaryOperator.identity[String]().apply("id")} andThen ${up.andThen((s: String) => s + "!").apply("x")}")
    val cmp: ju.Comparator[String] = (a, b) => Integer.compare(a.length, b.length)
    val minBy = jf.BinaryOperator.minBy[String](cmp)
    val maxBy = jf.BinaryOperator.maxBy[String](cmp)
    println(s"minBy ${minBy.apply("aaa", "bb")} ${minBy.apply("cc", "dd")} maxBy ${maxBy.apply("aaa", "bb")} ${maxBy.apply("cc", "dd")}")
    val concat: jf.BinaryOperator[String] = _ + _
    println(s"BinaryOperator ${concat.apply("con", "cat")} andThen ${concat.andThen(_.length).apply("ab", "cd")}")

    section("Predicate / BiPredicate")
    val even: jf.Predicate[Integer] = _ % 2 == 0
    val pos: jf.Predicate[Integer] = _ > 0
    val nums = List(-4, -3, 0, 1, 2, 7, 8)
    def show(p: jf.Predicate[Integer]) = nums.filter(n => p.test(n)).mkString(",")
    println(s"even ${show(even)} pos ${show(pos)}")
    println(s"and ${show(even.and(pos))} or ${show(even.or(pos))} negate ${show(even.negate)}")
    println(s"not ${show(jf.Predicate.not(pos))} complex ${show(even.negate.and(pos).or(_ == -4))}")
    val isA = jf.Predicate.isEqual[String]("a")
    val isNull = jf.Predicate.isEqual[String](null)
    println(s"isEqual ${isA.test("a")} ${isA.test("b")} ${isA.test(null)} null ${isNull.test(null)} ${isNull.test("a")}")
    val longer: jf.BiPredicate[String, Integer] = (s, n) => s.length > n
    println(s"BiPredicate ${longer.test("abc", 2)} ${longer.test("abc", 3)} negate ${longer.negate.test("abc", 3)} and ${longer.and((s, _) => s.startsWith("a")).test("abc", 1)} or ${longer.or((_, n) => n < 0).test("", -1)}")

    section("Supplier / Consumer / BiConsumer")
    var counter = 0
    val sup: jf.Supplier[String] = () => { counter += 1; s"call$counter" }
    println(s"Supplier ${sup.get} ${sup.get} ${sup.get}")
    val log = new StringBuilder
    val c1: jf.Consumer[String] = s => log.append(s"[1:$s]")
    val c2: jf.Consumer[String] = s => log.append(s"[2:$s]")
    c1.andThen(c2).andThen(c1).accept("x")
    println(s"Consumer.andThen $log")
    val bc: jf.BiConsumer[String, Integer] = (k, v) => log.append(s"($k=$v)")
    bc.andThen((k, v) => log.append(s"{${k * v}}")).accept("ab", 2)
    println(s"BiConsumer.andThen $log")
    val bs: jf.BooleanSupplier = () => counter > 2
    println(s"BooleanSupplier ${bs.getAsBoolean}")

  def primitives(): Unit =
    section("primitive specializations")
    val intF: jf.IntFunction[String] = i => "#" * i
    println(s"IntFunction ${intF.apply(3)}")
    val small: jf.IntPredicate = _ < 10
    val odd: jf.IntPredicate = _ % 2 != 0
    val r = (0 to 14).toList
    println(s"IntPredicate and ${r.filter(small.and(odd).test).mkString(",")} or ${r.filter(small.negate.or(odd).test).mkString(",")} negate ${r.filter(odd.negate.test).mkString(",")}")
    val sq: jf.IntUnaryOperator = x => x * x
    val plus3: jf.IntUnaryOperator = _ + 3
    println(s"IntUnaryOperator andThen ${sq.andThen(plus3).applyAsInt(4)} compose ${sq.compose(plus3).applyAsInt(4)} identity ${jf.IntUnaryOperator.identity.applyAsInt(9)}")
    val mul: jf.IntBinaryOperator = _ * _
    println(s"IntBinaryOperator ${mul.applyAsInt(6, 7)} overflow ${mul.applyAsInt(65536, 65536)}")
    val len: jf.ToIntFunction[String] = _.length
    val lenL: jf.ToLongFunction[String] = s => s.length.toLong << 32
    val lenD: jf.ToDoubleFunction[String] = _.length / 4.0
    println(s"ToIntFunction ${len.applyAsInt("hello")} ToLongFunction ${lenL.applyAsLong("hi")} ToDoubleFunction ${lenD.applyAsDouble("abc")}")
    var seed = 1L
    val ls: jf.LongSupplier = () => { seed = seed * 6364136223846793005L + 1442695040888963407L; seed }
    println(s"LongSupplier ${ls.getAsLong} ${ls.getAsLong} ${ls.getAsLong}")
    val is: jf.IntSupplier = () => 42
    val ds: jf.DoubleSupplier = () => 0.1 + 0.2
    println(s"IntSupplier ${is.getAsInt} DoubleSupplier ${ds.getAsDouble}")
    val half: jf.DoubleUnaryOperator = _ / 2
    val neg: jf.DoubleUnaryOperator = -_
    println(s"DoubleUnaryOperator ${half.andThen(neg).applyAsDouble(5)} ${half.compose(neg).applyAsDouble(-0.0)} identity ${jf.DoubleUnaryOperator.identity.applyAsDouble(1.5)}")
    val hyp: jf.DoubleBinaryOperator = (a, b) => math.sqrt(a * a + b * b)
    println(s"DoubleBinaryOperator ${hyp.applyAsDouble(3, 4)}")
    val lmax: jf.LongBinaryOperator = math.max
    val lneg: jf.LongUnaryOperator = -_
    println(s"LongBinaryOperator ${lmax.applyAsLong(Long.MinValue, -1)} LongUnaryOperator ${lneg.andThen(_ + 1).applyAsLong(Long.MaxValue)}")
    val sb = new StringBuilder
    val oic: jf.ObjIntConsumer[String] = (s, i) => sb.append(s * i)
    oic.accept("ab", 3)
    val olc: jf.ObjLongConsumer[String] = (s, l) => sb.append(s"|$s$l")
    olc.accept("L", 1L << 40)
    val odc: jf.ObjDoubleConsumer[String] = (s, d) => sb.append(s"|$s$d")
    odc.accept("D", 1e-7)
    println(s"Obj*Consumer $sb")
    val ic: jf.IntConsumer = i => sb.append(s"<$i>")
    sb.clear()
    ic.andThen(i => sb.append(s"{${i * 2}}")).accept(21)
    val lc: jf.LongConsumer = l => sb.append(s"[$l]")
    lc.andThen(l => sb.append(s"[${l + 1}]")).accept(-1)
    val dc: jf.DoubleConsumer = d => sb.append(s"($d)")
    dc.andThen(d => sb.append(s"(${d * 2})")).accept(0.25)
    println(s"Int/Long/DoubleConsumer.andThen $sb")
    val i2l: jf.IntToLongFunction = i => i.toLong * i
    val i2d: jf.IntToDoubleFunction = i => i / 3.0
    val l2i: jf.LongToIntFunction = l => (l >>> 32).toInt
    val d2i: jf.DoubleToIntFunction = _.toInt
    val d2l: jf.DoubleToLongFunction = math.round
    println(s"conversions ${i2l.applyAsLong(100000)} ${i2d.applyAsDouble(1)} ${l2i.applyAsInt(1L << 35)} ${d2i.applyAsInt(-2.7)} ${d2l.applyAsLong(2.5)}")
    val tib: jf.ToIntBiFunction[String, String] = (a, b) => a.compareTo(b)
    val tdb: jf.ToDoubleBiFunction[Integer, Integer] = (a, b) => a.toDouble / b
    val tlb: jf.ToLongBiFunction[Integer, Integer] = (a, b) => a.toLong * b
    println(s"To*BiFunction ${tib.applyAsInt("b", "a")} ${tdb.applyAsDouble(1, 8)} ${tlb.applyAsLong(Int.MaxValue, 4)}")
    val ip: jf.LongPredicate = _ > 0
    val dp: jf.DoublePredicate = d => d.isNaN
    println(s"Long/DoublePredicate ${ip.test(-1)} ${ip.negate.test(-1)} ${dp.test(Double.NaN)} ${dp.or(_ > 1).test(2.0)} ${dp.and(_ > 1).test(2.0)}")
    val lf: jf.LongFunction[String] = l => java.lang.Long.toHexString(l)
    val df: jf.DoubleFunction[String] = d => java.lang.Double.toString(d)
    println(s"Long/DoubleFunction ${lf.apply(-1L)} ${df.apply(1.0 / 3)}")

  def comparators(): Unit =
    section("Comparator")
    println(s"comparing(name) ${sorted(ju.Comparator.comparing[Emp, String](_.name))}")
    println(s"comparing(name, reverseOrder) ${sorted(ju.Comparator.comparing[Emp, String](_.name, ju.Comparator.reverseOrder[String]()))}")
    println(s"comparing(name, CASE_INSENSITIVE by last char) ${sorted(ju.Comparator.comparing[Emp, String](_.name.reverse, CaseInsensitive))}")
    println(s"comparingInt(age) ${sorted(ju.Comparator.comparingInt[Emp](_.age))}")
    println(s"comparingLong(salary) ${sorted(ju.Comparator.comparingLong[Emp](_.salary))}")
    println(s"comparingDouble(rating) ${sorted(ju.Comparator.comparingDouble[Emp](_.rating))}")
    val byDept = ju.Comparator.comparing[Emp, String](_.dept)
    val byAge = ju.Comparator.comparingInt[Emp](_.age)
    println(s"dept thenComparing(cmp) ${sorted(byDept.thenComparing(byAge))}")
    println(s"dept thenComparing(key) ${sorted(byDept.thenComparing[String]((e: Emp) => e.name))}")
    println(s"dept thenComparing(key, cmp) ${sorted(byDept.thenComparing[String]((e: Emp) => e.name, ju.Comparator.reverseOrder[String]()))}")
    println(s"dept thenComparingInt ${sorted(byDept.thenComparingInt(_.age))}")
    println(s"dept thenComparingLong reversed ${sorted(byDept.thenComparingLong(_.salary).reversed)}")
    println(s"dept thenComparingDouble ${sorted(byDept.thenComparingDouble(_.rating))}")
    println(s"age reversed then name ${sorted(byAge.reversed.thenComparing(ju.Comparator.comparing[Emp, String](_.name)))}")
    println(s"salary desc, rating desc, name ${sorted(ju.Comparator.comparingLong[Emp](_.salary).reversed.thenComparing(ju.Comparator.comparingDouble[Emp](_.rating).reversed).thenComparing((a: Emp, b: Emp) => a.name.compareTo(b.name)))}")
    val words = new ju.ArrayList[String](ju.List.of("pear", "Fig", "apple", "fig", "Banana", "kiwi"))
    words.sort(ju.Comparator.naturalOrder[String]())
    println(s"naturalOrder $words")
    words.sort(ju.Comparator.reverseOrder[String]())
    println(s"reverseOrder $words")
    words.sort(CaseInsensitive.thenComparing(ju.Comparator.naturalOrder[String]()))
    println(s"case-insensitive then natural $words")
    println(s"naturalOrder.reversed eq-like ${ju.Comparator.naturalOrder[String]().reversed.compare("a", "b") > 0} ${ju.Comparator.reverseOrder[String]().reversed.compare("a", "b") < 0}")
    val withNulls = new ju.ArrayList[String]()
    for s <- List("b", null, "a", "c", null) do withNulls.add(s)
    withNulls.sort(ju.Comparator.nullsFirst(ju.Comparator.naturalOrder[String]()))
    println(s"nullsFirst $withNulls")
    withNulls.sort(ju.Comparator.nullsLast(ju.Comparator.reverseOrder[String]()))
    println(s"nullsLast(reverse) $withNulls")
    withNulls.sort(ju.Comparator.nullsFirst[String](null))
    println(s"nullsFirst(null) keeps order $withNulls")
    val emps = new ju.ArrayList[Emp]()
    emps.add(staff(0)); emps.add(null); emps.add(staff(1))
    emps.sort(ju.Comparator.nullsLast(ju.Comparator.comparing[Emp, String](_.name)))
    println(s"nullsLast emps ${emps.asScala.map(e => if e == null then "null" else e.name).mkString(" ")}")
    attempt("naturalOrder with null")(ju.Comparator.naturalOrder[String]().compare(null, "a"))
    println(s"max by rating ${ju.Collections.max(staff.asJava, ju.Comparator.comparingDouble[Emp](_.rating)).name} min by age then name ${ju.Collections.min(staff.asJava, byAge.thenComparing((e: Emp) => e.name)).name}")

  def defaultMethods(): Unit =
    section("default methods taking lambdas")
    val l = new ju.ArrayList[Integer]()
    for i <- 1 to 10 do l.add(i)
    val sb = new StringBuilder
    l.forEach(i => sb.append(i).append(';'))
    println(s"Iterable.forEach $sb")
    println(s"removeIf ${l.removeIf(_ % 3 == 0)} ${l.removeIf(_ > 100)} -> $l")
    l.replaceAll(i => i * i)
    println(s"List.replaceAll $l")
    val it = l.iterator
    it.next(); it.next()
    val rest = new StringBuilder
    it.forEachRemaining(i => rest.append(i).append(' '))
    println(s"forEachRemaining ${rest.toString.trim} hasNext=${it.hasNext}")
    val m = new ju.HashMap[String, Integer]()
    for w <- List("alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta") do m.put(w, w.length)
    val ms = new StringBuilder
    m.forEach((k, v) => ms.append(s"$k:$v "))
    println(s"Map.forEach ${ms.toString.trim}")
    m.replaceAll((k, v) => if k.contains("e") then v * 10 else v)
    println(s"Map.replaceAll $m")
    m.values.removeIf(_ > 40)
    println(s"values.removeIf $m")
    val ts = new ju.TreeSet[String](m.keySet)
    val tsb = new StringBuilder
    ts.descendingSet.forEach(s => tsb.append(s.head))
    println(s"TreeSet descending forEach $tsb")
    val dq = new ju.ArrayDeque[String](ju.List.of("x", "y", "z"))
    val dsb = new StringBuilder
    dq.forEach(s => dsb.append(s))
    dq.descendingIterator.forEachRemaining(s => dsb.append(s.toUpperCase))
    println(s"ArrayDeque forEach $dsb")
    val sorted = new ju.TreeMap[String, Integer](m)
    sorted.entrySet.forEach(e => e.setValue(-e.getValue))
    println(s"entry setValue in forEach $sorted")

  def converters(): Unit =
    section("CollectionConverters")
    val seq = Seq(1, 2, 3)
    val jl = seq.asJava
    println(s"Seq.asJava $jl size=${jl.size} get=${jl.get(1)} contains=${jl.contains(2)} indexOf=${jl.indexOf(3)}")
    attempt("Seq.asJava add")(jl.add(4))
    println(s"List.asJava ${List("a", "b").asJava} equals ${List("a", "b").asJava.equals(ju.List.of("a", "b"))} hash ${List("a", "b").asJava.hashCode == ju.List.of("a", "b").hashCode}")
    val buf: mutable.Buffer[String] = mutable.ArrayBuffer("x", "y")
    val jbuf = buf.asJava
    jbuf.add("z"); jbuf.set(0, "X"); jbuf.add("ins")
    println(s"remove(int) ${jbuf.remove(1)}")
    // The Scala wrapper overrides only get/set/add(elem)/remove(int); the rest falls back to AbstractList.
    attempt("Buffer.asJava add(index, elem)")(jbuf.add(1, "nope"))
    attempt("Buffer.asJava remove(Object)")(jbuf.remove("z"))
    println(s"Buffer.asJava writes through buf=$buf java=$jbuf")
    buf += "tail"
    println(s"Buffer change visible in java $jbuf size=${jbuf.size}")
    jbuf.sort(ju.Comparator.reverseOrder[String]())
    println(s"sort via java view $buf")
    val roundTrip = jbuf.asScala
    println(s"round trip identity ${roundTrip eq buf}")
    val mm = mutable.LinkedHashMap("one" -> 1, "two" -> 2)
    val jm = mm.asJava
    jm.put("three", 3); jm.remove("one"); jm.merge("two", 20, (a, b) => a + b)
    println(s"mutable.Map.asJava mm=$mm java=$jm get=${jm.get("two")} containsKey=${jm.containsKey("one")}")
    val imm = Map("k" -> "v").asJava
    attempt("immutable Map.asJava put")(imm.put("a", "b"))
    println(s"immutable Map.asJava $imm get=${imm.get("k")}")
    val js = Set(3, 1, 2).asJava
    println(s"Set.asJava size=${js.size} contains=${js.contains(2)} ${js.contains(5)} sorted=${new ju.TreeSet[Int](js)}")
    val ms = mutable.TreeSet(5, 1, 3)
    ms.asJava.add(2)
    println(s"mutable.Set.asJava add $ms")
    val al = new ju.ArrayList[String](ju.List.of("a", "b", "c"))
    val sbuf = al.asScala
    sbuf += "d"
    sbuf.remove(0)
    sbuf.insert(1, "B")
    sbuf(0) = "BB"
    println(s"ArrayList.asScala java=$al scala=${sbuf.mkString("Buffer(", ", ", ")")} length=${sbuf.length}")
    println(s"asScala ops ${sbuf.map(_.toUpperCase).toList} ${sbuf.filter(_.length == 1).toList} ${sbuf.reverse.mkString} ${sbuf.indexOf("c")}")
    println(s"asScala round trip ${sbuf.asJava eq al}")
    val hm = new ju.HashMap[String, Integer]()
    hm.put("a", 1); hm.put("b", 2)
    val sm = hm.asScala
    sm += ("c" -> 3)
    sm -= "a"
    sm("b") = 22
    println(s"HashMap.asScala java=$hm get=${sm.get("b")} ${sm.get("a")} getOrElse=${sm.getOrElse("z", 0)} keys=${sm.keys.toList.sorted}")
    println(s"asScala map ops ${sm.toList.sortBy(_._1)} ${sm.map((k, v) => k * 2 -> v).toList.sortBy(_._1)}")
    val tm = new ju.TreeMap[Integer, String]()
    tm.put(3, "c"); tm.put(1, "a"); tm.put(2, "b")
    println(s"TreeMap.asScala ${tm.asScala.toList} ${tm.asScala.keys.mkString(",")}")
    val it = ju.List.of("p", "q", "r").iterator.asScala
    println(s"Iterator.asScala ${it.map(_ * 2).mkString("-")}")
    val jit = Iterator(1, 2, 3).asJava
    val jsb = new StringBuilder
    while jit.hasNext do jsb.append(jit.next())
    println(s"Iterator.asJava $jsb")
    val hs = new ju.TreeSet[String](ju.List.of("m", "n"))
    val ss = hs.asScala
    ss += "a"
    ss -= "n"
    println(s"TreeSet.asScala java=$hs scala=${ss.toList}")
    println(s"Iterable.asJava ${Vector(1, 2).asJava.iterator.next()} asJavaCollection size=${List(1, 2, 3).asJavaCollection.size}")
    val en = ju.Collections.enumeration(ju.List.of("e1", "e2"))
    println(s"Enumeration.asScala ${en.asScala.toList}")
    println(s"toList ${new ju.LinkedList[Integer](ju.List.of[Integer](4, 5, 6)).asScala.toList.map(_ + 1)}")

    section("FunctionConverters")
    import scala.jdk.FunctionConverters.*
    val sf: String => String = _.reverse
    val jfun: jf.Function[String, String] = sf.asJavaFunction
    println(s"asJavaFunction ${jfun.apply("abc")} ${jfun.andThen(_.toUpperCase).apply("xyz")}")
    val pred: Int => Boolean = _ > 2
    val jpred: jf.IntPredicate = pred.asJava
    println(s"asJava IntPredicate ${jpred.test(3)} ${jpred.test(1)} ${jpred.negate.test(1)}")
    val back: Int => Int = (jf.IntUnaryOperator.identity.andThen(_ + 1)).asScala
    println(s"IntUnaryOperator.asScala ${back(41)}")
    val jsup: jf.Supplier[String] = () => "supplied"
    println(s"Supplier.asScala ${jsup.asScala()}")

  // ---- user-defined classes extending javalib types

  class ByLengthThenAlpha extends ju.Comparator[String]:
    def compare(a: String, b: String): Int =
      val c = Integer.compare(a.length, b.length)
      if c != 0 then c else a.compareTo(b)

  class Squares(n: Int) extends ju.AbstractList[Integer]:
    def get(i: Int): Integer =
      if i < 0 || i >= n then throw new IndexOutOfBoundsException(s"$i")
      i * i
    def size(): Int = n

  class Growable extends ju.AbstractList[String]:
    private val data = mutable.ArrayBuffer.empty[String]
    def get(i: Int): String = data(i)
    def size(): Int = data.size
    override def set(i: Int, s: String): String =
      val old = data(i); data(i) = s; old
    override def add(i: Int, s: String): Unit =
      data.insert(i, s); modCount += 1
    override def remove(i: Int): String =
      modCount += 1; data.remove(i)

  class Countdown(from: Int) extends ju.Iterator[Integer]:
    private var cur = from
    def hasNext: Boolean = cur > 0
    def next(): Integer =
      if cur <= 0 then throw new ju.NoSuchElementException()
      cur -= 1; cur + 1

  class Powers(base: Int, n: Int) extends ju.AbstractMap[String, Integer]:
    def entrySet(): ju.Set[ju.Map.Entry[String, Integer]] =
      new ju.AbstractSet[ju.Map.Entry[String, Integer]]:
        def size(): Int = n
        def iterator(): ju.Iterator[ju.Map.Entry[String, Integer]] =
          new ju.Iterator[ju.Map.Entry[String, Integer]]:
            private var i = 0
            def hasNext: Boolean = i < n
            def next(): ju.Map.Entry[String, Integer] =
              val e = new ju.AbstractMap.SimpleImmutableEntry[String, Integer](s"$base^$i", math.pow(base, i).toInt)
              i += 1
              e

  class Evens(limit: Int) extends ju.AbstractSet[Integer]:
    def size(): Int = limit / 2
    def iterator(): ju.Iterator[Integer] = new Countdown(limit / 2) {
      override def next(): Integer = super.next() * 2
    }

  def userClasses(): Unit =
    section("user Comparator")
    val words = new ju.ArrayList[String](ju.List.of("ccc", "a", "bb", "aa", "b", "abc"))
    words.sort(new ByLengthThenAlpha)
    println(s"sorted $words")
    words.sort(new ByLengthThenAlpha().reversed)
    println(s"reversed $words")
    val ts = new ju.TreeSet[String](new ByLengthThenAlpha)
    ts.addAll(words); ts.add("zz")
    println(s"TreeSet with user comparator $ts first=${ts.first} ceiling(b)=${ts.ceiling("b")}")
    println(s"thenComparing on user ${Integer.signum(new ByLengthThenAlpha().thenComparing(ju.Comparator.reverseOrder[String]()).compare("ab", "ab"))}")

    section("user AbstractList")
    val sq = new Squares(6)
    println(s"$sq size=${sq.size} get(3)=${sq.get(3)} contains(16)=${sq.contains(16)} contains(15)=${sq.contains(15)}")
    println(s"indexOf(9)=${sq.indexOf(9)} lastIndexOf(25)=${sq.lastIndexOf(25)} indexOf(2)=${sq.indexOf(2)} isEmpty=${sq.isEmpty}")
    println(s"equals ${sq.equals(ju.List.of[Integer](0, 1, 4, 9, 16, 25))} ${ju.List.of[Integer](0, 1, 4, 9, 16, 25).equals(sq)} hashCode ${sq.hashCode} ${ju.List.of[Integer](0, 1, 4, 9, 16, 25).hashCode}")
    println(s"subList ${sq.subList(2, 5)} subList.get(0)=${sq.subList(2, 5).get(0)} toArray ${sq.toArray.mkString(",")}")
    val li = sq.listIterator(6)
    val rev = new StringBuilder
    while li.hasPrevious do rev.append(li.previous()).append(' ')
    println(s"listIterator backwards ${rev.toString.trim}")
    println(s"containsAll ${sq.containsAll(ju.List.of[Integer](1, 4))} copy ${new ju.ArrayList[Integer](sq)} asScala ${sq.asScala.map(_.intValue).sum}")
    val fe = new StringBuilder
    sq.forEach(x => fe.append(x).append('/'))
    println(s"forEach $fe")
    attempt("Squares add")(sq.add(36))
    attempt("Squares set")(sq.set(0, 1))
    attempt("Squares get(6)")(sq.get(6))
    val g = new Growable
    g.add("a"); g.add("c"); g.add(1, "b"); g.addAll(ju.List.of("d", "e"))
    println(s"Growable $g size=${g.size}")
    g.remove("c"); g.set(0, "A")
    println(s"after remove/set $g")
    g.removeIf(_ == "d")
    println(s"after removeIf $g")
    g.subList(0, 2).clear()
    println(s"after subList.clear $g")
    g.addAll(0, ju.List.of("x", "y", "z"))
    g.sort(ju.Comparator.reverseOrder[String]())
    println(s"after sort $g")
    val git = g.listIterator()
    git.next(); git.set("Z"); git.next(); git.remove(); git.add("new")
    println(s"after listIterator edits $g")
    attempt("Growable CME") {
      val i = g.iterator
      while i.hasNext do if i.next() == "Z" then g.add("late")
    }
    println(s"Growable equals ${g.equals(ju.List.of("Z", "new", "x", "e", "late"))} $g")

    section("user Iterator / AbstractSet / AbstractMap")
    val cd = new Countdown(4)
    val cdsb = new StringBuilder
    cd.forEachRemaining(i => cdsb.append(i).append(' '))
    println(s"Countdown ${cdsb.toString.trim}")
    attempt("Countdown default remove")(new Countdown(1).tap(_.next()).remove())
    println(s"Countdown asScala ${new Countdown(3).asScala.toList}")
    val ev = new Evens(10)
    println(s"Evens size=${ev.size} contains(4)=${ev.contains(4)} contains(5)=${ev.contains(5)} sorted=${new ju.TreeSet[Integer](ev)}")
    println(s"Evens equals ${ev.equals(ju.Set.of[Integer](2, 4, 6, 8, 10))} hashCode ${ev.hashCode} ${ju.Set.of[Integer](2, 4, 6, 8, 10).hashCode}")
    println(s"Evens toString $ev")
    val pw = new Powers(3, 5)
    println(s"$pw size=${pw.size} get=${pw.get("3^2")} missing=${pw.get("3^9")} containsKey=${pw.containsKey("3^4")} containsValue(27)=${pw.containsValue(27)} containsValue(28)=${pw.containsValue(28)}")
    println(s"keySet ${pw.keySet} values ${pw.values} isEmpty=${pw.isEmpty}")
    println(s"getOrDefault ${pw.getOrDefault("x", -1)} equals ${pw.equals(new ju.HashMap[String, Integer](pw))} hashCode ${pw.hashCode == new ju.HashMap[String, Integer](pw).hashCode}")
    val fm = new StringBuilder
    pw.forEach((k, v) => fm.append(s"$k=$v,"))
    println(s"forEach $fm")
    attempt("Powers put")(pw.put("x", 1))
    println(s"copied into TreeMap ${new ju.TreeMap[String, Integer](pw).descendingMap}")
    println(s"asScala ${pw.asScala.toList.sortBy(_._2)}")

  extension [A](a: A) def tap(f: A => Unit): A = { f(a); a }
