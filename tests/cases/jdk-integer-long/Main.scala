import java.lang.{Byte as JByte, Integer as JInteger, Long as JLong, Short as JShort}

// The integral boxes' statics: parsing (radixes, signs, overflow and the NumberFormatException
// messages), formatting in radixes and unsigned, bit twiddling, unsigned arithmetic, comparison,
// hashing and the boxed values' members.
object Main:
  def attempt(label: String)(body: => Any): Unit =
    val r =
      try String.valueOf(body)
      catch case e: Exception => s"${e.getClass.getName}: ${e.getMessage}"
    println(s"$label: $r")

  /** Only the exception's class: some messages differ (see known-bugs-jdk). */
  def attemptType(label: String)(body: => Any): Unit =
    val r =
      try String.valueOf(body)
      catch case e: Exception => e.getClass.getName
    println(s"$label: $r")

  /** The message too in radix 10; only the class in other radixes, whose messages differ. */
  def attemptRadix(label: String, radix: Int)(body: => Any): Unit =
    if radix == 10 then attempt(label)(body) else attemptType(label)(body)

  // Not constants: natives given constant operands can fold into Go constant expressions (known-bugs-jdk-go-errors).
  val zero: Int = Integer.parseInt("0")
  val one: Int = Integer.parseInt("1")

  val ints: Seq[Int] = Seq(0, 1, -1, 7, -8, 255, 0x12345678, Int.MaxValue, Int.MinValue, 0x80000001, 1 << 20)
  val longs: Seq[Long] = Seq(0L, 1L, -1L, 42L, -42L, 0x123456789abcdefL, Long.MaxValue, Long.MinValue, 1L << 40, 0xffffffffL)

  def main(args: Array[String]): Unit =
    parsing()
    formatting()
    bits()
    unsigned()
    comparing()
    smaller()

  def parsing(): Unit =
    println("== parseInt")
    for (s, radix) <- Seq("0" -> 10, "-0" -> 10, "+42" -> 10, "-2147483648" -> 10, "2147483647" -> 10, "ff" -> 16, "FF" -> 16,
        "-80000000" -> 16, "7fffffff" -> 16, "1010" -> 2, "z" -> 36, "Zz" -> 36, "777" -> 8, "007" -> 10)
    do attempt(s"parseInt($s, $radix)")(JInteger.parseInt(s, radix))
    for (s, radix) <- Seq("2147483648" -> 10, "-2147483649" -> 10, "80000000" -> 16, "12" -> 2, "" -> 10, "" -> 16, "+" -> 10,
        "-" -> 10, " 1" -> 10, "1 " -> 10, "1_000" -> 10, "0x10" -> 16, "1.0" -> 10, "1" -> 1, "1" -> 37, "99999999999" -> 10)
    do attemptRadix(s"parseInt(\"$s\", $radix)", radix)(JInteger.parseInt(s, radix))
    attemptType("parseInt(null)")(JInteger.parseInt(null))
    attempt("parseInt(\"12345\")")(JInteger.parseInt("12345"))
    attempt("valueOf(\"-17\")")(JInteger.valueOf("-17"))
    attempt("valueOf(\"x\")")(JInteger.valueOf("x"))
    attempt("\"12\".toInt")("12".toInt)
    attempt("\"12a\".toInt")("12a".toInt)
    println("== parseLong")
    for (s, radix) <- Seq("9223372036854775807" -> 10, "-9223372036854775808" -> 10, "+1" -> 10, "7fffffffffffffff" -> 16,
        "-8000000000000000" -> 16, "1111111111111111111111111111111111111111111111111111111111111111" -> 2, "zzzzzzzzzzzz" -> 36)
    do attemptRadix(s"parseLong($s, $radix)", radix)(JLong.parseLong(s, radix))
    for (s, radix) <- Seq("9223372036854775808" -> 10, "-9223372036854775809" -> 10, "8000000000000000" -> 16, "" -> 10,
        "12L" -> 10, "2" -> 2, "1" -> 40)
    do attemptRadix(s"parseLong(\"$s\", $radix)", radix)(JLong.parseLong(s, radix))
    attemptType("parseLong(null)")(JLong.parseLong(null))
    attempt("parseLong(\"-5\")")(JLong.parseLong("-5"))
    attempt("Long.valueOf(\"123456789012\")")(JLong.valueOf("123456789012"))
    attempt("\"99999999999\".toLong")("99999999999".toLong)

  def formatting(): Unit =
    println("== Integer formatting")
    for i <- ints do
      println(s"$i: ${JInteger.toString(i)} ${JInteger.toHexString(i)} ${JInteger.toOctalString(i)} ${JInteger.toBinaryString(i)} " +
        s"r2=${JInteger.toString(i, 2)} r7=${JInteger.toString(i, 7)} r16=${JInteger.toString(i, 16)} r36=${JInteger.toString(i, 36)} " +
        s"u=${JInteger.toUnsignedString(i)} ul=${JInteger.toUnsignedLong(i)}")
    println("== Long formatting")
    for l <- longs do
      println(s"$l: ${JLong.toString(l)} ${JLong.toHexString(l)} ${JLong.toOctalString(l)} ${JLong.toBinaryString(l)} " +
        s"r5=${JLong.toString(l, 5)} r16=${JLong.toString(l, 16)} r36=${JLong.toString(l, 36)} u=${JLong.toUnsignedString(l)}")
    println(s"RichInt ${255.toHexString} ${(-1).toBinaryString.length} ${8.toOctalString} ${(-1L).toHexString}")

  def bits(): Unit =
    println("== Integer bits")
    for i <- ints do
      println(s"$i: bitCount=${JInteger.bitCount(i)} nlz=${JInteger.numberOfLeadingZeros(i)} ntz=${JInteger.numberOfTrailingZeros(i)} " +
        s"highest=${JInteger.highestOneBit(i)} lowest=${JInteger.lowestOneBit(i)} reverse=${JInteger.reverse(i)} reverseBytes=${JInteger.reverseBytes(i)} " +
        s"rotl3=${JInteger.rotateLeft(i, 3)} rotr3=${JInteger.rotateRight(i, 3)} rotl33=${JInteger.rotateLeft(i, 33)} rotr-1=${JInteger.rotateRight(i, -1)} signum=${JInteger.signum(i)}")
    println("== Long bits")
    for l <- longs do
      println(s"$l: bitCount=${JLong.bitCount(l)} nlz=${JLong.numberOfLeadingZeros(l)} ntz=${JLong.numberOfTrailingZeros(l)} " +
        s"highest=${JLong.highestOneBit(l)} lowest=${JLong.lowestOneBit(l)} reverse=${JLong.reverse(l)} " +
        s"rotl5=${JLong.rotateLeft(l, 5)} rotr5=${JLong.rotateRight(l, 5)} rotl65=${JLong.rotateLeft(l, 65)} signum=${JLong.signum(l)}")

  def unsigned(): Unit =
    println("== unsigned")
    val pairs = Seq((-1, 2), (-2, -1), (7, 2), (0x80000000, 3), (5, -1), (-1, -1))
    for (a, b) <- pairs do
      println(s"$a,$b: div=${JInteger.divideUnsigned(a, b)} rem=${JInteger.remainderUnsigned(a, b)} cmp=${JInteger.compareUnsigned(a, b)} cmp=${JInteger.compare(a, b)}")
    val lpairs = Seq((-1L, 2L), (-2L, -1L), (Long.MinValue, 10L), (100L, 7L), (5L, -1L))
    for (a, b) <- lpairs do
      println(s"$a,$b: div=${JLong.divideUnsigned(a, b)} rem=${JLong.remainderUnsigned(a, b)} cmp=${JLong.compare(a, b)}")
    attempt("divideUnsigned(1, 0)")(JInteger.divideUnsigned(1, zero))
    attempt("remainderUnsigned(1L, 0L)")(JLong.remainderUnsigned(1L, zero.toLong))

  def comparing(): Unit =
    println("== comparing and boxes")
    println(s"${JInteger.max(3, -4)} ${JInteger.min(3, -4)} ${JInteger.sum(Int.MaxValue, one)} ${JLong.max(3L, -4L)} ${JLong.min(3L, -4L)} ${JLong.sum(Long.MaxValue, one.toLong)}")
    println(s"${JInteger.compare(Int.MinValue, Int.MaxValue)} ${JInteger.compare(5, 5)} ${JLong.compare(Long.MaxValue, Long.MinValue)}")
    println(s"hashCode ${JInteger.hashCode(-5)} ${JLong.hashCode(-5L)} ${JLong.hashCode(1L << 32)} ${JLong.hashCode(Long.MinValue)} ${JInteger.valueOf(77).hashCode} ${JLong.valueOf(1L << 33).hashCode}")
    val i: JInteger = JInteger.valueOf(1000)
    val l: JLong = JLong.valueOf(-3000000000L)
    println(s"${i.intValue} ${i.longValue} ${i.doubleValue} ${i.floatValue} ${i.shortValue} ${i.byteValue} ${i.compareTo(999)} ${i.equals(1000)} ${i.equals(1000L)} ${i.toString}")
    println(s"${l.intValue} ${l.longValue} ${l.doubleValue} ${l.floatValue} ${l.shortValue} ${l.byteValue} ${l.compareTo(0L)} ${l.equals(-3000000000L)} ${l.equals(-3000000000.0)}")
    println(s"${JInteger.MAX_VALUE} ${JInteger.MIN_VALUE} ${JInteger.SIZE} ${JInteger.BYTES} ${JLong.MAX_VALUE} ${JLong.MIN_VALUE} ${JLong.SIZE} ${JLong.BYTES}")
    val boxed = java.util.Arrays.asList[JInteger](5, -1, 3, Int.MinValue)
    java.util.Collections.sort(boxed)
    println(s"$boxed ${java.util.Collections.max(java.util.Arrays.asList[JLong](1L, 9L, -9L))}")
    val n: Number = i
    println(s"Number ${n.intValue} ${n.doubleValue} ${(l: Number).longValue}")

  def smaller(): Unit =
    println("== Short and Byte")
    println(s"${JShort.MAX_VALUE} ${JShort.MIN_VALUE} ${JShort.SIZE} ${JByte.MAX_VALUE} ${JByte.MIN_VALUE} ${JByte.BYTES}")
    println(s"compare ${JShort.compare(1, -1)} ${JShort.compare(Short.MinValue, Short.MaxValue)} ${JByte.compare(Byte.MaxValue, Byte.MinValue)} ${JByte.compare(-1, 1)}")
    val s: JShort = JShort.valueOf((-300).toShort)
    val b: JByte = JByte.valueOf((-7).toByte)
    println(s"${s.intValue} ${s.byteValue} ${s.doubleValue} ${s.compareTo(JShort.valueOf(1.toShort))} ${s.hashCode} ${JShort.hashCode((-1).toShort)} ${JShort.toString((-5).toShort)}")
    println(s"${b.intValue} ${b.shortValue} ${b.longValue} ${b.compareTo(JByte.valueOf(7.toByte))} ${b.hashCode} ${JByte.hashCode((-1).toByte)} ${JByte.toString((-128).toByte)}")
    println(s"${s.equals((-300).toShort)} ${s.equals(-300)} ${b.equals((-7).toByte)} ${b.equals(-7)}")
