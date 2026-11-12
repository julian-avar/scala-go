import java.nio.charset.StandardCharsets.UTF_8

import scalago.go.*
import scalago.gostd.{bufio, errors, filepath, fmt, fs, io, os, path, strings, url}

/** scalago-gostd's io, bufio, os, path, path/filepath and net/url (docs/_docs/libraries/gostd.md). */
object Main:
  def bytesOf(s: String): GoSlice[GoByte] = GoSlice.fromSeq(s.getBytes(UTF_8).toSeq.map(GoByte(_)))
  def text(b: GoSlice[GoByte]): String = String(b.toSeq.map(_.toByte).toArray, UTF_8)

  def main(args: Array[String]): Unit =
    // io: readers and writers of Go and Scala, output ordered with Scala's.
    println(s"ReadAll: ${text(io.ReadAll(io.MultiReader(strings.NewReader("ab"), strings.NewReader("cd"))))}")
    println(s"LimitReader: ${text(io.ReadAll(io.LimitReader(strings.NewReader("abcdef"), 3)))}")
    print("scala, then ")
    io.WriteString(os.Stdout, "go, then ")
    println("scala")
    val copied = io.Copy(os.Stdout, strings.NewReader("copied to standard output\n"))
    println(s"$copied bytes copied")
    fmt.Fprintln(os.Stdout, "Fprintln to os.Stdout", 1, true)

    // A pipe between a Scala thread and Go's reader.
    val (r, w) = io.Pipe()
    val writer = Thread(() =>
      for i <- 1 to 3 do io.WriteString(w, s"chunk $i;")
      w.Close()
    )
    writer.start()
    println(s"Pipe: ${text(io.ReadAll(r))}")
    writer.join()

    // bufio: scanners with Go's and Scala's split functions, readers, writers.
    val lines = bufio.NewScanner(strings.NewReader("one\ntwo\n\nthree"))
    val seen = collection.mutable.ListBuffer.empty[String]
    while lines.Scan() do seen += lines.Text()
    println(s"lines: ${seen.mkString("|")}")
    val words = bufio.NewScanner(strings.NewReader("  to be   or not "))
    words.Split(bufio.ScanWords)
    seen.clear()
    while words.Scan() do seen += words.Text()
    println(s"words: ${seen.mkString("|")}")
    val pairs = bufio.NewScanner(strings.NewReader("abcde"))
    pairs.Split { (data, atEOF) =>
      if data.length >= 2 then (2L, data.slice(0, 2))
      else if atEOF && data.length > 0 then (data.length.toLong, data)
      else (0L, null.asInstanceOf[GoSlice[GoByte]])
    }
    seen.clear()
    while pairs.Scan() do seen += pairs.Text()
    println(s"pairs: ${seen.mkString("|")}")
    val reader = bufio.NewReader(strings.NewReader("first\nsecond"))
    println(s"ReadString: [${reader.ReadString('\n'.toByte).stripLineEnd}]")
    try reader.ReadString('\n'.toByte)
    catch case e: GoException => println(s"at the end: ${errors.Is(e.error, io.EOF)} ${e.getMessage}")
    val out = bufio.NewWriter(os.Stdout)
    out.WriteString("buffered by Go, ")
    println(s"scala first (${out.Buffered()} bytes buffered)")
    out.Flush()
    println("flushed")

    // os: environment (env.txt) and files.
    println(s"Getenv: ${os.Getenv("SCALAGO_INTEROP_VAR")} [${os.Getenv("SCALAGO_INTEROP_UNSET")}] ${os.LookupEnv("SCALAGO_INTEROP_UNSET")}")
    val dir = os.MkdirTemp("", "scalago-interop-gostd-io-*")
    try
      os.WriteFile(filepath.Join(dir, "b.txt"), bytesOf("bee"), fs.FileMode(0x1a4))
      os.Mkdir(filepath.Join(dir, "sub"), fs.FileMode(0x1ed))
      os.WriteFile(filepath.Join(dir, "sub", "a.txt"), bytesOf("ay!"), fs.FileMode(0x1a4))
      println(s"ReadFile: ${text(os.ReadFile(filepath.Join(dir, "b.txt")))}")
      val info = os.Stat(filepath.Join(dir, "sub", "a.txt"))
      println(s"Stat: ${info.Name()} ${info.Size()} ${info.IsDir()} ${info.Mode().IsDir()} ${fs.FileMode(0x1a4).String()}")
      try os.ReadFile(filepath.Join(dir, "missing"))
      catch case e: GoException => println(s"missing: ${os.IsNotExist(e.error)} ${errors.Is(e.error, os.ErrNotExist)}")
      // A Scala callback for filepath.WalkDir, in lexical order.
      filepath.WalkDir(dir, (p, entry, err) =>
        println(s"walk: ${filepath.Rel(dir, p)} dir=${entry.IsDir()}")
      )
      try filepath.WalkDir(dir, (p, entry, err) => if entry.Name() == "sub" then throw GoException(filepath.SkipDir))
      catch case e: GoException => println(s"unexpected: $e")
      println(s"Glob: ${filepath.Glob(filepath.Join(dir, "*.txt")).iterator.map(g => filepath.Base(g.toJavaString)).mkString(",")}")
    finally os.RemoveAll(dir)

    // path and path/filepath.
    println(s"path: ${path.Join("a", "b/../c", "/d")} ${path.Base("/x/y.tar.gz")} ${path.Ext("y.tar.gz")} ${path.Clean("a//b/./c/..")} ${path.Dir("/x/y")} ${path.Split("a/b/c")} ${path.Match("*.go", "main.go")} ${path.IsAbs("/a")}")
    println(s"filepath: ${filepath.Rel("/a/b", "/a/c/d")} ${filepath.Split("/x/y.txt")} ${filepath.Dir("a/b/c")} ${filepath.IsAbs("a")} ${filepath.ToSlash("a/b")} ${filepath.SplitList("/bin:/usr/bin").length}")

    // net/url: a named map type built as a composite literal, and URL structs.
    val values = url.Values("b" -> GoSlice.of(go"2"), "a" -> GoSlice.of(go"1", go"x y"))
    values.Add("c", "3")
    values.Set("b", "two")
    println(s"Values: ${values.Encode()} ${values.Get("a")}")
    println(s"ParseQuery: ${url.ParseQuery("x=1&y=%C3%A9").Get("y")}")
    println(s"escape: ${url.QueryEscape("a b&c")} ${url.PathEscape("a b/c")}")
    val base = url.Parse("https://example.com/a/b/c?q=1")
    println(s"ResolveReference: ${base.ResolveReference(url.Parse("../d?r=2")).String()} ${base.IsAbs()}")
    val u = new url.URL
    u.Scheme = "https"
    u.Host = "go.dev"
    u.Path = "/doc"
    u.User = url.UserPassword("me", "secret")
    println(s"built: ${u.String()}")
