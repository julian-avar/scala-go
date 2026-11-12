import scala.collection.mutable.{ArrayBuffer as Buffer, ListBuffer as _, *}

// Lexical syntax, names and scopes (docs/_docs/spec/language.md, sections 1 and 2): literals,
// constant folding, identifiers, operators, shadowing, imports, and the JVM binary names of
// classes.
class TopLevel
object TopLevelObject

case class Vec(x: Int, y: Int):
  def +(o: Vec): Vec = Vec(x + o.x, y + o.y)
  def *(k: Int): Vec = Vec(x * k, y * k)
  def unary_- : Vec = Vec(-x, -y)
  def +:(k: Int): Vec = Vec(x + k, y) // ends in ':': right-associative, `this` on the right
  def ===(o: Vec): Boolean = x == o.x && y == o.y
  infix def dot(o: Vec): Int = x * o.x + y * o.y

object Main:
  class Nested
  object Inner:
    class Deeper
  inline val InlineMax = Int.MaxValue
  final val FinalMax = Int.MaxValue
  inline def addOne(inline n: Int): Int = n + 1

  def codes(s: String): String = s.map(c => Integer.toHexString(c)).mkString(" ")

  def main(args: Array[String]): Unit =
    // String literals: UTF-16 code units, unpaired surrogates included; equal literals are eq.
    val lone = "\uD800"
    println(s"lone surrogate: length ${lone.length}, code ${codes(lone)}")
    println(s"in text: ${codes("a\uDC00b")}")
    println(s"pair: length ${"😀".length}, code point ${Integer.toHexString("😀".codePointAt(0))}")
    println(s"escapes: ${codes("\t\n\r\b\f\\\"\'A")}")
    val raw = """a\nb "quoted" """
    println(s"triple-quoted: ${raw.length} [$raw]")
    println("""|margin
               |stripped""".stripMargin)
    println(raw"raw\t${1 + 1}")
    val hello1 = "hello"
    val hello2 = "hello"
    println(s"equal literals eq: ${hello1 eq hello2}")
    println(s"folded literal eq: ${("hel" + "lo") eq hello1}")
    println(s"non-ASCII: ${"héllo wörld ✓".length} ${codes("é✓")}")

    // Character literals: unsigned 16-bit code units.
    println(s"chars: ${'a'.toInt} ${'\u0000'.toInt} ${'￿'.toInt} ${('￿' + 1)} ${'\n'.toInt} ${'\''}")
    val maxChar = '￿'
    println(s"char arithmetic: ${(maxChar + 1).toChar.toInt} ${('a' + 1).toChar}")

    // Numeric literals.
    println(s"ints: ${0xFF} ${0xFFFFFFFF} ${1_000_000} ${0x7fff_ffff} ${-2147483648}")
    println(s"longs: ${0xFFFFFFFFL} ${0xFFFF_FFFF_FFFF_FFFFL} ${-9223372036854775808L} ${1_000_000_000_000L}")
    println(s"doubles: ${1e3} ${1.5e-3} ${.5} ${1d} ${2.0E+2} ${0.1 + 0.2} ${1e308 * 10}")
    println(s"floats: ${1.5f} ${1e-3f} ${3f} ${0.1f + 0.2f} ${Float.MinPositiveValue}")
    println(s"specials: ${Double.NaN} ${1.0 / 0} ${-1.0 / 0} ${-0.0} ${1 / -0.0} ${0.0 == -0.0}")
    println(s"byte/short: ${127.toByte} ${128.toByte} ${(-129).toByte} ${65535.toShort}")

    // Constant folding with the JVM's semantics.
    println(s"folded: ${300.0.toByte} ${(Int.MaxValue: Int) + 1} ${InlineMax + 1} ${FinalMax + 1} ${addOne(Int.MaxValue)}")
    println(s"folded conversions: ${1e10.toInt} ${(-1e10).toInt} ${Double.NaN.toInt} ${3.99.toInt} ${(-3.99).toLong} ${'A' + 1}")
    println(s"folded division: ${7 / -2} ${-7 % 3} ${Long.MinValue / -1} ${Int.MinValue / -1}")
    println(s"folded shifts: ${1 << 33} ${1L << 65} ${-8 >> 1} ${-8 >>> 28}")

    // Identifiers.
    val `my value` = 1
    val `type` = 2
    val αβγ = 3
    val x_+ = 4
    val _under = 5
    println(s"identifiers: ${`my value` + `type` + αβγ + x_+ + _under}")

    // Operators: precedence, associativity, prefix, infix.
    val a = Vec(1, 2)
    val b = Vec(3, 4)
    println(s"precedence: ${a + b * 2}")
    println(s"prefix: ${-a}")
    println(s"right-assoc: ${10 +: a}")
    println(s"infix: ${a dot b} ${a === Vec(1, 2)}")
    println(s"precedence of ints: ${1 + 2 * 3 - 4 / 2} ${1 << 2 + 1} ${true || false && false} ${5 & 3 | 8 ^ 1}")
    val list = 1 :: 2 :: Nil
    println(s"cons: ${0 :: list} ${list :+ 3} ${-1 +: list}")
    var acc = 10
    acc += 5; acc *= 2; acc -= 1; acc /= 3; acc %= 4; acc <<= 2; acc |= 1
    println(s"assignment operators: $acc")

    // Scopes: shadowing, nested blocks, imports with renaming and wildcards.
    val shadow = "outer"
    locally {
      val shadow = "inner"
      println(s"shadowed: $shadow")
    }
    println(s"restored: $shadow")
    val buf = Buffer(1, 2)
    buf += 3
    val set = LinkedHashSet(3, 1, 2)
    println(s"imports: $buf $set")
    def outer(n: Int): Int =
      def inner(m: Int): Int = n * m
      inner(n + 1)
    println(s"nested defs: ${outer(3)}")

    // Class names are the JVM binary names.
    class LocalC
    val anon = new Runnable { def run(): Unit = () }
    val anon2 = new Object { override def toString = "anon2" }
    println(s"names: ${getClass.getName} ${classOf[TopLevel].getName} ${TopLevelObject.getClass.getName}")
    println(s"nested: ${classOf[Nested].getName} ${Inner.getClass.getName} ${classOf[Inner.Deeper].getName}")
    println(s"local: ${classOf[LocalC].getName}")
    println(s"anonymous: ${anon.getClass.getName} ${anon2.getClass.getName}")
    println(s"simple names: ${classOf[TopLevel].getSimpleName} ${classOf[Nested].getSimpleName} ${classOf[Inner.Deeper].getSimpleName} [${anon.getClass.getSimpleName}] ${classOf[Array[Int]].getSimpleName} ${classOf[Array[String]].getSimpleName}")
    println(s"case class: ${Vec(0, 0).getClass.getName} ${Vec.getClass.getName}")
