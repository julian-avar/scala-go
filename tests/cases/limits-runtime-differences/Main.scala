import java.io.{BufferedReader, File, InputStreamReader, OutputStream}
import java.net.*
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.CountDownLatch

import scala.jdk.CollectionConverters.*
import scala.reflect.ClassTag

import scalago.go.*

@GoImport("os")
object goos:
  def Executable(): String = native

class Program

object FailingInit:
  val value: Int = Main.failInit()

/** Documented differences of the runtime (docs/_docs/spec/runtime.md, "Deviation"; and
 *  docs/_docs/user/differences.md, "Runtime" and "I/O and networking") that no other limits-*
 *  case pins. Each line prints what scala-go does; the comment says what the JVM does. When one
 *  changes, this case fails: move the line to a case that runs on the JVM too (if it now
 *  matches), and update the docs. The cases about exit and signals run the program itself as a
 *  child process. env.txt sets a German locale.
 */
object Main:
  def attempt(what: String)(body: => Any): Unit =
    val result =
      try String.valueOf(body)
      catch case e: Throwable => s"throws ${e.getClass.getName}${Option(e.getMessage).fold("")(": " + _)}"
    println(s"$what: $result")

  def sum(n: Int): Long = if n == 0 then 0L else n + sum(n - 1)
  def failInit(): Int = throw new IllegalStateException("init failed")
  def add(a: Int, b: Int): Int = a + b
  def fail(): Int = throw new RuntimeException("fail")

  def child(mode: String, args: String*): ProcessBuilder =
    new ProcessBuilder((Seq(goos.Executable(), mode) ++ args)*)

  /** Starts a child that creates `ready` when it is ready to be signalled, sends it `signal`
   *  (`kill`), and returns its exit status and output.
   */
  def signalled(mode: String, signal: String): String =
    val ready = File.createTempFile("scalago-ready", "")
    ready.delete()
    val output = File.createTempFile("scalago-output", ".txt")
    try
      val process = child(mode, ready.getPath).redirectErrorStream(true).redirectOutput(output).start()
      val deadline = System.nanoTime() + 30_000_000_000L
      while !ready.exists && System.nanoTime() < deadline do Thread.sleep(5)
      if signal == "TERM" then process.destroy()
      else new ProcessBuilder("kill", s"-$signal", process.pid.toString).start().waitFor()
      val status = process.waitFor()
      val text = new String(java.nio.file.Files.readAllBytes(output.toPath), UTF_8)
      s"status $status, output [${text.linesIterator.take(1).mkString}]"
    finally
      ready.delete()
      output.delete()

  def childMain(mode: String, args: Seq[String]): Unit = mode match
    case "exit-in-hook" =>
      Runtime.getRuntime.addShutdownHook(new Thread(() =>
        println("the hook calls exit")
        System.exit(5)
      ))
      println("main returns")
    case "wait-for-signal" =>
      Runtime.getRuntime.addShutdownHook(new Thread(() => println("the hook ran")))
      println("a line printed before the signal")
      new File(args.head).createNewFile()
      Thread.sleep(60000)
    case "write-to-closed-pipe" =>
      val line = "x" * 99
      var i = 0
      while i < 1000000 && !System.out.checkError() do
        println(line)
        i += 1
      System.err.println(s"stopped writing after ${i} lines")

  def main(args: Array[String]): Unit =
    if args.nonEmpty then return childMain(args.head, args.toSeq.tail)

    // ---- Program exit and signals --------------------------------------------------------
    // JVM: never ends (exit from a shutdown hook blocks).
    attempt("exit in a shutdown hook") {
      val p = child("exit-in-hook").redirectErrorStream(true).start()
      val out = new String(p.getInputStream.readAllBytes(), UTF_8)
      s"status ${p.waitFor()}, output [${out.linesIterator.mkString(" | ")}]"
    }
    // JVM: status 143, output [a line printed before the signal] (and the hook runs).
    attempt("SIGTERM")(signalled("wait-for-signal", "TERM"))
    // JVM: prints a thread dump, continues, and ends after its sleep with status 0.
    attempt("SIGQUIT")(signalled("wait-for-signal", "QUIT"))
    // JVM: status 0: System.out's error flag is set, and the program goes on.
    attempt("writing to a closed standard output") {
      val p = child("write-to-closed-pipe").start()
      val reader = new BufferedReader(new InputStreamReader(p.getInputStream, UTF_8))
      reader.readLine()
      p.getInputStream.close()
      s"status ${p.waitFor()}"
    }

    // ---- Threads ---------------------------------------------------------------------------
    // JVM 25: 3.
    attempt("main threadId")(Thread.currentThread().threadId)
    // JVM: false: println waits for the monitor of System.out, held here.
    val printer = new Thread(() => println("printed while main holds System.out's monitor"))
    System.out.synchronized {
      printer.start()
      printer.join(5000)
      attempt("println while another thread holds System.out's monitor finished")(!printer.isAlive)
    }
    printer.join()
    // JVM: java.net.SocketException (interrupting a virtual thread blocked in socket I/O closes
    // the socket).
    attempt("interrupt of a virtual thread blocked in ServerSocket.accept") {
      val socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress)
      @volatile var result = "still blocked"
      val acceptor = Thread.ofVirtual().start(() =>
        try
          socket.accept()
          result = "accepted"
        catch case e: Exception => result = e.getClass.getName
      )
      Thread.sleep(100)
      acceptor.interrupt()
      acceptor.join(1000)
      result
    }

    // ---- Memory and stack ------------------------------------------------------------------
    // JVM: the heap limit (-Xmx; by default a quarter of the memory).
    attempt("Runtime.maxMemory is Long.MaxValue")(Runtime.getRuntime.maxMemory == Long.MaxValue)
    // JVM: throws java.lang.StackOverflowError (the main thread's stack is 8 MB at most).
    attempt("a million calls deep on the main thread")(sum(1000000))

    // ---- System properties, locale ---------------------------------------------------------
    // JVM: OpenJDK 64-Bit Server VM (or the vendor's), the vendor, the class path.
    attempt("java.vm.name")(System.getProperty("java.vm.name"))
    attempt("java.vendor")(System.getProperty("java.vendor"))
    attempt("java.class.path")(s"[${System.getProperty("java.class.path")}]")
    // JVM: de_DE (LC_ALL in env.txt).
    attempt("Locale.getDefault")(java.util.Locale.getDefault)

    // ---- Classes ---------------------------------------------------------------------------
    // JVM: jdk.internal.loader.ClassLoaders$AppClassLoader@...
    attempt("class loader of a program class")(classOf[Program].getClassLoader)
    // JVM: [Ljava.lang.String;, then [[I for each of the next four.
    attempt("reflect.Array.newInstance(String)")(java.lang.reflect.Array.newInstance(classOf[String], 1).getClass.getName)
    attempt("reflect.Array.newInstance(int, 2, 3)")(java.lang.reflect.Array.newInstance(classOf[Int], 2, 3).getClass.getName)
    attempt("ClassTag[Array[Int]].newArray")(summon[ClassTag[Array[Int]]].newArray(1).getClass.getName)
    attempt("Array.ofDim[Int](2, 2)")(Array.ofDim[Int](2, 2).getClass.getName)
    attempt("Array(Array(1))")(Array(Array(1)).getClass.getName)

    // ---- Stack traces ----------------------------------------------------------------------
    // JVM: 4, the line of `fail()`.
    val here = new Exception().getStackTrace()(0).getLineNumber
    try
      add(
        1,
        fail())
    catch case e: RuntimeException =>
      attempt("line of main's frame, from `here`")(e.getStackTrace()(1).getLineNumber - here)
    // JVM: java.lang.NumberFormatException.forInputString (frames of the JDK's own methods).
    try Integer.parseInt("x")
    catch case e: NumberFormatException =>
      attempt("top frame of Integer.parseInt's exception")(e.getStackTrace.head.getClassName + "." + e.getStackTrace.head.getMethodName)
    // JVM: java.lang.Thread.run(Thread.java), and nothing after it.
    val frames = new Array[String](1)
    val t = new Thread(() =>
      frames(0) = new Exception().getStackTrace.drop(1).map(f => s"${f.getClassName}.${f.getMethodName}(${f.getFileName})").mkString(" < ")
    )
    t.start()
    t.join()
    attempt("frames below a thread's body")(frames(0))
    // JVM: java.lang.ExceptionInInitializerError, FailingInit$.<clinit> (see known-bugs-misc for
    // the missing ExceptionInInitializerError).
    try FailingInit.value
    catch case e: Throwable =>
      val root = if e.getCause != null then e.getCause else e
      attempt("frame of an object's initializer")(root.getStackTrace.map(f => s"${f.getClassName}.${f.getMethodName}").find(_.startsWith("FailingInit")))

    // ---- Time zones ------------------------------------------------------------------------
    // JVM: 2 rules; 2 transitions listed after 1996, the last in 1997 (the rules give the later
    // ones).
    val rules = java.time.ZoneId.of("Europe/Paris").getRules
    attempt("Europe/Paris getTransitionRules size")(rules.getTransitionRules.size)
    attempt("Europe/Paris transitions listed after 1996")(rules.getTransitions.asScala.count(_.getDateTimeBefore.getYear > 1996))
    attempt("Europe/Paris last listed transition year")(rules.getTransitions.asScala.last.getDateTimeBefore.getYear)

    // ---- I/O and networking ----------------------------------------------------------------
    // JVM: 789ccb48cdc9c957c8402701680308b1 (zlib's bytes; both decompress to the same text).
    attempt("Deflater output") {
      val deflater = new java.util.zip.Deflater()
      deflater.setInput("hello hello hello hello".getBytes(UTF_8))
      deflater.finish()
      val buffer = new Array[Byte](100)
      val n = deflater.deflate(buffer)
      buffer.take(n).map(b => f"${b & 0xff}%02x").mkString
    }
    val server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress)
    val base = s"http://127.0.0.1:${server.getLocalPort}/"
    /** Serves one request with a lower-case header name; returns the request's header names. */
    def serveOnce(): java.util.concurrent.Future[String] =
      val result = new java.util.concurrent.CompletableFuture[String]
      val t = new Thread(() =>
        val socket = server.accept()
        val in = new BufferedReader(new InputStreamReader(socket.getInputStream, UTF_8))
        val names = Iterator.continually(in.readLine()).takeWhile(l => l != null && l.nonEmpty).drop(1).map(_.takeWhile(_ != ':')).toList
        socket.getOutputStream.write("HTTP/1.1 200 OK\r\nx-lower-case: v\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".getBytes(UTF_8))
        socket.close()
        result.complete(names.filter(n => n.equalsIgnoreCase("upgrade") || n.equalsIgnoreCase("http2-settings")).sorted.mkString(",")): Unit
      )
      t.setDaemon(true)
      t.start()
      result
    // JVM: Connection,Content-Length,x-lower-case (as the server sent them).
    attempt("HttpURLConnection response header names") {
      val served = serveOnce()
      val connection = URI.create(base).toURL.openConnection().asInstanceOf[HttpURLConnection]
      connection.getResponseCode
      served.get()
      connection.getHeaderFields.keySet.asScala.filter(_ != null).toList.sorted.mkString(",")
    }
    // JVM: HTTP2-Settings,Upgrade (it asks to upgrade to HTTP/2 over cleartext).
    attempt("headers of an HTTP/2 request to upgrade") {
      val served = serveOnce()
      val client = java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_2).build()
      client.send(java.net.http.HttpRequest.newBuilder(URI.create(base)).build(), java.net.http.HttpResponse.BodyHandlers.ofString())
      s"[${served.get()}]"
    }
