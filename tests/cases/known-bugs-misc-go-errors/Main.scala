import scalago.go.*

// Known bugs (docs/roadmap.md, M0): code the linker accepts but Go rejects. Each should be a link
// error, or work. When one is fixed, this case fails: remove it from here (with a link-errors
// case or a running case in its place), and from the roadmap and the specification.

// BigInteger and BigDecimal are Go values and cannot be subclassed.
class BigInteger10 extends java.math.BigInteger("10"):
  def twice: java.math.BigInteger = add(this)

object Main:
  def main(args: Array[String]): Unit =
    println(BigInteger10().twice)
