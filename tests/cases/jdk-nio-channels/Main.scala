import java.io.*
import java.nio.ByteBuffer
import java.nio.channels.*
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.*

import scala.util.Using
import scala.util.chaining.*

/** File channels, Channels' adapters and the default file system's path matchers, in a fresh
 *  temporary directory: only deterministic facts are printed.
 */
object Main:
  def section(name: String): Unit = println(s"-- $name")

  def error(body: => Any): String =
    try s"ok ${body}"
    catch case e: Exception => s"${e.getClass.getName}: ${e.getMessage}"

  def rel(root: Path, s: String): String = s.replace(root.toString + "/", "")

  def text(b: ByteBuffer): String = new String(b.array(), b.arrayOffset() + b.position(), b.remaining(), UTF_8)

  def show(lock: FileLock): String =
    if lock == null then "null"
    else s"[${lock.position}:${lock.size} ${if lock.isShared then "shared" else "exclusive"} ${if lock.isValid then "valid" else "invalid"}]"

  def channels(root: Path): Unit =
    section("FileChannel")
    val file = root.resolve("data.bin")
    Using.resource(FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.READ)) { ch =>
      println(s"open ${ch.isOpen} size ${ch.size} position ${ch.position}")
      val n = ch.write(ByteBuffer.wrap("hello, channels".getBytes(UTF_8)))
      println(s"wrote $n position ${ch.position} size ${ch.size}")
      val gathered = ch.write(Array(ByteBuffer.wrap(" [".getBytes(UTF_8)), ByteBuffer.wrap("gathered]".getBytes(UTF_8))))
      println(s"gathered $gathered size ${ch.size}")
      ch.position(7)
      val buf = ByteBuffer.allocate(8)
      println(s"read ${ch.read(buf)} '${text(buf.flip())}' position ${ch.position}")
      buf.clear()
      println(s"positional read ${ch.read(buf, 0)} '${text(buf.flip())}' position ${ch.position}")
      println(s"positional write ${ch.write(ByteBuffer.wrap("HELLO".getBytes(UTF_8)), 0)} position ${ch.position}")
      println(s"read past the end ${ch.read(ByteBuffer.allocate(4), 100)}")
      ch.position(ch.size)
      println(s"at end ${ch.read(ByteBuffer.allocate(4))}")
      val first = ByteBuffer.allocate(3)
      val second = ByteBuffer.allocate(4)
      ch.position(0)
      println(s"scattered ${ch.read(Array(first, second))} '${text(first.flip())}' '${text(second.flip())}'")
      println(s"truncate ${ch.truncate(12).size} position ${ch.position} ${ch.truncate(100).size}")
      ch.force(true)
      println(s"position beyond the end ${ch.position(20).position} read ${ch.read(ByteBuffer.allocate(1))}")
    }
    println(s"contents '${Files.readString(file)}'")

    section("options")
    println(error(FileChannel.open(root.resolve("missing"))).pipe(rel(root, _)))
    println(error(FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)).pipe(rel(root, _)))
    println(error(FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.APPEND)))
    println(error(FileChannel.open(file, StandardOpenOption.APPEND, StandardOpenOption.TRUNCATE_EXISTING)))
    Using.resource(FileChannel.open(file)) { ch =>
      println(error(ch.write(ByteBuffer.allocate(1))))
      println(error(ch.truncate(0)))
      println(error(ch.lock()))
      println(error(ch.read(ByteBuffer.allocate(1).asReadOnlyBuffer())))
    }
    Using.resource(FileChannel.open(file, StandardOpenOption.APPEND)) { ch =>
      println(s"append position ${ch.position} ${error(ch.read(ByteBuffer.allocate(1)))}")
      ch.write(ByteBuffer.wrap("!".getBytes(UTF_8)))
      println(s"appended size ${ch.size} position ${ch.position}")
    }
    Using.resource(FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) { ch =>
      println(s"truncated size ${ch.size}")
      ch.write(ByteBuffer.wrap("0123456789".getBytes(UTF_8)))
    }
    val temp = root.resolve("temp.txt")
    val tc = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.DELETE_ON_CLOSE)
    tc.write(ByteBuffer.wrap("x".getBytes(UTF_8)))
    println(s"delete on close: exists ${Files.exists(temp)}")
    tc.close()
    println(s"after close: exists ${Files.exists(temp)} open ${tc.isOpen} ${error(tc.position())} ${error(tc.size())}")
    tc.close()

    section("transfers")
    Using.resource(FileChannel.open(file)) { src =>
      val out = new ByteArrayOutputStream()
      println(s"transferTo ${src.transferTo(2, 5, Channels.newChannel(out))} '${out.toString("UTF-8")}' position ${src.position}")
      val copy = root.resolve("copy.txt")
      Using.resource(FileChannel.open(copy, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) { dst =>
        println(s"transferFrom ${dst.transferFrom(src, 0, Long.MaxValue)} size ${dst.size} position ${dst.position}")
        println(s"transferFrom beyond the end ${dst.transferFrom(Channels.newChannel(new ByteArrayInputStream(Array[Byte](1))), 50, 1)}")
        println(s"transferFrom a stream ${dst.transferFrom(Channels.newChannel(new ByteArrayInputStream("ab".getBytes(UTF_8))), 10, 5)}")
      }
      println(s"copy size ${Files.size(copy)} '${Files.readString(copy).take(12)}' ${Files.readAllBytes(copy)(50)}")
    }

    section("map")
    Using.resource(FileChannel.open(file)) { ch =>
      val mapped = ch.map(FileChannel.MapMode.READ_ONLY, 3, 4)
      println(s"${mapped.remaining()} ${mapped.isReadOnly} ${mapped.get(0).toChar}${mapped.get(3).toChar} ${error(mapped.put(0.toByte))}")
      println(error(ch.map(FileChannel.MapMode.PRIVATE, 0, 1)))
    }
    Using.resource(FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) { ch =>
      val priv = ch.map(FileChannel.MapMode.PRIVATE, 0, 3)
      priv.put(0, 'X'.toByte)
      println(s"private ${priv.get(0).toChar} file ${Files.readString(file).take(3)}")
    }

    section("locks")
    Using.resource(FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) { ch =>
      val lock = ch.lock()
      println(s"${show(lock)} valid ${lock.isValid} shared ${lock.isShared} channel ${lock.channel eq ch}")
      println(error(ch.tryLock(0, 10, true)))
      println(s"overlaps ${lock.overlaps(5, 1)} ${lock.overlaps(-5, 1)}")
      lock.release()
      println(s"released: valid ${lock.isValid}")
      val region = ch.tryLock(0, 4, true)
      val other = ch.tryLock(4, 4, false)
      println(s"${show(region)} ${show(other)}")
      region.close()
      Using.resource(FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) { ch2 =>
        println(error(ch2.lock(6, 1, false)))
        println(s"other region ${show(ch2.tryLock(10, 1, false))}")
      }
      println(s"after closing the other channel ${other.isValid}")
    }

  def streams(root: Path): Unit =
    section("stream channels")
    val file = root.resolve("data.bin")
    val in = new FileInputStream(file.toFile)
    val ch = in.getChannel
    println(s"same channel ${ch eq in.getChannel} position ${ch.position}")
    println(s"read ${in.read().toChar}${in.read().toChar} position ${ch.position}")
    ch.position(5)
    println(s"stream reads ${in.read().toChar} at the channel's position, size ${ch.size}")
    in.close()
    println(s"channel open after closing the stream: ${ch.isOpen}")

    val out = new FileOutputStream(root.resolve("out.txt").toFile)
    val och = out.getChannel
    och.write(ByteBuffer.wrap("abc".getBytes(UTF_8)))
    out.write('d')
    println(s"position ${och.position} ${error(och.read(ByteBuffer.allocate(1)))}")
    och.close()
    println(error(out.write('e')))
    val appending = new FileOutputStream(root.resolve("out.txt").toFile, true)
    println(s"append position ${appending.getChannel.position}")
    appending.close()

    Using.resource(new RandomAccessFile(root.resolve("out.txt").toFile, "rw")) { raf =>
      val rch = raf.getChannel
      raf.seek(2)
      println(s"raf position ${rch.position} read ${rch.read(ByteBuffer.allocate(10))} pointer ${raf.getFilePointer}")
    }

    section("Channels")
    val bytes = Files.newByteChannel(file)
    println(s"${bytes.isInstanceOf[FileChannel]} size ${bytes.size}")
    val stream = Channels.newInputStream(bytes)
    println(s"available ${stream.available()} skip ${stream.skip(3)} read ${stream.read().toChar}")
    println(s"rest '${new String(stream.readAllBytes(), UTF_8)}' read ${stream.read()}")
    stream.close()
    println(s"closing the stream closes the channel: ${bytes.isOpen}")

    val wch = Files.newByteChannel(root.resolve("writer.txt"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    Using.resource(Channels.newWriter(wch, UTF_8)) { w => w.write("written through a channel: ü\nsecond line\n") }
    Using.resource(new BufferedReader(Channels.newReader(Files.newByteChannel(root.resolve("writer.txt")), "UTF-8"))) { r =>
      println(s"${r.readLine()} | ${r.readLine()} | ${r.readLine()}")
    }
    val sink = new ByteArrayOutputStream()
    Using.resource(Channels.newOutputStream(Channels.newChannel(sink))) { os => os.write("to a stream".getBytes(UTF_8)) }
    println(sink.toString("UTF-8"))
    val source = Channels.newChannel(new ByteArrayInputStream("from a stream".getBytes(UTF_8)))
    val buf = ByteBuffer.allocate(64)
    println(s"${source.read(buf)} '${text(buf.flip())}' ${source.read(ByteBuffer.allocate(1))}")
    source.close()
    println(error(source.read(ByteBuffer.allocate(1))))

  def fileSystems(root: Path): Unit =
    section("FileSystem")
    val fs = FileSystems.getDefault
    println(s"${root.getFileSystem eq fs} open ${fs.isOpen} readOnly ${fs.isReadOnly} separator ${fs.getSeparator}")
    fs.getRootDirectories.forEach(r => println(s"root $r"))
    println(fs.getPath("a", "b", "c"))
    println(error(fs.close()))
    val paths = Seq("a.txt", "dir/b.txt", "dir/sub/c.scala", "Main.scala", ".hidden", "x.TXT", "data1.csv", "data22.csv")
    def matching(pattern: String): String =
      val m = fs.getPathMatcher(pattern)
      paths.filter(p => m.matches(Paths.get(p))).mkString(" ")
    for pattern <- Seq("glob:*.txt", "glob:**.txt", "glob:**/*.scala", "glob:*.{txt,scala}", "glob:dir/*", "glob:dir/**",
        "glob:data?.csv", "glob:data[0-9]*.csv", "glob:[!a-m]*", "glob:.*", "glob:*.[tT][xX][tT]", "regex:.*\\.(txt|csv)",
        "regex:dir/.*", "GLOB:a.txt")
    do println(s"$pattern: ${matching(pattern)}")
    for bad <- Seq("glob:[abc", "glob:{a,{b}}", "glob:a\\", "glob:[a/b]", "foo:x", "nocolon", ":x")
    do println(s"$bad: ${error(fs.getPathMatcher(bad)).takeWhile(_ != '\n')}")

  def main(args: Array[String]): Unit =
    val root = Files.createTempDirectory("scalago-nio-channels")
    try
      channels(root)
      streams(root)
      fileSystems(root)
    finally
      Using.resource(Files.walk(root)) { s =>
        s.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(p => Files.delete(p))
      }
