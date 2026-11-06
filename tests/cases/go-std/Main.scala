import java.nio.charset.StandardCharsets.UTF_8

import scalago.go.*
import scalago.gostd.*

/** The generated facades of Go's standard library, used as they are. */
object Main:
  def main(args: Array[String]): Unit =
    println(strings.Repeat("ab", 3))
    println(strings.ToUpper("grüße"))
    val fields = strings.Fields("  the quick  brown fox ")
    println(s"${fields.length}: ${strings.Join(fields, "-")}")
    val (before, after, found) = strings.Cut("key=value", "=")
    println(s"$before $after $found")
    println(strings.Map(c => if c == 'a' then 'A' else c, "banana"))

    println(strconv.Itoa(42) + "!")
    println(strconv.Atoi("123") + 1)
    try strconv.Atoi("x")
    catch case e: GoException => println(s"Atoi failed: ${e.getMessage}")
    println(strconv.Quote("tab\there"))

    println(math.Sqrt(2) * math.Sqrt(2) > 1.99)
    println(s"${math.MaxInt32} ${math.Pi > 3.14}")

    val d = time.ParseDuration("1h30m")
    println(s"${d.Minutes()} minutes, ${time.Second.String()}, ${d.Round(time.Hour).String()}")

    val u = url.Parse("https://example.com:8080/a/b?x=1&y=two")
    println(s"${u.Hostname()} ${u.Port()} ${u.Path} ${u.Query().Get("y")}")

    val buffer = new bytes.Buffer
    buffer.WriteString("hello, ")
    fmt.Fprintf(buffer, "%s #%d", "world", 1)
    println(s"${buffer.String()} (${buffer.Len()} bytes)")
    println(fmt.Sprintf("%05.1f|%-4s|%v", 3.14159, "go", true))

    println(filepath.Join("a", "b", "c.txt"))
    println(filepath.Ext("archive.tar.gz"))

    val words = GoSlice.of(GoString("pear"), GoString("apple"), GoString("fig"))
    sort.Strings(words)
    println(words.iterator.map(_.toJavaString).mkString(","))

    val counts = GoMap.of(GoString("a") -> GoInt(1), GoString("b") -> GoInt(2))
    val js = json.Marshal(counts)
    println(new String(js.iterator.map(_.toByte).toArray, UTF_8))
    println(base64.StdEncoding.EncodeToString(GoSlice.of("hi".getBytes(UTF_8).map(GoByte(_))*)))

    println(s"${utf8.RuneCountInString("grüße")} runes, ${unicode.IsUpper('Q'.toInt)}")
    println(os.Getenv("SCALAGO_SURELY_NOT_SET").isEmpty)
    println(errors.New("plain").Error())
