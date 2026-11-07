import java.io.*
import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.nio.file.attribute.{BasicFileAttributes, FileTime}

import scala.io.Source
import scala.util.{Try, Using}

/** File-system operations in a fresh temporary directory: only deterministic facts are printed
 *  (no absolute paths, no timestamps).
 */
object Main:
  def section(name: String): Unit = println(s"-- $name")

  def error(body: => Any): String =
    Try(body) match
      case scala.util.Failure(e) => s"${e.getClass.getName}: ${e.getMessage}"
      case scala.util.Success(v) => s"no error: $v"

  /** The message of an exception about `root`'s files, relative to it. */
  def rel(root: Path, s: String): String = s.replace(root.toString + "/", "")

  def lines(iterable: Seq[String]): java.lang.Iterable[String] = new java.lang.Iterable[String]:
    def iterator(): java.util.Iterator[String] = new java.util.Iterator[String]:
      private val it = iterable.iterator
      def hasNext(): Boolean = it.hasNext
      def next(): String = it.next()

  def javaIo(root: File): Unit =
    section("java.io.File")
    val dir = new File(root, "io")
    println(s"mkdir ${dir.mkdir()} again ${dir.mkdir()} exists ${dir.exists()} isDirectory ${dir.isDirectory}")
    val nested = new File(dir, "a/b/c")
    println(s"mkdir nested ${nested.mkdir()} mkdirs ${nested.mkdirs()} ${nested.isDirectory}")
    val f = new File(dir, "data.txt")
    println(s"createNewFile ${f.createNewFile()} again ${f.createNewFile()} isFile ${f.isFile} length ${f.length}")

    Using.resource(new FileWriter(f)) { w => w.write("first line\nsecond line\n") }
    Using.resource(new FileWriter(f, true)) { w => w.write("appended ünïcödé\n") }
    println(s"length ${f.length}")
    Using.resource(new BufferedReader(new FileReader(f))) { r =>
      Iterator.continually(r.readLine()).takeWhile(_ != null).foreach(l => println(s"  read: $l"))
    }
    Using.resource(new PrintWriter(new FileOutputStream(new File(dir, "latin1.txt")), true, StandardCharsets.ISO_8859_1))(
      _.println("Grüße"))
    println(s"latin1 bytes: ${new File(dir, "latin1.txt").length}")
    Using.resource(new FileInputStream(new File(dir, "latin1.txt"))) { in =>
      println(s"available ${in.available()}, skip ${in.skip(2)}, read ${in.read().toChar}")
      println(new String(in.readAllBytes(), StandardCharsets.ISO_8859_1).trim)
    }
    Using.resource(new PrintStream(new File(dir, "ps.txt"))) { ps => ps.println(3.5); ps.printf("%s%n", "printf") }
    println(Source.fromFile(new File(dir, "ps.txt")).getLines().mkString(","))

    println(s"name ${f.getName} parent ${f.getParentFile.getName} absolute ${f.isAbsolute} ${new File("rel").isAbsolute}")
    println(s"path normalization: ${new File("a//b/")} ${new File("/")} ${new File("x", "y")} ${new File("", "y")}")
    println(s"getParent: ${new File("a/b").getParent} ${new File("a").getParent} ${new File("/a").getParent}")
    println(s"canonical ${new File(dir, "a/../data.txt").getCanonicalPath == f.getCanonicalPath}")
    println(s"canRead ${f.canRead} canWrite ${f.canWrite} canExecute ${f.canExecute} hidden ${new File(".x").isHidden}")
    println(s"setReadOnly ${f.setReadOnly()} canWrite ${f.canWrite} setWritable ${f.setWritable(true)} ${f.canWrite}")
    println(s"setLastModified ${f.setLastModified(1700000000000L)} ${f.lastModified}")
    println(s"list: ${dir.list().sorted.mkString(", ")}")
    println(s"listFiles: ${dir.listFiles().filter(_.isFile).map(_.getName).sorted.mkString(", ")}")
    println(s"filtered: ${dir.list((_, name) => name.endsWith(".txt")).sorted.mkString(", ")}")
    println(s"list of a file: ${f.list()}")
    val renamed = new File(dir, "renamed.txt")
    println(s"renameTo ${f.renameTo(renamed)} ${f.exists} ${renamed.exists}")
    println(s"delete non-empty dir ${new File(dir, "a").delete()}, file ${renamed.delete()} again ${renamed.delete()}")
    println(s"missing: exists ${new File(dir, "nope").exists} length ${new File(dir, "nope").length} " +
      s"lastModified ${new File(dir, "nope").lastModified}")
    val tmp = File.createTempFile("pre", ".suf", dir)
    println(s"temp file: ${tmp.getName.startsWith("pre")} ${tmp.getName.endsWith(".suf")} ${tmp.exists} ${tmp.length}")
    println(error(File.createTempFile("ab", null)))
    println(new File("x").toPath.resolve("y"))

    section("java.io errors")
    println(error(new FileInputStream("missing-dir/missing.txt")))
    println(error(new FileReader(new File("missing.txt"))))
    println(rel(root.toPath, error(new FileInputStream(dir))))
    println(rel(root.toPath, error(new FileOutputStream(new File(dir, "no/such/dir.txt")))))
    println(error(new RandomAccessFile("x", "rx")))
    println(rel(root.toPath, error(new File(dir, "no/file").createNewFile())))
    val in = new FileInputStream(new File(dir, "latin1.txt"))
    in.close()
    println(error(in.read()))

    section("RandomAccessFile")
    val raf = new RandomAccessFile(new File(dir, "raf.bin"), "rw")
    raf.writeInt(42)
    raf.writeUTF("héllo")
    raf.writeBytes("line one\r\nline two\n")
    println(s"pointer ${raf.getFilePointer} length ${raf.length}")
    raf.seek(0)
    println(s"${raf.readInt()} ${raf.readUTF()} [${raf.readLine()}] [${raf.readLine()}] ${raf.readLine()}")
    raf.setLength(4)
    println(s"after setLength: pointer ${raf.getFilePointer} length ${raf.length}")
    raf.seek(0)
    println(s"skipBytes ${raf.skipBytes(10)} read ${raf.read()}")
    raf.close()

  def nio(root: Path): Unit =
    section("paths")
    val p = Paths.get("a", "b", "", "c.txt")
    println(s"$p name ${p.getFileName} parent ${p.getParent} root ${p.getRoot} count ${p.getNameCount}")
    println(s"${Path.of("/x/y").getRoot} ${Path.of("/").getFileName} ${Path.of("/").getParent} ${Path.of("").getFileName}")
    println(s"${Path.of("a//b/")} ${Path.of("a/./b/../c").normalize} ${Path.of("../a/..").normalize} ${Path.of("/../a").normalize}")
    println(s"${Path.of("a").resolve("b")} ${Path.of("a").resolve("/b")} ${Path.of("a/b").resolveSibling("c")} ${Path.of("").resolve("x")}")
    println(s"${Path.of("a/b").relativize(Path.of("a/c/d"))} ${Path.of("/a").relativize(Path.of("/a/b"))} [${Path.of("a").relativize(Path.of("a"))}]")
    println(s"${Path.of("a/b/c").startsWith("a/b")} ${Path.of("a/b/c").startsWith("a/bc")} ${Path.of("a/b/c").endsWith("b/c")} ${Path.of("/a").startsWith("/")}")
    println(s"${Path.of("a/b/c").subpath(1, 3)} ${Path.of("a/b/c").getName(0)} ${Path.of("x").toAbsolutePath.isAbsolute}")
    val it = Path.of("/p/q/r").iterator()
    var names = List.empty[String]
    while it.hasNext do names ::= it.next().toString
    println(names.reverse.mkString(","))
    println(s"${Path.of("b").compareTo(Path.of("a")) > 0} ${Path.of("a/b") == Paths.get("a", "b")} ${Path.of("a").toFile.getPath}")
    println(error(Path.of("a\u0000b")))

    section("java.nio.file.Files")
    val dir = Files.createDirectory(root.resolve("nio"))
    println(s"exists ${Files.exists(dir)} isDirectory ${Files.isDirectory(dir)} notExists ${Files.notExists(dir.resolve("x"))}")
    val deep = Files.createDirectories(dir.resolve("d1/d2/d3"))
    println(s"createDirectories ${dir.relativize(deep)} again ${dir.relativize(Files.createDirectories(deep))}")
    val text = dir.resolve("text.txt")
    Files.writeString(text, "Grüße\nline 2\r\nline 3")
    println(s"size ${Files.size(text)} regular ${Files.isRegularFile(text)} readable ${Files.isReadable(text)}")
    println(Files.readString(text).replace("\r", "\\r").replace("\n", "\\n"))
    println(Files.readAllBytes(text).length)
    Files.writeString(text, "\nappended", StandardOpenOption.APPEND)
    Using.resource(Files.newBufferedReader(text)) { r =>
      println(Iterator.continually(r.readLine()).takeWhile(_ != null).mkString("|"))
    }
    Files.write(dir.resolve("lines.txt"), lines(Seq("alpha", "beta", "gamma")))
    println(Source.fromFile(dir.resolve("lines.txt").toFile).getLines().toList)
    println(Files.readAllLines(dir.resolve("lines.txt")))
    println(Files.readAllLines(text, StandardCharsets.UTF_8).size())
    val perms = java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----")
    Files.setPosixFilePermissions(dir.resolve("lines.txt"), perms)
    println(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("lines.txt"))))
    println(s"${perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ)} ${perms.size()}")
    Using.resource(Files.newBufferedWriter(dir.resolve("w.txt"), StandardCharsets.ISO_8859_1)) { w =>
      w.write("é")
      w.newLine()
    }
    println(Files.readAllBytes(dir.resolve("w.txt")).map(_ & 0xff).mkString(" "))
    Files.write(dir.resolve("bin"), Array[Byte](1, 2, 3))
    Using.resource(Files.newOutputStream(dir.resolve("bin"), StandardOpenOption.APPEND))(_.write(4))
    Using.resource(Files.newInputStream(dir.resolve("bin")))(in => println(in.readAllBytes().mkString(",")))
    println(Files.getLastModifiedTime(text).toMillis > 0)
    Files.setLastModifiedTime(text, FileTime.fromMillis(86400000L * 365 + 1234))
    println(Files.getLastModifiedTime(text))
    val attrs = Files.readAttributes(text, classOf[BasicFileAttributes])
    println(s"attrs: file ${attrs.isRegularFile} dir ${attrs.isDirectory} size ${attrs.size}")

    val copy = Files.copy(text, dir.resolve("copy.txt"))
    println(s"copy ${Files.readString(copy) == Files.readString(text)}")
    println(rel(root, error(Files.copy(text, copy))))
    Files.writeString(copy, "replaced")
    Files.copy(copy, text, StandardCopyOption.REPLACE_EXISTING)
    println(Files.readString(text))
    val moved = Files.move(copy, dir.resolve("d1/moved.txt"))
    println(s"move ${Files.exists(copy)} ${Files.readString(moved)}")
    println(Files.copy(new ByteArrayInputStream("from stream".getBytes), dir.resolve("s.txt")))
    val sink = new ByteArrayOutputStream()
    println(s"${Files.copy(dir.resolve("s.txt"), sink)} $sink")

    Using.resource(Files.newDirectoryStream(dir)) { ds =>
      var entries = List.empty[String]
      val it = ds.iterator()
      while it.hasNext do entries ::= dir.relativize(it.next()).toString
      println(entries.sorted.mkString(", "))
    }
    val tmp = Files.createTempFile(dir, "tmp", ".txt")
    val tmpDir = Files.createTempDirectory(dir, "tmpdir")
    println(s"temp: ${tmp.getFileName.toString.startsWith("tmp")} ${tmp.toString.endsWith(".txt")} ${Files.isDirectory(tmpDir)}")
    println(s"deleteIfExists ${Files.deleteIfExists(tmp)} ${Files.deleteIfExists(tmp)}")

    section("nio errors")
    println(rel(root, error(Files.readString(dir.resolve("missing.txt")))))
    println(rel(root, error(Files.createDirectory(dir))))
    println(rel(root, error(Files.createDirectories(text))))
    println(rel(root, error(Files.delete(dir.resolve("d1")))))
    println(rel(root, error(Files.delete(dir.resolve("missing")))))
    println(rel(root, error(Files.size(dir.resolve("missing")))))
    println(rel(root, error(Files.newOutputStream(text, StandardOpenOption.CREATE_NEW))))
    println(rel(root, error(Files.newOutputStream(dir.resolve("no/such.txt")))))
    println(rel(root, error(Files.readAllBytes(dir))))
    println(rel(root, error(Files.newDirectoryStream(text))))
    println(rel(root, error(Files.move(dir.resolve("missing"), dir.resolve("x")))))
    println(error(Files.newOutputStream(text, StandardOpenOption.APPEND, StandardOpenOption.TRUNCATE_EXISTING)))
    Files.write(dir.resolve("bad.txt"), Array[Byte]('o', 'k', 0xff.toByte))
    println(error(Files.readString(dir.resolve("bad.txt"))))
    println(error(Files.writeString(dir.resolve("bad.txt"), "€", StandardCharsets.US_ASCII)))

    section("walkFileTree")
    var shallow = List.empty[String]
    Files.walkFileTree(dir, java.util.Collections.emptySet[FileVisitOption](), 1, new SimpleFileVisitor[Path]:
      override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult =
        shallow ::= s"${dir.relativize(file)} ${attrs.isDirectory}"
        FileVisitResult.CONTINUE
    )
    println(shallow.map(_.replaceAll("tmpdir[0-9]+", "tmpdir*")).sorted.mkString(", "))
    var visited = List.empty[String]
    Files.walkFileTree(dir, new SimpleFileVisitor[Path]:
      override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult =
        visited ::= s"file ${dir.relativize(file)}"
        Files.delete(file)
        FileVisitResult.CONTINUE
      override def postVisitDirectory(d: Path, exc: IOException): FileVisitResult =
        visited ::= s"dir [${dir.relativize(d)}]"
        Files.delete(d)
        FileVisitResult.CONTINUE
    )
    println(visited.map(_.replaceAll("tmpdir[0-9]+", "tmpdir*")).sorted.mkString("\n"))
    println(s"deleted: ${Files.exists(dir)}")

  def deleteTree(f: File): Unit =
    if f.isDirectory then f.listFiles().foreach(deleteTree)
    f.delete()

  def main(args: Array[String]): Unit =
    val root = Files.createTempDirectory("io-files-test")
    try
      javaIo(root.toFile)
      nio(root)
    finally deleteTree(root.toFile)
    println(s"cleaned up: ${!Files.exists(root)}")
