import scala.collection.mutable

// Equality and hashing (docs/_docs/spec/language.md, "Equality and hashing"): ==, equals, eq,
// ##, hashCode of boxes, strings, case classes, tuples and collections; cooperative equality of
// boxed numbers. Not here: the documented deviations (boxed Int/Long == boxed Float, boxed
// primitive == BigInt, null String operands, identity of boxes), pinned in known-bugs-misc and
// limits-runtime.
case class P(x: Int, y: String)
case class D(v: Double)
case class Q(x: Int, y: String)
final case class Custom(id: Int, ignored: String):
  override def equals(o: Any): Boolean = o match
    case Custom(i, _) => i == id
    case _            => false
  override def hashCode: Int = id
class Plain(val n: Int)
case object Obj

object Main:
  def p(what: String)(body: => Any): Unit = println(s"$what: ${String.valueOf(body)}")
  def any(x: Any): Any = x
  def ref(x: AnyRef): AnyRef = x

  def main(args: Array[String]): Unit =
    // Cooperative equality of boxed numbers and characters
    p("1 == 1L boxed")(any(1) == any(1L))
    p("1 == 1.0 boxed")(any(1) == any(1.0))
    p("1L == 1.0 boxed")(any(1L) == any(1.0))
    p("'a' == 97 boxed")(any('a') == any(97))
    p("97L == 'a' boxed")(any(97L) == any('a'))
    p("(1: Byte) == (1: Short) boxed")(any(1.toByte) == any(1.toShort))
    p("(-1: Byte) == 255 boxed")(any((-1).toByte) == any(255))
    p("1.5f == 1.5 boxed")(any(1.5f) == any(1.5))
    p("0.1f == 0.1 boxed")(any(0.1f) == any(0.1))
    p("1.0f == 1.0 boxed")(any(1.0f) == any(1.0))
    p("2^53+1 L == 2^53 D boxed")(any(9007199254740993L) == any(9007199254740992.0))
    p("Long.MaxValue == Int.MaxValue boxed")(any(Long.MaxValue) == any(Int.MaxValue))
    p("-0.0 == 0.0 boxed")(any(-0.0) == any(0.0))
    p("NaN == NaN boxed, distinct")(any(Double.NaN) == any(0.0 / args.length))
    p("true == true boxed")(any(true) == any(true))
    p("true == 1 boxed")(any(true) == any(1))
    p("() == () boxed")(any(()) == any(()))
    p("1 == \"1\"")(any(1) == any("1"))
    p("1 != 2L boxed")(any(1) != any(2L))
    p("Integer == Long via ==")(Integer.valueOf(5) == java.lang.Long.valueOf(5))
    p("BigInt(1) == 1, BigInt(1) == 1L, BigDecimal(\"1.5\") == BigDecimal(\"1.50\")")(s"${BigInt(1) == 1} ${BigInt(1) == 1L} ${BigDecimal("1.5") == BigDecimal("1.50")}")
    p("BigInt(2).pow(70) == 2^70 as Double")(BigInt(2).pow(70) == math.pow(2, 70))

    // equals: type and value
    p("1 equals 1L")(any(1).equals(any(1L)))
    p("1 equals 1")(any(1).equals(any(1)))
    p("'a' equals 97")(any('a').equals(any(97)))
    p("1.0 equals 1.0f")(any(1.0).equals(any(1.0f)))
    p("NaN equals NaN")(any(Double.NaN).equals(any(Double.NaN)))
    p("-0.0 equals 0.0")(any(-0.0).equals(any(0.0)))
    p("Integer.valueOf(3).equals(3)")(Integer.valueOf(3).equals(3))
    p("Long.valueOf(3).equals(3)")(java.lang.Long.valueOf(3).equals(3))
    p("Short equals Byte")(java.lang.Short.valueOf(1.toShort).equals(java.lang.Byte.valueOf(1.toByte)))
    p("\"a\" equals \"a\"")("a".equals("a" + ""))

    // ##: consistent with ==
    p("1.## 1L.## 1.0.## 1.0f.## 'a'.##")(s"${1.##} ${1L.##} ${1.0.##} ${1.0f.##} ${'a'.##} ${97.##}")
    p("boxed ##")(s"${any(1).##} ${any(1L).##} ${any(1.0).##} ${any(1.0f).##} ${any(1.toByte).##} ${any(1.toShort).##}")
    p("1.5.##, 1.5.hashCode")(s"${any(1.5).##} ${any(1.5).hashCode}")
    p("Long beyond Int ##")(s"${any(1L << 40).##} ${any((1L << 40).toDouble).##} ${any(Long.MaxValue).##} ${any(-1L).##}")
    p("NaN.##, -0.0.##, 0.0.##")(s"${any(Double.NaN).##} ${any(-0.0).##} ${any(0.0).##}")
    p("Float NaN.##, 0.1f.##")(s"${any(Float.NaN).##} ${any(0.1f).##}")
    p("Double ## of large whole")(s"${any(1e10).##} ${any(10000000000L).##} ${any(1e30).##}")
    p("null.##")((null: Any).##)
    p("true.##, false.##")(s"${any(true).##} ${any(false).##}")
    p("\"abc\".## == hashCode")("abc".## == "abc".hashCode)

    // hashCode of boxes and strings (Java's)
    p("Integer hashCode")(s"${Integer.valueOf(-7).hashCode} ${Integer.valueOf(Int.MinValue).hashCode}")
    p("Long hashCode")(s"${java.lang.Long.valueOf(-1L).hashCode} ${java.lang.Long.valueOf(1L << 32).hashCode} ${java.lang.Long.valueOf(Long.MinValue).hashCode}")
    p("Double hashCode")(s"${java.lang.Double.valueOf(1.0).hashCode} ${java.lang.Double.valueOf(-0.0).hashCode} ${java.lang.Double.valueOf(Double.NaN).hashCode}")
    p("Float hashCode")(s"${java.lang.Float.valueOf(1.0f).hashCode} ${java.lang.Float.valueOf(Float.NaN).hashCode}")
    p("Boolean hashCode")(s"${java.lang.Boolean.TRUE.hashCode} ${java.lang.Boolean.FALSE.hashCode}")
    p("Char, Byte, Short hashCode")(s"${Character.valueOf('a').hashCode} ${java.lang.Byte.valueOf((-1).toByte).hashCode} ${java.lang.Short.valueOf((-1).toShort).hashCode}")
    p("String hashCode")(s"${"".hashCode} ${"a".hashCode} ${"hello world".hashCode} ${"\u00e9\u20ac".hashCode} ${"\uD83D\uDE00".hashCode}")
    p("long String hashCode (overflow)")(("abcdefghij" * 10).hashCode)
    p("BigInteger, BigDecimal hashCode")(s"${java.math.BigInteger.valueOf(-5).hashCode} ${new java.math.BigInteger("123456789012345678901234567890").hashCode} ${new java.math.BigDecimal("1.50").hashCode}")
    p("BigDecimal equals by scale")(s"${new java.math.BigDecimal("1.5").equals(new java.math.BigDecimal("1.50"))} ${BigDecimal("1.5") == BigDecimal("1.50")}")

    // Case classes, tuples, options
    p("case class ==")(s"${P(1, "a") == P(1, "a")} ${P(1, "a") == P(1, "b")} ${P(1, "a") == Q(1, "a")}")
    p("case class hashCode equal")(P(1, "a").hashCode == P(1, "a").hashCode)
    p("case class hashCode")(s"${P(1, "a").hashCode} ${Q(1, "a").hashCode} ${D(1.0).hashCode} ${D(-0.0).hashCode} ${Obj.hashCode}")
    p("case class with NaN")(s"${D(Double.NaN) == D(Double.NaN)} ${D(0.0) == D(-0.0)}")
    p("case class with null field")(s"${P(1, null) == P(1, null)} ${P(1, null).hashCode == P(1, null).hashCode}")
    p("custom equals/hashCode")(s"${Custom(1, "x") == Custom(1, "y")} ${Custom(1, "x").## } ${Set(Custom(1, "a"), Custom(1, "b")).size}")
    p("tuples ==")(s"${(1, "a") == (1, "a")} ${(1, 2L) == (1L, 2)} ${(1, 2.0) == (1.0, 2)} ${any((1, 2)) == any((1, 2, 3))}")
    p("tuple hashCode")(s"${(1, "a").hashCode} ${(1, 2, 3).hashCode} ${Tuple1(1).hashCode} ${(1L, 1.0).## == (1, 1).##}")
    p("Option ==")(s"${Some(1) == Some(1L)} ${Some(1) == Some(2)} ${None == None} ${Some(null) == Some(null)} ${Option(null) == None}")
    p("Option hashCode")(s"${Some(1).hashCode} ${None.hashCode}")
    p("Either, Try")(s"${Left(1) == Left(1)} ${Right("a") == Right("a")} ${Left(1) == Right(1)}")
    p("case object")(s"${Obj == Obj} ${Obj eq Obj} ${Obj.equals(Obj)}")

    // Collections
    p("List == Vector")(List(1, 2) == Vector(1, 2))
    p("List == ArrayBuffer")(List(1, 2) == mutable.ArrayBuffer(1, 2))
    p("List(1, 2) == List(1L, 2L)")(List(1, 2) == List(1L, 2L))
    p("Seq vs Set")(any(List(1)) == any(Set(1)))
    p("Set order")(Set(1, 2, 3) == Set(3, 2, 1))
    p("Set mixed numbers")(Set(1, 2) == Set(2L, 1.0))
    p("Map ==")(Map(1 -> "a", 2 -> "b") == Map(2 -> "b", 1 -> "a"))
    p("mutable Map == immutable Map")(mutable.Map(1 -> 1) == Map(1 -> 1))
    p("Range == List")((1 to 3) == List(1, 2, 3))
    p("empty collections")(s"${List() == Vector()} ${Set() == Set()} ${any(List()) == any(Set())} ${Nil == List()}")
    p("Seq hashCodes")(s"${List(1, 2, 3).hashCode} ${Vector(1, 2, 3).hashCode} ${(1 to 3).hashCode} ${Nil.hashCode}")
    p("Set and Map hashCodes")(s"${Set(1, 2, 3).hashCode} ${Set(3, 2, 1).hashCode} ${Map(1 -> 2).hashCode} ${Set.empty[Int].hashCode}")
    p("String-element hashCodes")(s"${List("a", "b").hashCode} ${Set("x").hashCode}")
    p("HashSet(1, 1L, 1.0, 1.0f)")(Set[Any](1, 1L, 1.0, 1.0f).size)
    p("HashMap key 1 found by 1L and 1.0")({ val m = Map[Any, String](1 -> "one"); s"${m.get(1L)} ${m.get(1.0)} ${m.get('a')}" })
    p("mutable.HashMap Long key by Int")({ val m = mutable.HashMap[Any, Int](); m(5L) = 1; m.get(5) })
    p("java.util.HashMap Integer vs Long")({ val m = new java.util.HashMap[Any, Int](); m.put(1, 1); m.put(1L, 2); s"${m.size} ${m.get(1)} ${m.get(1L)}" })
    p("java.util.List equals")(java.util.List.of(1, 2).equals(java.util.Arrays.asList(1, 2)))
    p("java.util.List hashCode")(java.util.List.of("a", "b").hashCode)

    // Arrays: == is identity
    val a1 = Array(1, 2, 3)
    val a2 = Array(1, 2, 3)
    p("array == other array")(a1 == a2)
    p("array == itself")(a1 == a1)
    p("array sameElements")(a1.sameElements(a2))
    p("Arrays.equals")(java.util.Arrays.equals(a1, a2))
    p("Arrays.hashCode")(java.util.Arrays.hashCode(a1))
    p("Arrays.deepEquals")(java.util.Arrays.deepEquals(Array[AnyRef](Array(1), "x"), Array[AnyRef](Array(1), "x")))
    p("arrays wrapped")(a1.toSeq == a2.toSeq)

    // null-safe ==, eq, ne
    val nullP: P = null
    val nullAny: Any = null
    p("null == null")(nullP == null)
    p("P == null, null == P")(s"${P(1, "a") == nullP} ${nullP == P(1, "a")} ${nullAny == 1} ${any(1) == nullAny}")
    p("null eq null")(nullP eq null)
    p("null equals via Objects")(java.util.Objects.equals(null, null))
    val pl = Plain(1)
    p("plain objects")(s"${pl == pl} ${pl == Plain(1)} ${pl eq pl} ${pl ne Plain(1)} ${pl.equals(pl)}")
    p("plain hashCode stable")(pl.hashCode == pl.hashCode && pl.## == pl.hashCode)
    p("case class eq")(s"${P(1, "a") eq P(1, "a")} ${P(1, "a") ne P(1, "a")}")
    val s1 = "hello"
    val s2 = "hel" + "lo"
    val s3 = new String("hello")
    val s4 = (new StringBuilder).append("hel").append("lo").toString
    p("string literals eq")(s1 eq "hello")
    p("constant-folded concat eq")(s1 eq s2)
    p("new String eq")(s1 eq s3)
    p("built string eq, ==")(s"${s1 eq s4} ${s1 == s4}")
    p("ref == on strings")(ref(s1) == ref(s3))
    val lst = List(1)
    p("same list eq")(lst eq lst)
    p("Nil eq Nil, None eq None")(s"${Nil eq List()} ${None eq Option.empty}")
    p("Objects.hash")(java.util.Objects.hash("a", 1, null))
    p("Objects.hashCode(null)")(java.util.Objects.hashCode(null))
    p("MurmurHash3 stringHash")(scala.util.hashing.MurmurHash3.stringHash("abc"))
    p("MurmurHash3 seqHash")(scala.util.hashing.MurmurHash3.seqHash(List(1, 2, 3)))
    p("Statics.anyHash")(s"${scala.runtime.Statics.anyHash(1L)} ${scala.runtime.Statics.anyHash(2.5)} ${scala.runtime.Statics.anyHash("s")}")
