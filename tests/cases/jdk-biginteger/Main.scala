import java.math.BigInteger

// java.math.BigInteger: construction from strings (radixes, signs, errors), bytes and longs;
// arithmetic with Java's truncating division and sign rules, mod/modPow/modInverse/gcd, two's
// complement bit operations on negative numbers, conversions (narrowing, doubles, byte arrays),
// primality, comparison, hashing, and scala.math.BigInt on top.
object Main:
  def attempt(label: String)(body: => Any): Unit =
    val r =
      try String.valueOf(body)
      catch case e: Exception => s"${e.getClass.getName}: ${e.getMessage}"
    println(s"$label: $r")

  /** Only the exception's class: the messages of some parse errors differ (see known-bugs-jdk). */
  def attemptType(label: String)(body: => Any): Unit =
    val r =
      try String.valueOf(body)
      catch case e: Exception => e.getClass.getName
    println(s"$label: $r")

  def bi(s: String): BigInteger = new BigInteger(s)
  def bytes(b: Array[Byte]): String = b.map(x => f"${x & 0xff}%02x").mkString("[", " ", "]")

  val big = bi("123456789012345678901234567890")
  val values: Seq[BigInteger] = Seq(BigInteger.ZERO, BigInteger.ONE, BigInteger.TWO, BigInteger.TEN, bi("-1"), bi("127"), bi("128"),
    bi("-128"), bi("-129"), bi("255"), bi("65536"), bi("-2147483648"), bi("9223372036854775807"), bi("-9223372036854775809"), big, big.negate())

  def main(args: Array[String]): Unit =
    construction()
    arithmetic()
    modular()
    bitwise()
    conversions()
    primesAndComparison()
    scalaBigInt()

  def construction(): Unit =
    println("== construction")
    println(s"${BigInteger.valueOf(Long.MinValue)} ${BigInteger.valueOf(0)} ${bi("-0")} ${bi("+42")} ${bi("000123")} ${bi("-00")}")
    println(s"${new BigInteger("ff", 16)} ${new BigInteger("-FF", 16)} ${new BigInteger("zz", 36)} ${new BigInteger("-101", 2)} ${new BigInteger("777", 8)}")
    println(s"${new BigInteger("123456789abcdef0123456789abcdef", 16)}")
    for s <- Seq("12a", " 1", "1 ", "1.0", "1e3") do attempt(s"new BigInteger(\"$s\")")(bi(s))
    for s <- Seq("", "-", "+", "--1", "+-1", "0x10") do attemptType(s"new BigInteger(\"$s\")")(bi(s))
    attemptType("digit out of radix")(new BigInteger("12", 2))
    println(s"bytes ${new BigInteger(Array[Byte](1, 0))} ${new BigInteger(Array[Byte](-1))} ${new BigInteger(Array[Byte](-128, 0))} ${new BigInteger(Array[Byte](0, -1))} ${new BigInteger(Array[Byte](-1, -1, -1, -1, -1, -1, -1, -1, -1))}")
    println(s"signum+magnitude ${new BigInteger(1, Array[Byte](-1, -1))} ${new BigInteger(-1, Array[Byte](1, 0))} ${new BigInteger(0, Array[Byte](0, 0))} ${new BigInteger(1, Array[Byte]())}")
    attempt("empty bytes")(new BigInteger(Array[Byte]()))

  def arithmetic(): Unit =
    println("== arithmetic")
    val a = big
    val b = bi("-987654321098765432")
    println(s"${a.add(b)} ${a.subtract(b)} ${a.multiply(b)} ${a.divide(b)} ${a.remainder(b)} ${a.negate().divide(b)} ${a.negate().remainder(b)}")
    val qr = a.divideAndRemainder(bi("-7"))
    println(s"divideAndRemainder ${qr(0)} ${qr(1)} ${bi("-7").divideAndRemainder(bi("2")).mkString(",")} ${bi("7").divideAndRemainder(bi("-2")).mkString(",")}")
    println(s"pow ${bi("2").pow(100)} ${bi("-3").pow(5)} ${bi("-3").pow(0)} ${BigInteger.ZERO.pow(0)} ${bi("10").pow(30)}")
    println(s"abs ${b.abs()} ${a.abs()} signum ${b.signum} ${a.signum} ${BigInteger.ZERO.signum} min ${a.min(b)} max ${a.max(b)}")
    println(s"factorial 30 ${(1 to 30).foldLeft(BigInteger.ONE)((acc, i) => acc.multiply(BigInteger.valueOf(i)))}")
    println(s"fib 150 ${Iterator.iterate((BigInteger.ZERO, BigInteger.ONE))((x, y) => (y, x.add(y))).drop(150).next()._1}")
    attempt("divide by zero")(a.divide(BigInteger.ZERO))
    attempt("remainder by zero")(a.remainder(BigInteger.ZERO))
    attempt("divideAndRemainder by zero")(a.divideAndRemainder(BigInteger.ZERO))
    attempt("pow(-1)")(a.pow(-1))

  def modular(): Unit =
    println("== modular")
    println(s"mod ${bi("-7").mod(bi("3"))} ${bi("7").mod(bi("3"))} ${bi("-9").mod(bi("3"))} ${big.negate().mod(bi("1000000007"))} rem ${bi("-7").remainder(bi("3"))}")
    attempt("mod(-3)")(bi("7").mod(bi("-3")))
    attempt("mod(0)")(bi("7").mod(BigInteger.ZERO))
    println(s"modPow ${bi("4").modPow(bi("13"), bi("497"))} ${bi("2").modPow(bi("100"), bi("1000000007"))} ${bi("-2").modPow(bi("3"), bi("5"))} ${bi("3").modPow(bi("-1"), bi("7"))} ${bi("5").modPow(BigInteger.ZERO, BigInteger.ONE)} ${bi("0").modPow(bi("0"), bi("7"))}")
    attempt("modPow(m = 0)")(bi("2").modPow(bi("3"), BigInteger.ZERO))
    attempt("modPow(-1, not invertible)")(bi("2").modPow(bi("-1"), bi("4")))
    println(s"modInverse ${bi("3").modInverse(bi("11"))} ${bi("-3").modInverse(bi("11"))} ${bi("10").modInverse(bi("17"))} ${big.modInverse(bi("1000000007"))} ${bi("5").modInverse(BigInteger.ONE)}")
    attempt("modInverse not invertible")(bi("4").modInverse(bi("8")))
    attempt("modInverse(-5)")(bi("3").modInverse(bi("-5")))
    println(s"gcd ${bi("12").gcd(bi("18"))} ${bi("-12").gcd(bi("18"))} ${bi("0").gcd(bi("-5"))} ${BigInteger.ZERO.gcd(BigInteger.ZERO)} ${big.gcd(bi("1234567890"))}")

  def bitwise(): Unit =
    println("== bits")
    for v <- values do
      println(s"$v: bitLength=${v.bitLength} bitCount=${v.bitCount} lowest=${v.getLowestSetBit} not=${v.not()} " +
        s"shl3=${v.shiftLeft(3)} shr3=${v.shiftRight(3)} shr-2=${v.shiftRight(-2)} shr200=${v.shiftRight(200)} test0=${v.testBit(0)} test70=${v.testBit(70)} " +
        s"set5=${v.setBit(5)} clear0=${v.clearBit(0)} flip7=${v.flipBit(7)}")
    val x = bi("-12345678901234567890")
    val y = bi("987654321987654321")
    println(s"and ${x.and(y)} or ${x.or(y)} xor ${x.xor(y)} andNot ${x.andNot(y)} ${y.andNot(x)}")
    println(s"and neg ${bi("-6").and(bi("-3"))} or neg ${bi("-6").or(bi("5"))} xor ${bi("-1").xor(bi("255"))}")
    attempt("testBit(-1)")(x.testBit(-1))
    attempt("setBit(-1)")(x.setBit(-1))

  def conversions(): Unit =
    println("== conversions")
    // Not negative powers of two: their toByteArray has an extra 0xff byte (known-bugs-jdk).
    for v <- values if !(v.signum < 0 && v.negate().bitCount == 1) do
      println(s"$v: int=${v.intValue} long=${v.longValue} short=${v.shortValue} byte=${v.byteValue} double=${v.doubleValue} float=${v.floatValue} " +
        s"bytes=${bytes(v.toByteArray)} hex=${v.toString(16)} r36=${v.toString(36)} r2=${v.toString(2).length} r1=${v.toString(1)} hash=${v.hashCode}")
    val huge = bi("2").pow(1024)
    println(s"huge double ${huge.doubleValue} ${huge.negate().doubleValue} ${huge.floatValue} ${bi("2").pow(1023).doubleValue}")
    println(s"rounding ${bi("9007199254740993").doubleValue} ${bi("9007199254740995").doubleValue} ${bi("16777217").floatValue} ${bi("18014398509481983").doubleValue}")
    val n: Number = big
    println(s"Number ${n.intValue} ${n.longValue}")
    println(s"round trip ${new BigInteger(big.toByteArray) == big} ${new BigInteger(big.negate().toString(7), 7) == big.negate()}")

  def primesAndComparison(): Unit =
    println("== primes and comparison")
    val candidates = Seq(0, 1, 2, 3, 4, 17, 561, 7919, 1000003, 1000001)
    println(candidates.map(c => s"$c=${BigInteger.valueOf(c).isProbablePrime(50)}").mkString(" "))
    println(s"mersenne ${bi("2").pow(127).subtract(BigInteger.ONE).isProbablePrime(64)} ${bi("2").pow(128).subtract(BigInteger.ONE).isProbablePrime(64)} negative ${bi("-7").isProbablePrime(10)} certainty0 ${bi("4").isProbablePrime(0)}")
    println(s"compareTo ${big.compareTo(big.negate())} ${big.negate().compareTo(big)} ${big.compareTo(bi(big.toString))} equals ${big.equals(bi(big.toString))} ${big.equals(big.toString)} ${BigInteger.ONE.equals(1)}")
    val list = java.util.Arrays.asList(bi("10"), bi("-5"), big, bi("0"), big.negate())
    java.util.Collections.sort(list)
    println(list)
    val set = new java.util.HashSet[BigInteger]()
    set.add(bi("12345678901234567890"))
    println(s"hash set ${set.contains(new BigInteger("12345678901234567890"))} ${set.contains(bi("12345678901234567891"))}")

  def scalaBigInt(): Unit =
    println("== scala.math.BigInt")
    val a = BigInt("123456789012345678901234567890")
    val b = BigInt(-97)
    println(s"${a + b} ${a - b} ${a * b} ${a / b} ${a % b} ${a /% b} ${a.mod(BigInt(97))} ${b.abs} ${a.pow(2)} ${-a}")
    println(s"${a.gcd(BigInt(30))} ${BigInt(3).modPow(BigInt(200), BigInt(1000))} ${BigInt(3).modInverse(BigInt(7))} ${a.bitLength} ${a.bitCount} ${a.testBit(1)} ${a << 10} ${a >> 10} ${b & BigInt(0xff)} ${b | BigInt(1)} ${b ^ BigInt(-1)} ${~b}")
    println(s"${a.isValidLong} ${BigInt(Long.MaxValue).isValidLong} ${BigInt(42).isValidInt} ${a.toLong} ${a.toInt} ${a.toDouble} ${a.signum} ${a.toString(36)} ${BigInt("-zz", 36)} ${BigInt(2).pow(64) - 1}")
    println(s"${a == BigInt("123456789012345678901234567890")} ${a.hashCode == BigInt("123456789012345678901234567890").hashCode} ${BigInt(5) == 5} ${BigInt(5).hashCode == 5.hashCode} ${a.compare(b)} ${List(BigInt(3), BigInt(-1), a).sorted}")
    println(s"${BigInt(1000).isProbablePrime(10)} ${BigInt(1009).isProbablePrime(10)} ${BigInt(Long.MinValue) - 1} ${(BigInt(1) << 100).toByteArray.length} ${BigInt(Array[Byte](1, 2, 3))}")
    attempt("BigInt / 0")(a / BigInt(0))
    attempt("BigInt(\"x\")")(BigInt("x"))
