import java.io.File
import java.nio.charset.StandardCharsets.UTF_8

import scalago.go.*

@GoImport("os")
object goos:
  def Executable(): String = native

/** SIGINT, SIGTERM and SIGHUP end the program like `System.exit`: the shutdown hooks run, standard
 *  output (a pipe here) is flushed, and the status is 128 plus the signal's number. The program
 *  runs itself as a child process, which prints a line (buffered, not yet flushed), registers a
 *  hook and waits to be signalled.
 *
 *  On the JVM the child's output and statuses are those expected.txt has.
 */
object Main:
  def attempt(what: String)(body: => Any): Unit =
    val result =
      try String.valueOf(body)
      catch case e: Throwable => s"throws ${e.getClass.getName}${Option(e.getMessage).fold("")(": " + _)}"
    println(s"$what: $result")

  /** Starts the child, which creates `ready` once it waits; sends it `signal`; reports its end. */
  def signalled(signal: String): String =
    val ready = File.createTempFile("scalago-ready", "")
    ready.delete()
    val output = File.createTempFile("scalago-output", ".txt")
    try
      val process = new ProcessBuilder(goos.Executable(), ready.getPath).redirectErrorStream(true).redirectOutput(output).start()
      val deadline = System.nanoTime() + 30_000_000_000L
      while !ready.exists && System.nanoTime() < deadline do Thread.sleep(5)
      new ProcessBuilder("kill", s"-$signal", process.pid.toString).start().waitFor()
      val status = process.waitFor()
      val text = new String(java.nio.file.Files.readAllBytes(output.toPath), UTF_8)
      s"status $status, output [${text.linesIterator.mkString(" | ")}]"
    finally
      ready.delete()
      output.delete()

  def main(args: Array[String]): Unit =
    if args.nonEmpty then
      Runtime.getRuntime.addShutdownHook(new Thread(() => println("the hook ran")))
      println("a line printed before the signal")
      new File(args.head).createNewFile()
      Thread.sleep(60000)
    else
      attempt("SIGINT")(signalled("INT"))
      attempt("SIGTERM")(signalled("TERM"))
      attempt("SIGHUP")(signalled("HUP"))
