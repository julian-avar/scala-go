import scala.math.Ordering
import scala.math.Ordered.orderingToOrdered

/** StringOps, string interpolators and formatting, rich wrappers of primitives, Ordering and
  * Ordered, sorting, and the Numeric, Integral and Fractional type classes. */
object Main:
  def section(name: String): Unit = println(s"-- $name")

  def main(args: Array[String]): Unit =
    stringOps()
    interpolators()
    formatting()
    richPrimitives()
    orderings()
    sorting()
    numericTypeClasses()

  def stringOps(): Unit =
    section("StringOps")
    val s = "Hello, World"
    println(s"${s.reverse} ${s.toUpperCase} ${s.toLowerCase} ${s.length}")
    println(s"${s.take(5)} ${s.drop(7)} ${s.takeRight(3)} ${s.dropRight(7)} ${s.slice(2, 4)}")
    println(s"${s.head} ${s.last} ${s(4)} ${s.headOption} ${"".headOption} ${s.charAt(1)}")
    println(s"${s.filter(_.isUpper)} ${s.filterNot(_.isLetter)} ${s.count(_ == 'l')} ${s.exists(_.isDigit)}")
    println(s.map(c => if c.isUpper then c.toLower else c.toUpper))
    println(s.flatMap(c => s"$c$c").take(8))
    println(s"${s.indexOf('o')} ${s.lastIndexOf('o')} ${s.indexOf("World")} ${s.indexWhere(_.isWhitespace)}")
    println(s"${s.startsWith("Hell")} ${s.endsWith("ld")} ${s.contains("lo, W")} ${s.isEmpty} ${"".nonEmpty}")
    println(s.split(", ").toList)
    println("a1b2c3".split("\\d").toList)
    println("a,b;c".split(Array(',', ';')).toList)
    println("one two  three".split(' ').toList)
    println("  padded  ".trim + "|" + "x".padTo(4, '.') + "|")
    println("abc" * 3)
    println(s"${"scala".capitalize} ${"Scala".capitalize}")
    println("""|first
               |second""".stripMargin)
    println("#line1\n#line2".stripMargin('#'))
    println(s"${"file.txt".stripSuffix(".txt")} ${"http://x".stripPrefix("http://")}")
    println("a\nb\r\nc".linesIterator.toList)
    println("a\nb\n".linesWithSeparators.map(_.length).toList)
    println(s"${"42".toInt + 1} ${"-7".toLong} ${"3.5".toDouble * 2} ${"true".toBoolean} ${"1e3".toFloat}")
    println(s"${"12".toIntOption} ${"x".toIntOption} ${"9999999999".toIntOption} ${"1.5".toDoubleOption} ${"no".toBooleanOption}")
    println(s"${"ff".toByteOption} ${"127".toByteOption} ${"-32768".toShortOption} ${"5".toLongOption}")
    println(try "abc".toInt catch case e: NumberFormatException => e.getMessage)
    println("hello world".split(" ").map(_.capitalize).mkString(" "))
    println(s"${"banana".distinct} ${"banana".sorted} ${"banana".groupBy(identity).view.mapValues(_.length).toList.sorted}")
    println(s"${"abcdef".grouped(2).toList} ${"abcd".sliding(3).toList}")
    println("hello".zip("abc").map((a, b) => s"$a$b").mkString(","))
    println(s"${"a-b-c".replace("-", "+")} ${"aaa".replaceFirst("a", "b")} ${"x1y22".replaceAll("\\d+", "#")}")
    println(s"${"abc".compare("abd")} ${"b".compareTo("a")} ${"ABC".equalsIgnoreCase("abc")} ${"abc" < "abd"}")
    println(s"${"tab\tsep".split('\t').length} ${"ünïcödé".length} ${"日本語".reverse}")
    println("Hello".toCharArray.map(_.toInt).mkString(","))
    println(s"${"hello".collect { case c if c != 'l' => c.toUpper }} ${"hello".partition(_ < 'h')}")
    println(s"${"abc".foldLeft("")((acc, c) => c.toString + acc)} ${"xyz".zipWithIndex.map((c, i) => s"$i$c").mkString}")
    println(s"${"mississippi".span(_ != 's')} ${"mississippi".splitAt(4)} ${"a,b,,".split(",").length}")
    println(s"${"Hello".iterator.toList} ${"ab".toSeq} ${"hello".indexOfSlice("ll")} ${"hello".patch(0, "J", 1)}")
    println(s"${"  x ".strip} ${"   ".isBlank} ${"abc".chars.sum} ${"abc".codePointAt(0)} ${"a".repeat(2)}")
    println(s"${"Hello".updated(0, 'J')} ${"ab".appended('c')} ${"bc".prepended('a')} ${"abc".concat("d")}")
    println(s"${"%d items".format(3)} ${"%s=%s".format("k", "v")}")

  def interpolators(): Unit =
    section("Interpolators")
    val name = "Ada"
    val n = 3
    val pi = math.Pi
    println(s"$name has ${n * 2} items; $$5; ${if n > 2 then "many" else "few"}")
    println(raw"no\tescape\n $name")
    println(s"tab\there")
    println(f"$name%s is $n%d, pi is $pi%.3f, padded [$n%4d] [$name%-5s] [$n%03d]")
    println(f"${1.5}%.1f ${100L}%d ${'c'}%c ${true}%b ${0.000123}%.2e")
    case class Point(x: Int, y: Int)
    println(s"${Point(1, 2)} ${List(1, 2)} ${Some("x")} ${(1, "a")} ${null}")
    extension (sc: StringContext)
      def shout(args: Any*): String = sc.s(args*).toUpperCase + "!"
    println(shout"hello $name")
    println(StringContext("a", "b", "c").s(1, 2))
    println(StringContext.processEscapes("x\\ty"))
    val parsed = "key=value" match
      case s"$k=$v" => s"$k -> $v"
      case _        => "no match"
    println(parsed)
    println("2026-11-11" match { case s"$y-$m-$d" => List(y, m, d).map(_.toInt).sum; case _ => 0 })

  def formatting(): Unit =
    section("format")
    println("%d|%5d|%-5d|%05d|%+d|%d".format(42, 42, 42, 42, 42, -42))
    println("%s|%10s|%-10s|%.2s".format("abc", "abc", "abc", "abc"))
    println("%.2f|%8.3f|%-8.1f|%.0f|%f".format(3.14159, 2.71828, 1.05, 2.5, 1.0))
    println("%x|%X|%o|%08x|%#x".format(255, 255, 8, 48879, 255))
    println("%c|%b|%%|%e|%.3e".format('z', false, 12345.678, 0.000123456))
    println("%s %s %s".format(List(1, 2), Some(1), null))
    println("%d %d %d".format(Long.MaxValue, Int.MinValue, 7.toByte))
    println("%.3f %.1f %.2f".format(-0.0005, 0.05, 1e10))
    println("%10.4f|%-10d|".format(math.E, 7))
    println(String.format("%s-%s", "a", "b") + String.format("%n").length)
    println(f"${12}%x ${3.0f}%.2f ${BigDecimal("1.5")}%s")

  def richPrimitives(): Unit =
    section("Rich wrappers")
    println(s"${5.max(3)} ${5.min(3)} ${(-5).abs} ${(-5).sign} ${0.sign} ${7.compare(9)}")
    println(s"${10.toBinaryString} ${255.toHexString} ${8.toOctalString} ${(-1).toHexString} ${5L.toBinaryString}")
    println(s"${3.until(6)} ${3.to(9, 3)} ${(1 to 3).map(_.toChar.toInt)}")
    println(s"${2.5.round} ${2.5.floor} ${2.5.ceil} ${(-2.5).round} ${2.4.abs} ${(-0.5).sign}")
    println(s"${1.0.isWhole} ${1.5.isWhole} ${Double.NaN.isNaN} ${(1.0 / 0).isInfinite} ${1.0.isFinite}")
    println(s"${3.7.toInt} ${300.toByte} ${70000.toShort} ${65.toChar} ${'A'.toInt} ${1e10.toFloat}")
    println(s"${math.Pi.toDegrees} ${180.0.toRadians}")
    println(s"${'a'.isLetter} ${'1'.isDigit} ${' '.isWhitespace} ${'A'.isUpper} ${'a'.toUpper} ${'Z'.toLower}")
    println(s"${'7'.asDigit} ${'f'.asDigit} ${'a'.isLetterOrDigit} ${'_'.isLetterOrDigit} ${'é'.isLower}")
    println(s"${('a' to 'e').toList} ${'c' - 'a'} ${('a' + 2).toChar}")
    println(s"${5.isValidByte} ${500.isValidByte} ${1.0.isValidInt} ${1.5.isValidInt} ${'a'.isValidChar}")
    println(s"${Int.MaxValue} ${Int.MinValue} ${Long.MaxValue} ${Byte.MinValue} ${Short.MaxValue} ${Char.MaxValue.toInt}")
    println(s"${Double.MaxValue} ${Double.MinPositiveValue} ${Float.MaxValue} ${Float.MinPositiveValue}")
    println(s"${java.lang.Integer.MAX_VALUE == Int.MaxValue} ${1.byteValue} ${2.0.intValue} ${3.toLong.doubleValue}")

  case class Version(major: Int, minor: Int) extends Ordered[Version]:
    def compare(that: Version): Int =
      if major != that.major then major.compare(that.major) else minor.compare(that.minor)
    override def toString = s"$major.$minor"

  case class Person(name: String, age: Int)

  def orderings(): Unit =
    section("Ordering, Ordered")
    val vs = List(Version(1, 10), Version(1, 2), Version(0, 9))
    println(vs.sorted)
    println(s"${Version(1, 0) < Version(1, 1)} ${Version(2, 0) >= Version(1, 9)} ${vs.max} ${vs.min}")
    println(s"${Version(1, 0).compareTo(Version(1, 0))} ${List(Version(3, 0), Version(2, 0)).max}")
    val people = List(Person("Bo", 30), Person("Al", 30), Person("Cy", 25))
    println(people.sortBy(_.age))
    println(people.sortBy(p => (p.age, p.name)))
    println(people.sorted(using Ordering.by[Person, Int](_.age).reverse))
    println(people.sorted(using Ordering.by[Person, Int](_.age).orElseBy(_.name)))
    println(people.sorted(using Ordering.by[Person, String](_.name).orElse(Ordering.by(_.age))))
    println(s"${people.maxBy(_.name)} ${people.minBy(p => (p.age, p.name))}")
    val byLength = Ordering.by[String, Int](_.length)
    println(List("ccc", "a", "bb").sorted(using byLength))
    println(List("ccc", "a", "bb").sorted(using Ordering.String.reverse))
    println(List("b", "a").sorted(using Ordering.Tuple2(byLength, Ordering.String).on[String](s => (s, s))))
    println(s"${Ordering.Int.compare(1, 2)} ${Ordering[String].lt("a", "b")} ${Ordering.Int.max(3, 7)} ${Ordering.Int.equiv(2, 2)}")
    println(s"${Ordering.Double.TotalOrdering.compare(Double.NaN, 1.0)} ${List(2.0, Double.NaN, -1.0).sorted(using Ordering.Double.TotalOrdering)}")
    println(List(Some(2), None, Some(1)).sorted)
    println(List((2, "b"), (1, "z"), (2, "a")).sorted)
    println(List(List(1, 2), List(1), List(0, 5)).sorted(using Ordering.Implicits.seqOrdering[List, Int]))
    println(List(Left(3), Right("a"), Left(1)).sortBy(_.isRight))
    println(s"${Ordering.Boolean.compare(true, false)} ${Ordering.Char.compare('a', 'b')} ${Ordering.BigInt.compare(BigInt(5), BigInt(3))}")
    import Ordering.Implicits.*
    println(s"${(1, "b") < (1, "c")} ${List(1, 2) > List(1)} ${Version(1, 1) <= Version(1, 1)}")
    val revInt = Ordering.Int.reverse
    println(s"${revInt.isReverseOf(Ordering.Int)} ${List(1, 3, 2).sorted(using revInt)}")
    given Ordering[Person] = Ordering.by(p => (-p.age, p.name))
    println(people.sorted)
    println(people.max)

  def sorting(): Unit =
    section("Sorting")
    val words = List("banana", "Apple", "cherry", "apple", "Banana")
    println(words.sorted)
    println(words.sortWith(_.toLowerCase < _.toLowerCase))
    println(words.sortBy(_.toLowerCase))
    println(words.sortBy(w => (w.length, w)))
    println(words.sorted(using Ordering.comparatorToOrdering(String.CASE_INSENSITIVE_ORDER)))
    // Stable: equal keys keep their order.
    println(List(("x", 2), ("y", 1), ("z", 2), ("w", 1)).sortBy(_._2))
    val arr = Array(5, 2, 9, 1)
    scala.util.Sorting.quickSort(arr)
    println(arr.mkString(","))
    println(Array("b", "c", "a").sortInPlace().mkString)
    println(Vector(3.5, -1.0, 2.25).sorted)
    println((1 to 20).sortBy(i => (i % 5, -i)).take(6))
    println(List(1, 2, 3, 4, 5, 6).sorted(using Ordering.by[Int, Boolean](_ % 2 == 0)))
    println(Seq(BigDecimal("1.10"), BigDecimal("1.1"), BigDecimal("0.5")).sorted)
    println(Seq(BigInt("100000000000000000000"), BigInt(-1)).sorted)
    println(s"${List('b', 'A', 'a').sorted} ${List(true, false).sorted} ${List(3L, 1L).sorted}")

  def sum[T](xs: Seq[T])(using n: Numeric[T]): T = xs.foldLeft(n.zero)(n.plus)
  def mean[T](xs: Seq[T])(using f: Fractional[T]): T = f.div(sum(xs), f.fromInt(xs.size))
  def gcd[T](a: T, b: T)(using i: Integral[T]): T = if i.equiv(b, i.zero) then a else gcd(b, i.rem(a, b))
  def describe[T: Numeric](x: T): String =
    import Numeric.Implicits.*
    val n = summon[Numeric[T]]
    s"${x * x + n.one} ${-x} ${x.toInt} ${x.toDouble} ${n.abs(x)} ${n.sign(x)} ${n.signum(x)}"

  def numericTypeClasses(): Unit =
    section("Numeric, Integral, Fractional")
    println(s"${sum(List(1, 2, 3))} ${sum(List(1.5, 2.5))} ${sum(List(BigInt(10).pow(20), BigInt(1)))} ${sum(List(1L, 2L))}")
    println(s"${mean(List(1.0, 2.0, 4.0))} ${mean(List(1.0f, 2.0f))} ${mean(List(BigDecimal(1), BigDecimal(3)))}")
    println(s"${gcd(48, 18)} ${gcd(48L, 180L)} ${gcd(BigInt(2).pow(70), BigInt(6).pow(20))} ${gcd[Short](21, 14)}")
    println(describe(-3))
    println(describe(2.5))
    println(describe(BigDecimal("-1.5")))
    println(describe(BigInt(-7)))
    println(s"${Numeric[Int].parseString("12")} ${Numeric[Double].parseString("x")} ${Numeric[Long].fromInt(5)}")
    println(s"${List(1, 2, 3).product} ${List(BigDecimal("1.5"), BigDecimal(2)).product} ${List.empty[Double].sum}")
    import Integral.Implicits.*
    println(s"${17 /% 5} ${BigInt(17) /% BigInt(5)} ${-17 % 5}")
    val i = summon[Integral[Long]]
    println(s"${i.quot(-7L, 2L)} ${i.rem(-7L, 2L)} ${i.toInt(i.fromInt(9))} ${i.compare(1L, 2L)} ${i.max(1L, 2L)}")
    val f = summon[Fractional[Double]]
    println(s"${f.div(1.0, 8.0)} ${f.toLong(3.99)} ${f.negate(2.0)} ${f.times(1.5, 4.0)}")
    println(s"${List(3, 1, 2).max} ${List(1.5, -2.0).min} ${List("b", "a").max}")
