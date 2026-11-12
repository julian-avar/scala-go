import scalago.go.*

// Limitations of Go interop (docs/_docs/user/limitations.md, "Go interop"): Go types that a
// hand-written facade cannot express (generated facades leave out more:
// limits-interop-facadegen). Each use is a link error. When one starts to link, this case fails:
// move the use to interop-facades, and update the docs.

@GoImport("sync/atomic")
object atomic:
  // A generic Go type.
  class Pointer:
    def Load(): AnyRef = native

@GoImport("crypto/sha256")
object sha256:
  // Returns an array, [32]byte.
  def Sum256(data: Array[Byte]): Array[Byte] = native

@GoImport("math/cmplx")
object cmplx:
  // Takes a complex128.
  def Abs(x: Double): Double = native

@GoImport("net/url")
object url:
  // Returns url.Values, a named map type: not a GoMap.
  def ParseQuery(query: String): GoMap[GoString, GoSlice[GoString]] = native

object Main:
  def main(args: Array[String]): Unit =
    println(new atomic.Pointer().Load())
    println(sha256.Sum256(Array[Byte](1)).length)
    println(cmplx.Abs(1.0))
    println(url.ParseQuery("a=1"))
