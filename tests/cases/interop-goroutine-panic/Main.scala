import scalago.go.*

@GoImport("example.com/crash")
object crash:
  def Run(f: () => Unit): Unit = native

// An exception thrown by Scala code on a goroutine that Go code started, and that nothing
// recovers, ends the program with Go's panic report and exit status 2, without Scala's
// uncaught-exception handlers (docs/_docs/spec/interop.md, "Errors and panics"). GOTRACEBACK=none
// (env.txt) leaves the goroutine traces out of the report.
object Main:
  def main(args: Array[String]): Unit =
    Thread.setDefaultUncaughtExceptionHandler((t, e) => println(s"handler: not called ($e)"))
    println("before")
    crash.Run(() => throw IllegalStateException("from a goroutine"))
    println("not printed")
