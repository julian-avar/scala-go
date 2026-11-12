import scalago.go.*

// A program with Go calls that runs on the JVM too (docs/_docs/interop/facades.md, "On the JVM"):
// facade members throw UnsupportedOperationException there, so the Go calls are behind a trait of
// the program's own, with an implementation for each platform. Go data, GoString, GoError and
// GoException work on both.
@GoImport("strconv")
object strconv:
  def Itoa(i: Int): String = native
  def Quote(s: String): String = native
  def IntSize: Int = native

@GoImport("strings")
object strings:
  class Builder:
    def WriteString(s: String): Int = native
    def String(): String = native

trait Formatting:
  def itoa(i: Int): String
  def quote(s: String): String

object GoFormatting extends Formatting:
  def itoa(i: Int): String = strconv.Itoa(i)
  def quote(s: String): String = strconv.Quote(s)

object JvmFormatting extends Formatting:
  def itoa(i: Int): String = i.toString
  def quote(s: String): String = "\"" + s.flatMap {
    case '"'  => "\\\""
    case '\t' => "\\t"
    case c    => c.toString
  } + "\""

/** A Scala error type, a Go error on Go. */
final class NotFound(name: String) extends GoError:
  def Error(): String = s"$name not found"

object Main:
  def platform: Formatting =
    try
      strconv.IntSize
      GoFormatting
    catch case _: UnsupportedOperationException => JvmFormatting

  def main(args: Array[String]): Unit =
    val f = platform
    println(s"${f.itoa(42)} ${f.quote("tab\there \"q\"")}")

    // Every kind of facade member throws UnsupportedOperationException on the JVM.
    val onGo = f eq GoFormatting
    def check(what: String)(body: => Any): Unit =
      val result =
        try { body; "ran" }
        catch case _: UnsupportedOperationException => "unsupported"
      println(s"$what: ${if onGo then result == "ran" else result == "unsupported"}")
    check("function")(strconv.Itoa(1))
    check("constant")(strconv.IntSize)
    check("method")(new strings.Builder().WriteString("x"))

    // Scala implementations of GoError, and GoException, work on both.
    val e = GoException(NotFound("key"))
    println(s"${e.getMessage} ${e.error.Error()} ${e.isInstanceOf[RuntimeException]}")
    try throw e
    catch case GoExceptionOf(err) => println(s"matched ${err.Error()}")

    // Go data and GoString work on both.
    val data = GoSlice.of(go"a", go"b").appended(go"c")
    println(s"${data.length} ${data.iterator.map(_.toJavaString).mkString} ${go"x${1}y".byteLength}")

object GoExceptionOf:
  def unapply(e: Throwable): Option[GoError] = e match
    case g: GoException => Some(g.error)
    case _              => None
