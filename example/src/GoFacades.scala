import scalago.go.*

/** Go's strconv package (standard library). */
@GoImport("strconv")
object strconv:
  def Quote(s: String): String = native

/** The local Go module in example/go/textstats (see `goLocalModules` in build.mill). */
@GoImport("example.com/textstats")
object textstats:
  def Words(text: String): Array[String] = native
  def Longest(words: Array[String]): String = native // a Go error is thrown as GoException
