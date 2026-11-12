// Known bugs: JDK natives that the linker inlines as Go expressions. Given constant operands, the
// expression is a Go constant expression, which Go evaluates at compile time and rejects (overflow,
// division by zero, a negative constant converted to an unsigned type) where the JVM wraps or
// throws at run time. When fixed, this case fails: move the calls to jdk-integer-long,
// jdk-character and jdk-double-float (which avoid constant operands today), and update the roadmap.
object Main:
  def main(args: Array[String]): Unit =
    // JVM: -2147483648 and -9223372036854775808 (wrapping)
    println(Integer.sum(Int.MaxValue, 1))
    println(java.lang.Long.sum(Long.MaxValue, 1L))
    // JVM: ArithmeticException: / by zero
    println(Integer.divideUnsigned(1, 0))
    println(java.lang.Long.remainderUnsigned(1L, 0L))
    // JVM: false
    println(Character.isISOControl(-1))
    // JVM: -Infinity and -0.0
    println(java.lang.Float.intBitsToFloat(0xff800000))
    println(java.lang.Double.longBitsToDouble(0x8000000000000000L))
