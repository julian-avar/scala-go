import scala.io.StdIn

/** Reads standard input (stdin.txt) with StdIn until its end, then exits with a status. */
object Main:
  def main(args: Array[String]): Unit =
    Runtime.getRuntime.addShutdownHook(new Thread(() => println("shutdown hook")))
    print("name? ")
    val name = StdIn.readLine()
    println(s"hello, $name")
    val n = StdIn.readInt()
    var sum = 0
    for _ <- 1 to n do sum += StdIn.readLine().trim.toInt
    println(s"sum of $n numbers: $sum")
    // The rest, line by line (with \r\n and non-ASCII), until null at the end of the input.
    var line = StdIn.readLine()
    while line != null do
      println(s"[$line] ${line.length}")
      line = StdIn.readLine()
    println(s"after EOF: ${StdIn.readLine()}")
    try StdIn.readInt()
    catch case e: java.io.EOFException => println(s"${e.getClass.getName}: ${e.getMessage}")
    println(s"System.in.read: ${System.in.read()}")
    System.out.print("exiting")
    sys.exit(3)
