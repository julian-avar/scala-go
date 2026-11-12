import scalago.go.*

// Go errors and panics in Scala, and Scala exceptions in Go (docs/_docs/spec/interop.md, "Errors
// and panics"; docs/_docs/interop/facades.md, "Errors").
@GoImport("example.com/fail")
object fail:
  def ErrBase: GoError = native
  def Parse(s: String): Int = native
  @GoName("Parse") def parseOrError(s: String): (Int, GoError) = native
  def Check(ok: Boolean): Unit = native
  @GoName("Check") def checkError(ok: Boolean): GoError = native
  def NilMyError(): GoError = native
  def Is(err: GoError): Boolean = native
  def Code(err: GoError): Int = native
  def Type(err: GoError): String = native
  def Panic(v: Any): Unit = native
  def Index(xs: Array[Long], i: Int): Long = native
  def NilX(): Int = native
  def Div(a: Int, b: Int): Int = native
  def Assert(v: Any): String = native
  def Deferred(f: () => Unit): Unit = native
  def Recover(f: () => Unit): String = native
  def CallErr(f: () => Unit): String = native

/** A Scala error type: Go code sees it as an error. */
final class ScalaError(code: Int) extends GoError:
  def Error(): String = s"scala error $code"

final class Marker extends RuntimeException("marker")

object Main:
  def attempt(what: String)(body: => Any): Unit =
    val result =
      try s"returns ${body}"
      catch case e: Exception => s"throws ${e.getClass.getName}: ${e.getMessage}"
    println(s"$what: $result")

  def main(args: Array[String]): Unit =
    // A trailing error result left out of the facade is thrown as a GoException.
    attempt("Parse(\"abc\")")(fail.Parse("abc"))
    attempt("Parse(\"\")")(fail.Parse(""))
    try fail.Parse("")
    catch case e: GoException => println(s"wrapped: ${fail.Code(e.error)} ${fail.Type(e.error)} ${e.error.Error()}")
    attempt("Check(true)")(fail.Check(true))
    attempt("Check(false)")(fail.Check(false))
    try fail.Check(false)
    catch case e: GoException => println(s"wraps ErrBase: ${fail.Is(e.error)}")

    // Kept in the result type, the error is returned; nil is null.
    println(s"(Int, GoError): ${fail.parseOrError("ab")} ${fail.parseOrError("")._2.Error()}")
    println(s"GoError alone: ${fail.checkError(true)} ${fail.checkError(false).Error()}")

    // An error holding a nil pointer is not nil.
    val nilPointer = fail.NilMyError()
    println(s"nil *MyError: ${nilPointer != null} ${fail.Type(nilPointer)}")

    // Scala errors in Go.
    println(s"Scala GoError: ${fail.Type(ScalaError(1)) != ""} ${fail.Code(ScalaError(1))} ${fail.Is(ScalaError(1))}")
    println(s"GoException message: ${GoException(ScalaError(2)).getMessage}")

    // Go panics become exceptions: runtime errors the matching Java exceptions.
    attempt("panic(string)")(fail.Panic("boom"))
    attempt("panic(error)")(fail.Panic(fail.ErrBase))
    attempt("panic(int)")(fail.Panic(42))
    attempt("index out of range")(fail.Index(Array(1L, 2L, 3L), 5))
    attempt("nil dereference")(fail.NilX())
    attempt("division by zero")(fail.Div(1, 0))
    attempt("failed type assertion")(fail.Assert(1))

    // finally blocks run as a panic passes; Go code keeps working afterwards.
    try
      try fail.Panic("unwinding")
      finally println("scala: finally runs")
    catch case e: RuntimeException => println(s"caught ${e.getMessage}")
    println(s"after the panic: ${fail.Parse("again")}")

    // A Scala exception unwinds through Go frames (running deferred calls) and reaches the Scala
    // code that called Go, as itself.
    val marker = Marker()
    try
      fail.Deferred { () =>
        try throw marker
        finally println("scala: inner finally runs")
      }
    catch case e: Marker => println(s"caught the same exception: ${e eq marker}")

    // Go code recovers Scala exceptions: the panic value is an error describing the exception.
    println(fail.Recover(() => throw IllegalStateException("bad state")))
    println(fail.Recover(() => fail.Panic("go panic inside a callback")))
    println(fail.Recover(() => println("scala: no exception")))

    // With an error result, the exception is the error: a GoException gives back its Go error.
    println(fail.CallErr(() => ()))
    println(fail.CallErr(() => throw GoException(fail.ErrBase)))
    println(fail.CallErr(() => throw IllegalArgumentException("bad argument")))
