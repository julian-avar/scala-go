import scala.util.control.NonFatal

// Known bugs found with the Scala library: each line prints what scala-go does today; the comment
// says what the JVM does. Fixing a bug makes this case fail: move the check to a case that runs on
// both the JVM and Go (scala-immutable-collections, scala-option-either-try), and remove the bug
// from the roadmap and the specification.
object Main:
  def attempt(what: String)(body: => Any): Unit =
    val result =
      try String.valueOf(body)
      catch case e: Throwable => s"throws ${e.getClass.getName}${Option(e.getMessage).fold("")(": " + _)}"
    println(s"$what: $result")

  def main(args: Array[String]): Unit =
    // getSimpleName of a class whose name encodes an operator ($colon$colon is `::`) keeps only
    // what follows the last `$` (getName is right). JVM: $colon$colon, $colon$colon, $plus$plus
    attempt("List(1).getClass.getSimpleName")(List(1).getClass.getSimpleName)
    attempt("classOf[::[?]].getSimpleName")(classOf[::[?]].getSimpleName)
    attempt("classOf[++].getSimpleName")(classOf[++].getSimpleName)

    // OutOfMemoryError is a VirtualMachineError, so NonFatal is false (the roadmap lists the
    // same bug for StackOverflowError). JVM: false, true
    attempt("NonFatal(OutOfMemoryError())")(NonFatal(OutOfMemoryError()))
    attempt("OutOfMemoryError is a VirtualMachineError")((OutOfMemoryError(): Throwable).isInstanceOf[VirtualMachineError])

class ++
