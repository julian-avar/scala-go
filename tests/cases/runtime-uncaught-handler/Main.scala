// An exception thrown out of `main` goes to the default uncaught exception handler, which here
// replaces the stack trace; the program still exits with status 1 (exit-status.txt).
object Main:
  def main(args: Array[String]): Unit =
    Thread.setDefaultUncaughtExceptionHandler((t, e) => println(s"handler: ${t.getName} ${e.getMessage}"))
    println("before")
    throw IllegalStateException("boom")
