// Limitation (docs/design.md, "Status": not planned): BigInteger and BigDecimal are Go values and
// cannot be subclassed, so a subclass is a link error. On the JVM this prints 20.
class BigInteger10 extends java.math.BigInteger("10"):
  def twice: java.math.BigInteger = add(this)

object Main:
  def main(args: Array[String]): Unit =
    println(BigInteger10().twice)
