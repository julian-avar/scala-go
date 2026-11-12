import java.math.BigInteger

// Known bugs in javalib and the JDK natives, found by the jdk-* cases: each line prints what
// scala-go does today; the comment says what the JVM (JDK 25) prints. Fixing a bug makes this case
// fail: move the check to the jdk-* case it was taken from, and update the roadmap.
object Main:
  def attempt(what: String)(body: => Any): Unit =
    val result =
      try String.valueOf(body)
      catch case e: Throwable => s"throws ${e.getClass.getName}${Option(e.getMessage).fold("")(": " + _)}"
    println(s"$what: $result")

  def bytes(b: Array[Byte]): String = b.map(x => f"${x & 0xff}%02x").mkString("[", " ", "]")

  def main(args: Array[String]): Unit =
    bigIntegers()
    characters()
    numbers()
    maths()

  def bigIntegers(): Unit =
    // JVM: [80] and [80 00 00 00] (the shortest two's complement)
    attempt("BigInteger(-128).toByteArray")(bytes(BigInteger.valueOf(-128).toByteArray))
    attempt("BigInteger(-2^31).toByteArray")(bytes(BigInteger.valueOf(Int.MinValue).toByteArray))
    // JVM: throws java.lang.NumberFormatException: Radix out of range (both)
    attempt("new BigInteger(\"1\", 1)")(new BigInteger("1", 1))
    attempt("new BigInteger(\"1\", 37)")(new BigInteger("1", 37))
    // JVM: throws java.lang.NumberFormatException: signum-magnitude mismatch / Invalid signum value
    attempt("new BigInteger(0, [1])")(new BigInteger(0, Array[Byte](1)))
    attempt("new BigInteger(2, [1])")(new BigInteger(2, Array[Byte](1)))
    // JVM: Zero length BigInteger; Illegal embedded sign character; For input string: "12" under radix 2
    attempt("new BigInteger(\"\")")(new BigInteger(""))
    attempt("new BigInteger(\"--1\")")(new BigInteger("--1"))
    attempt("new BigInteger(\"12\", 2)")(new BigInteger("12", 2))

  def characters(): Unit =
    // JVM: 3 3 10 35 (non-ASCII digits: Arabic-Indic, fullwidth, and fullwidth letters)
    attempt("Character.digit of non-ASCII")(Seq(Character.digit(0x663, 10), Character.digit(0xff13, 10), Character.digit(0xff21, 16), Character.digit(0xff5a, 36)).mkString(" "))
    // JVM: -1 -1 (radix out of range)
    attempt("Character.digit('0', 1), ('5', 37)")(s"${Character.digit('0', 1)} ${Character.digit('5', 37)}")
    // JVM: true true true false (Other_Uppercase, Other_Lowercase: Roman numerals, ª, modifier letters)
    attempt("isUpperCase(U+2160), isLowerCase(U+2170, U+00AA, U+02B0)")(
      s"${Character.isUpperCase(0x2160)} ${Character.isLowerCase(0x2170)} ${Character.isLowerCase(0xaa)} ${Character.isLowerCase(0x2b0)}")
    // JVM: true true true (isAlphabetic includes letter numbers and Other_Alphabetic)
    attempt("isAlphabetic(U+2160, U+0345, U+24B6)")(s"${Character.isAlphabetic(0x2160)} ${Character.isAlphabetic(0x345)} ${Character.isAlphabetic(0x24b6)}")

  def numbers(): Unit =
    // JVM: 123 (Character.digit accepts any Unicode decimal digit)
    attempt("Integer.parseInt(\"١٢٣\")")(Integer.parseInt("١٢٣"))
    // JVM: For input string: "12" under radix 2; radix 1 less than Character.MIN_RADIX; Cannot parse null string
    attempt("Integer.parseInt(\"12\", 2)")(Integer.parseInt("12", 2))
    attempt("Integer.parseInt(\"1\", 1)")(Integer.parseInt("1", 1))
    attempt("Long.parseLong(null)")(java.lang.Long.parseLong(null))
    // JVM: 255 and 255 (an out-of-range radix means 10)
    attempt("Integer.toString(255, 1)")(Integer.toString(255, 1))
    attempt("Long.toString(255L, 37)")(java.lang.Long.toString(255L, 37))
    // JVM: 2.5, NaN, then NumberFormatException for "1_000", "infinity", "nan" and " 1"
    for s <- Seq("2.5F", "-NaN", "1_000", "infinity", "nan", " 1") do attempt(s"Double.parseDouble(\"$s\")")(java.lang.Double.parseDouble(s))
    // JVM: NumberFormatException: empty String; multiple points
    attempt("Double.parseDouble(\"\")")(java.lang.Double.parseDouble(""))
    attempt("Double.parseDouble(\"1.2.3\")")(java.lang.Double.parseDouble("1.2.3"))
    // JVM: 7ff8000000000000 (the canonical NaN)
    attempt("doubleToRawLongBits(NaN)")(java.lang.Long.toHexString(java.lang.Double.doubleToRawLongBits(Double.NaN)))
    // JVM: 1.0000001 (parseFloat rounds once, from the decimal; rounding through a double rounds twice)
    attempt("Float.parseFloat(\"1.00000017881393432617187499\")")(java.lang.Float.parseFloat("1.00000017881393432617187499"))

  def maths(): Unit =
    // JVM: NaN and NaN (a NaN exponent gives NaN; |base| = 1 with an infinite exponent gives NaN)
    attempt("Math.pow(1, NaN)")(Math.pow(1, Double.NaN))
    attempt("Math.pow(-1, Infinity)")(Math.pow(-1, Double.PositiveInfinity))
    // JVM: 1.0E308 and 2.7181459268249255 (Go's Pow is off by several ulps)
    attempt("Math.pow(10, 308)")(Math.pow(10, 308))
    attempt("Math.pow(1.0001, 10000)")(Math.pow(1.0001, 10000))
    // JVM: 0.7937005259840998 (StrictMath must give fdlibm's result)
    attempt("StrictMath.cbrt(0.5)")(StrictMath.cbrt(0.5))
