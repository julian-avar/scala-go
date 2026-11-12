// Known bugs of the language features (docs/_docs/spec/language.md): each line prints what
// scala-go does today; the comment says what the JVM does. Fixing a bug makes this case fail:
// move the check to a lang-* case that runs on both the JVM and Go, and update the docs.
object Main:
  inline def failing(n: Int): Int =
    if n < 0 then throw IllegalArgumentException(s"negative: $n")
    n

  def main(args: Array[String]): Unit =
    // JVM: 11, the line of the call (the inline method throws on line 6)
    try failing(-1)
    catch case e: IllegalArgumentException =>
      val line = e.getStackTrace.filter(_.getFileName == "Main.scala").map(_.getLineNumber).head
      println(s"line of a throw in inlined code: $line")
