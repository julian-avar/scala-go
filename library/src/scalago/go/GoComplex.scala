package scalago.go

import scalago.go.internal.GoNatives

/** Go `complex128`: a complex number of two `Double`s. In Go it *is* a `complex128`, so it goes
 *  to and from Go code (`math/cmplx`, `strconv.ParseComplex`) as it is; on the JVM it is a pair.
 *  `==` compares values, and `toString` formats as Go's `fmt` does (`(1+2i)`). Go's `complex64`
 *  converts to and from it.
 *
 *  {{{
 *  val z = GoComplex(1, 2)
 *  (z * z).real                   // -3.0
 *  cmplx.Abs(GoComplex(3, 4))     // 5.0 (scalago-gostd)
 *  }}}
 */
opaque type GoComplex = AnyRef

object GoComplex:
  def apply(real: Double, imag: Double): GoComplex = GoNatives.complexMake(real, imag)

  extension (z: GoComplex)
    def real: Double = GoNatives.complexReal(z)
    def imag: Double = GoNatives.complexImag(z)
    def +(w: GoComplex): GoComplex = GoNatives.complexAdd(z, w)
    def -(w: GoComplex): GoComplex = GoNatives.complexSub(z, w)
    def *(w: GoComplex): GoComplex = GoNatives.complexMul(z, w)
    def /(w: GoComplex): GoComplex = GoNatives.complexDiv(z, w)
