// Known bug (docs/roadmap.md, M0): an exception thrown out of `main` skips the default uncaught
// exception handler; the runtime prints it itself. The JVM prints "handler: boom" and exits with
// status 1. Fixing the bug makes this case fail: run it on the JVM too (remove go-only.txt).
object Main:
  def main(args: Array[String]): Unit =
    Thread.setDefaultUncaughtExceptionHandler((t, e) => println(s"handler: ${e.getMessage}"))
    println("before")
    throw IllegalStateException("boom")
