import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.CountDownLatch

/** Standard I/O: lines on standard output and standard error keep their order, non-ASCII text
 *  is UTF-8 (unpaired surrogates become '?'), print calls from many threads do not interleave,
 *  output keeps its place around a subprocess that inherits it, System.setOut/setErr/setIn and
 *  Console.withOut/withErr/withIn redirect, and standard input is closed (no stdin.txt).
 */
object Main:
  def main(args: Array[String]): Unit =
    // Output on the two streams keeps program order, also text without a newline.
    println("out 1")
    System.err.println("err 1")
    System.out.println("out 2")
    Console.err.println("err 2")
    Console.println("out 3")
    System.err.print("err 3\n")
    System.out.print("out 4\n")
    print("text without a newline, then ")
    System.err.println("a line on stderr")

    // Text.
    println("Grüße, ☃, 😀, 中文")
    println("unpaired: a\uD800b c\uDC00d")
    System.err.println("on stderr: ünïcödé")
    println(null: String)
    System.out.println(Array('c', 'h', 'a', 'r', 's'))
    System.out.print(42)
    System.out.print(' ')
    System.out.print(true)
    System.out.println()
    System.out.write("raw bytes\n".getBytes(UTF_8))
    System.out.flush()
    println(s"checkError: ${System.out.checkError()}")

    // Many threads print the same long line many times: every line arrives whole.
    val line = ("0123456789" * 30)
    val go = new CountDownLatch(1)
    val printers = (1 to 4).map { _ =>
      val t = new Thread(() =>
        go.await()
        for _ <- 1 to 25 do println(line)
      )
      t.start()
      t
    }
    go.countDown()
    printers.foreach(_.join())
    println("printers done")

    // A subprocess that inherits standard output writes after the lines printed before it.
    println("before the subprocess")
    val child = new ProcessBuilder("sh", "-c", "echo from the subprocess; echo its stderr >&2").inheritIO().start()
    println(s"subprocess exit: ${child.waitFor()}")

    // System.setOut, setErr, setIn.
    val original = (System.out, System.err, System.in)
    val out = new ByteArrayOutputStream
    val err = new ByteArrayOutputStream
    System.setOut(new PrintStream(out, true, UTF_8))
    System.setErr(new PrintStream(err, true, UTF_8))
    System.setIn(new ByteArrayInputStream("typed\n".getBytes(UTF_8)))
    System.out.println("to the new out")
    System.err.println("to the new err")
    val typed = new String(System.in.readAllBytes(), UTF_8)
    System.setOut(original._1)
    System.setErr(original._2)
    System.setIn(original._3)
    println(s"setOut: ${out.toString(UTF_8).trim}; setErr: ${err.toString(UTF_8).trim}; setIn: ${typed.trim}")
    println(s"restored: ${System.out eq original._1} ${System.err eq original._2} ${System.in eq original._3}")

    // Console.withOut, withErr, withIn: per thread, restored afterwards.
    val consoleOut = new ByteArrayOutputStream
    val consoleErr = new ByteArrayOutputStream
    Console.withOut(consoleOut) {
      Console.withErr(consoleErr) {
        println("via Console.out")
        Console.err.println("via Console.err")
        printf("%s-%d%n", "printf", 1)
      }
    }
    val read = Console.withIn(new ByteArrayInputStream("first\nsecond\n".getBytes(UTF_8))) {
      (scala.io.StdIn.readLine(), scala.io.StdIn.readLine(), scala.io.StdIn.readLine())
    }
    println(s"withOut: ${consoleOut.toString(UTF_8).trim.replace('\n', '|')}; withErr: ${consoleErr.toString(UTF_8).trim}; withIn: $read")

    // Standard input is closed.
    println(s"stdin: ${System.in.read()} ${System.in.available()} ${scala.io.StdIn.readLine()}")
    System.err.println("last line, on stderr")
