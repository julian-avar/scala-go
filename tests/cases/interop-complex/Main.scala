import scalago.go.*

/** GoComplex: Go's complex128, a pair of Doubles on the JVM (docs/_docs/interop/go-data.md). */
object Main:
  def main(args: Array[String]): Unit =
    val z = GoComplex(1, 2)
    val w = GoComplex(0.5, -1.5)
    println(s"$z $w ${GoComplex(-3, 0)} ${GoComplex(0, 0)}")
    println(s"${z + w} ${z - w} ${z * z} ${z / GoComplex(0, 1)}")
    println(s"${z.real} ${z.imag} ${(z * w).real} ${(z * w).imag}")
    println(s"${z == GoComplex(1, 2)} ${z == w} ${z * GoComplex(1, 0) == z}")
    val zs = GoSlice.of(z, w)
    zs(1) = zs(0) + zs(1)
    println(zs.iterator.mkString(" "))
    // Go's %g: shortest digits, exponents below -4 or from 6 on.
    println(Seq(GoComplex(1e-7, 1e6), GoComplex(123456, -0.0001), GoComplex(1234567.5, 1.5e300),
      GoComplex(-0.0, Double.NaN), GoComplex(Double.PositiveInfinity, Double.NegativeInfinity),
      GoComplex(0.1 + 0.2, 1e21)).mkString(" "))
