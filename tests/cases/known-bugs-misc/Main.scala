import scala.util.control.NonFatal

// Known bugs (docs/roadmap.md, M0 "Known bugs"): each line prints what scala-go does today; the
// comment says what the JVM does. Fixing a bug makes this case fail: move the check to a case
// that runs on both the JVM and Go, and remove the bug from the roadmap and the specification.
//
// Not here: `try throw e finally return x` (go vet rejects the generated code) and facades of
// pointer-receiver methods on non-struct Go types (go build fails).
object Main:
  def attempt(what: String)(body: => Any): Unit =
    val result =
      try String.valueOf(body)
      catch case e: Throwable => s"throws ${e.getClass.getName}${Option(e.getMessage).fold("")(": " + _)}"
    println(s"$what: $result")

  object Failing:
    println("initializing Failing")
    if true then throw IllegalStateException("init failed")
    val value = 1

  def main(args: Array[String]): Unit =
    // JVM: 0 and 4503599627370497
    attempt("Math.round(0.49999999999999994)")(Math.round(0.49999999999999994))
    attempt("Math.round(4503599627370497.0)")(Math.round(4503599627370497.0))

    // JVM: 0 and -1
    attempt("\"\\uD83D\\uDE00\".lastIndexOf(0x1F600)")("\uD83D\uDE00".lastIndexOf(0x1F600))
    attempt("\"\\uFFFD\".indexOf(0x110000)")("\uFFFD".indexOf(0x110000))

    // JVM: false (StackOverflowError is a VirtualMachineError)
    attempt("NonFatal(StackOverflowError())")(NonFatal(StackOverflowError()))

    // JVM: ExceptionInInitializerError, then NoClassDefFoundError caused by it
    attempt("Failing.value")(Failing.value)
    try Failing.value
    catch case e: Throwable => println(s"second access: ${e.getClass.getName}, cause ${e.getCause}")

    // JVM: Local
    class Local
    attempt("getSimpleName of a local class")(classOf[Local].getSimpleName)
