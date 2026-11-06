import scalago.go.*

/** A Scala implementation of Go's io.Writer: upper-cases what it is given. */
class Shouting extends io.Writer:
  val out = new StringBuilder
  def Write(p: Array[Byte]): Int =
    if p.length > 0 && p(0) == '!'.toByte then throw IllegalStateException("no bangs")
    out.append(new String(p, "UTF-8").toUpperCase)
    p.length

object Main:
  def main(args: Array[String]): Unit =
    // Functions, with numbers and strings converted at the boundary
    println(strings.Repeat("ab", 3))
    println(strings.ToUpper("grüße".toGoString).toJavaString)
    println(strings.Contains("seafood", "foo"))
    println(strings.Fields("  a b   c ").mkString("[", ",", "]"))
    println(strings.Join(Array("x", "y", "z"), "-"))

    // Callbacks: Scala functions become Go funcs
    println(strings.FieldsFunc("a1b22c333", c => c >= '0' && c <= '9').mkString("|"))
    println(strings.Map(c => if c == 'a' then 'A' else c, "banana"))

    // Variables and constants
    println(math.Pi)
    println(math.MaxInt32)
    println(math.Sqrt(2.0))
    println(math.Float64bits(1.0) == java.lang.Double.doubleToLongBits(1.0))

    // Errors: thrown as GoException, or returned when the facade asks for them
    println(strconv.Atoi("42") + 1)
    try strconv.Atoi("forty-two")
    catch case e: GoException => println("caught: " + e.getMessage)
    val (b, err) = strconv.ParseBool("maybe")
    println(s"$b ${err != null} ${err.Error()}")
    println(strconv.ParseBool("true")._2 == null)
    val custom = errors.New("custom failure")
    println(custom.Error())
    println(fmt.Errorf("wrapped: %w", custom).Error())

    // Go types: structs (zero value with `new`), methods, fields, named non-struct types
    val sb = new strings.Builder
    sb.WriteString("hello")
    sb.WriteString(", go")
    println(s"${sb.String()} (${sb.Len()})")
    println(strings.NewReplacer("a", "1", "b", "2").Replace("abcab"))
    val u = url.Parse("https://example.com:8080/a/b?q=scala&x=1")
    println(s"${u.Scheme} ${u.Host} ${u.Hostname()} ${u.Path} ${u.Query().Get("q")}")
    u.Path = "/c"
    println(u.show())
    try url.Parse(":bad")
    catch case e: GoException => println("bad url: " + e.error.Error())
    val second = time.Second
    println(s"${second.String()} ${second.Seconds()}")

    // Byte slices share memory with Array[Byte]
    val upper = bytes.ToUpper("abc".getBytes("UTF-8"))
    println(new String(upper, "UTF-8"))

    // fmt with Scala values; output ordering between Scala and Go
    println(fmt.Sprintf("%d-%s-%v-%.2f", 7, "seven", true, 1.5))
    print("scala, ")
    fmt.Println("go,", 1, "two")
    println("scala again")

    // A Go interface implemented in Scala, and a Go type implementing it
    val shouting = Shouting()
    val n = fmt.Fprintf(shouting, "%s and %s", "scala", "go")
    println(s"${shouting.out} ($n bytes)")
    try fmt.Fprintf(shouting, "!")
    catch case e: GoException => println("writer failed: " + e.getMessage)
    val buf = new bytes.Buffer
    val w: io.Writer = buf
    fmt.Fprintf(w, "%d+%d", 1, 2)
    buf.WriteString("=3")
    println(s"${buf.String()} ${buf.Len()} ${w.isInstanceOf[bytes.Buffer]} ${(shouting: Any).isInstanceOf[io.Writer]}")
