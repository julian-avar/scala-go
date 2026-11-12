import scalago.go.*

@GoImport("os")
object goos:
  def Executable(): String = native

@GoImport("runtime/debug")
object debug:
  def SetMaxStack(bytes: GoInt): GoInt = native

// Limitations (docs/design.md, "Status", not planned to change): a stack overflow ends the program
// with a Go fatal error; StackOverflowError is never thrown. The buffered standard output is lost
// with it, unless the 100 ms flush timer ran first (docs/_docs/spec/runtime.md, "Standard I/O"),
// so only the fatal error is checked here. The program runs itself as a child process, with a
// 16 MB stack limit, and reports how the child ended.
//
// On the JVM the child prints "caught StackOverflowError" and "after", and exits with status 0.
object Main:
  def recurse(n: Int): Int = recurse(n + 1) + 1

  def main(args: Array[String]): Unit =
    if args.sameElements(Array("child")) then
      debug.SetMaxStack(GoInt(16L << 20))
      println("buffered before the overflow")
      try recurse(0)
      catch case e: StackOverflowError => println("caught StackOverflowError")
      println("after")
    else
      val builder = ProcessBuilder(goos.Executable(), "child").redirectErrorStream(true)
      builder.environment.put("GOTRACEBACK", "none")
      val child = builder.start()
      val output = new String(child.getInputStream.readAllBytes(), "UTF-8")
      println(s"exit status: ${child.waitFor()}")
      println(output.linesIterator.find(_.startsWith("fatal error")).getOrElse("no fatal error"))
      println(s"caught StackOverflowError: ${output.contains("caught")}")
