import java.io.*
import java.nio.charset.{Charset, CodingErrorAction, MalformedInputException, StandardCharsets}

import scala.io.{Codec, Source}
import scala.util.{Try, Using}

object Main:
  def show(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString(" ")

  def section(name: String): Unit = println(s"-- $name")

  def byteStreams(): Unit =
    section("byte streams")
    val out = new ByteArrayOutputStream()
    out.write(1)
    out.write(Array[Byte](2, 3, 4, 5), 1, 2)
    out.write("hé".getBytes(StandardCharsets.UTF_8))
    println(s"${out.size()} bytes: ${show(out.toByteArray)}")
    println(out.toString(StandardCharsets.UTF_8).drop(3))
    val in = new ByteArrayInputStream(out.toByteArray)
    println(s"read ${in.read()}, available ${in.available()}")
    val buf = new Array[Byte](2)
    println(s"read ${in.read(buf)} into ${show(buf)}; skipped ${in.skip(1)}")
    println(s"rest: ${show(in.readAllBytes())}, then ${in.read()}")

    val buffered = new BufferedInputStream(new ByteArrayInputStream("abcdef".getBytes))
    buffered.mark(10)
    println(s"${buffered.read().toChar}${buffered.read().toChar}")
    buffered.reset()
    println(new String(buffered.readNBytes(4)))

    val pushback = new PushbackInputStream(new ByteArrayInputStream("xyz".getBytes), 2)
    val first = pushback.read()
    pushback.unread(first)
    pushback.unread('w')
    println(new String(pushback.readAllBytes()))

  def dataStreams(): Unit =
    section("data streams")
    val bytes = new ByteArrayOutputStream()
    val out = new DataOutputStream(bytes)
    out.writeInt(-2)
    out.writeLong(1L << 40)
    out.writeDouble(3.25)
    out.writeFloat(-1.5f)
    out.writeShort(65535)
    out.writeChar('é')
    out.writeBoolean(true)
    out.writeUTF("naïve ☃")
    out.flush()
    println(s"${out.size()} bytes written")
    val in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray))
    println(List(in.readInt(), in.readLong(), in.readDouble(), in.readFloat(), in.readShort(), in.readChar(),
      in.readBoolean(), in.readUTF()).mkString(", "))
    try in.readByte()
    catch case e: EOFException => println(s"EOF: ${e.getClass.getName}")

  def readersAndWriters(): Unit =
    section("readers and writers")
    val reader = new BufferedReader(new StringReader("one\r\ntwo\rthree\n\nlast"))
    Iterator.continually(reader.readLine()).takeWhile(_ != null).foreach(l => println(s"[$l]"))
    println(reader.readLine())

    val numbered = new LineNumberReader(new StringReader("a\nb\r\nc"))
    while numbered.readLine() != null do ()
    println(s"lines: ${numbered.getLineNumber}")

    val pushback = new PushbackReader(new StringReader("bc"), 2)
    pushback.unread('a')
    val chars = new Array[Char](5)
    println(new String(chars, 0, pushback.read(chars)))

    val sw = new StringWriter()
    sw.write("abc")
    sw.append('d').append("ef").append("xghx", 1, 3)
    sw.write("0123456789", 2, 3)
    println(sw.toString)
    println(sw.getBuffer.length())

    val caw = new CharArrayWriter()
    caw.write("hello")
    println(caw.toCharArray.reverse.mkString)
    val car = new CharArrayReader("abcdef".toCharArray, 1, 3)
    println(Iterator.continually(car.read()).takeWhile(_ != -1).map(_.toChar).mkString)

    val bw = new StringWriter()
    val buffered = new BufferedWriter(bw)
    buffered.write("buffered")
    buffered.newLine()
    println(s"before flush: '${bw.toString}'")
    buffered.flush()
    println(s"after flush: '${bw.toString.trim}'")

  def printWriters(): Unit =
    section("print writers and streams")
    val sw = new StringWriter()
    val pw = new PrintWriter(sw)
    pw.print(1)
    pw.print(' ')
    pw.print(2.5)
    pw.print(' ')
    pw.print(true)
    pw.print(' ')
    pw.print(null: String)
    pw.println()
    pw.printf("%d-%s|%5.2f%n", 42, "x", 3.14159)
    pw.format("%08X%n", 255)
    pw.flush()
    print(sw)
    println(s"checkError: ${pw.checkError()}")
    pw.close()
    pw.println("after close")
    println(s"checkError after close: ${pw.checkError()}")

    // Autoflush: println flushes through the BufferedWriter; print does not.
    val target = new StringWriter()
    val auto = new PrintWriter(new BufferedWriter(target), true)
    auto.print("pending")
    println(s"autoflush print: '$target'")
    auto.println()
    println(s"autoflush println: '${target.toString.trim}'")

    // Errors of the underlying stream set the error flag instead of throwing.
    val failing = new OutputStream:
      def write(b: Int): Unit = throw new IOException("disk full")
    val ps = new PrintStream(failing)
    ps.print("x")
    println(s"PrintStream checkError: ${ps.checkError()}")

    val bytes = new ByteArrayOutputStream()
    val latin = new PrintStream(bytes, true, "ISO-8859-1")
    latin.print("é€")
    latin.printf("%s!", "ok")
    println(show(bytes.toByteArray))
    try new PrintStream(bytes, true, "NO-SUCH-CHARSET")
    catch case e: UnsupportedEncodingException => println(s"${e.getClass.getName}: ${e.getMessage}")

  def charsets(): Unit =
    section("charsets")
    val text = "Grüße, ☃ and 😀"
    for cs <- List(StandardCharsets.UTF_8, StandardCharsets.ISO_8859_1, StandardCharsets.US_ASCII) do
      val bytes = new ByteArrayOutputStream()
      val writer = new OutputStreamWriter(bytes, cs)
      writer.write(text)
      writer.close()
      val decoded = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes.toByteArray), cs)).readLine()
      println(s"${cs.name}: ${bytes.size()} bytes, ${show(bytes.toByteArray.take(8))} -> $decoded")
      println(s"  getBytes: ${text.getBytes(cs).length} bytes, new String: ${new String(text.getBytes(cs), cs)}")
    println(Charset.forName("utf8").name + " " + Charset.forName("latin1") + " " + Charset.defaultCharset())
    println(s"${Charset.isSupported("UTF-8")} ${Charset.isSupported("x-unknown")}")
    try Charset.forName("x-unknown")
    catch case e: IllegalArgumentException => println(s"${e.getClass.getName}: ${e.getMessage}")
    println(new InputStreamReader(new ByteArrayInputStream(Array[Byte]()), StandardCharsets.UTF_8).getEncoding)

    // Malformed UTF-8 is replaced by readers...
    val malformed = Array[Byte]('a', 0xff.toByte, 'b', 0xe2.toByte, 0x82.toByte, 'c', 0xe2.toByte)
    val reader = new InputStreamReader(new ByteArrayInputStream(malformed), StandardCharsets.UTF_8)
    val decoded = Iterator.continually(reader.read()).takeWhile(_ != -1).map(c => f"$c%04x").mkString(" ")
    println(s"replaced: $decoded")
    // ...and reported by a REPORT decoder (scala.io.Codec's default).
    try println(Source.fromInputStream(new ByteArrayInputStream(malformed))(using Codec.UTF8).mkString)
    catch case e: MalformedInputException => println(s"${e.getClass.getName}: ${e.getMessage}")
    val lenient = Codec.UTF8.onMalformedInput(CodingErrorAction.REPLACE)
    println(Source.fromInputStream(new ByteArrayInputStream(malformed))(using lenient).mkString.map(c => f"${c.toInt}%04x").mkString(" "))
    println(Source.fromBytes(malformed)(using Codec.UTF8).mkString.map(c => f"${c.toInt}%04x").mkString(" "))

    // A surrogate pair split across writes.
    val bytes = new ByteArrayOutputStream()
    val writer = new OutputStreamWriter(bytes, StandardCharsets.UTF_8)
    writer.write('\ud83d')
    writer.write('\ude00')
    writer.write('\ud83d') // lone high surrogate at the end: replaced
    writer.close()
    println(show(bytes.toByteArray))

  def console(): Unit =
    section("console")
    val captured = new ByteArrayOutputStream()
    scala.Console.withOut(new PrintStream(captured)) {
      println("captured line")
      print(42)
    }
    println(s"withOut captured: '${captured.toString.replace("\n", "|")}'")
    val err = new ByteArrayOutputStream()
    scala.Console.withErr(err)(scala.Console.err.print("to err"))
    println(s"withErr captured: '$err'")
    // Child threads inherit the redirection.
    val fromThread = new ByteArrayOutputStream()
    scala.Console.withOut(fromThread) {
      val t = new Thread(() => println("from a thread"))
      t.start()
      t.join()
    }
    println(s"thread captured: '${fromThread.toString.trim}'")

    val original = System.out
    val replaced = new ByteArrayOutputStream()
    System.setOut(new PrintStream(replaced, true))
    System.out.println("via System.out")
    System.setOut(original)
    println(s"setOut captured: '${replaced.toString.trim}'")
    System.out.print('c')
    System.out.print(1L)
    System.out.print(2.5f)
    System.out.print(Array('x', 'y'))
    System.out.println(null: Object)
    System.out.write('!'.toInt)
    System.out.write("bytes\n".getBytes)
    System.out.append("app").append('e').append("_nd_", 1, 3)
    System.out.println()
    System.out.printf("%s=%d%n", "printf", 7)
    System.out.flush()
    println(s"System.out checkError: ${System.out.checkError()}")

  def using(): Unit =
    section("Using")
    class Res(name: String) extends Closeable:
      println(s"open $name")
      def close(): Unit = println(s"close $name")
    val r = Using.resource(new Res("a"))(_ => "result")
    println(r)
    val t = Using.Manager { use =>
      val b = use(new Res("b"))
      val c = use(new Res("c"))
      throw new IOException("boom")
    }
    println(t)
    println(Using(new BufferedReader(new StringReader("first\nsecond")))(_.readLine()))

  def threadLocals(): Unit =
    section("thread locals")
    val counter = new ThreadLocal[Integer]:
      override def initialValue(): Integer = 0
    counter.set(counter.get + 5)
    val inherited = new InheritableThreadLocal[String]:
      override def childValue(parent: String): String = parent + " (child)"
    inherited.set("parent value")
    var seen = ""
    val t = new Thread(() => seen = s"${counter.get} / ${inherited.get}")
    t.start()
    t.join()
    println(s"main: ${counter.get} / ${inherited.get}; thread: $seen")
    counter.remove()
    println(s"after remove: ${counter.get}")

  def system(): Unit =
    section("system")
    println(System.lineSeparator() == "\n")
    println(List("line.separator", "file.separator", "path.separator").map(System.getProperty).map(_.replace("\n", "\\n")))
    println(List("user.dir", "user.home", "java.io.tmpdir", "os.name", "os.arch").map(k => System.getProperty(k) != null))
    println(System.getProperty("no.such.property") + " " + System.getProperty("no.such.property", "default"))
    println(System.setProperty("scalago.test", "1") + " " + System.getProperty("scalago.test"))
    println(System.clearProperty("scalago.test") + " " + System.getProperty("scalago.test"))
    println(Try(System.getProperty("")).failed.get.getClass.getName)
    println(System.getenv("PATH") != null)
    println(System.getenv("SCALAGO_NO_SUCH_VARIABLE"))
    println(Runtime.getRuntime.availableProcessors() > 0)
    println(Runtime.getRuntime.maxMemory() > 0)
    println(Try(Runtime.getRuntime.addShutdownHook(null)).failed.get.getClass.getName)

  def main(args: Array[String]): Unit =
    byteStreams()
    dataStreams()
    readersAndWriters()
    printWriters()
    charsets()
    console()
    using()
    threadLocals()
    system()
    Runtime.getRuntime.addShutdownHook(new Thread(() => println("shutdown hook ran")))
    System.err.println("to stderr")
    println("end of main")
