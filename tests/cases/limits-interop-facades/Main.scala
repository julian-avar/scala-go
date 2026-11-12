import scalago.go.*

// Limitations of Go interop (docs/_docs/user/limitations.md, "Go interop"): Go types that a
// hand-written facade cannot express (generated facades leave out more:
// limits-interop-facadegen). Each use is a link error. When one starts to link, this case fails:
// move the use to interop-facades, and update the docs.

// slog.Level is an int: Scala holds a copy, which UnmarshalText (receiver *Level) would change.
@GoImport("log/slog")
object slog:
  class Level:
    def UnmarshalText(text: Array[Byte]): Unit = native

@GoImport("crypto/sha256")
object sha256:
  // Returns an array, [32]byte.
  def Sum256(data: Array[Byte]): Array[Byte] = native

@GoImport("net/url")
object url:
  // Returns url.Values, a named map type: not a GoMap.
  def ParseQuery(query: String): GoMap[GoString, GoSlice[GoString]] = native

@GoImport("sync/atomic")
object atomic:
  // A generic Go type: type tests cannot check its (erased) type arguments.
  class Pointer[T]:
    def Load(): GoPtr[T] = native

object Main:
  def main(args: Array[String]): Unit =
    println(sha256.Sum256(Array[Byte](1)).length)
    println(url.ParseQuery("a=1"))
    new slog.Level().UnmarshalText("INFO".getBytes)
    val x: Any = new atomic.Pointer[GoString]
    println(x.isInstanceOf[atomic.Pointer[?]])
