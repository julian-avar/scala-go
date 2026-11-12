import java.nio.charset.StandardCharsets.UTF_8

import scalago.go.*
import scalago.gostd.{big, binary, bytes, cmplx, context, crc32, csv, exec, flag, gzip, hex, htmltemplate, http, io, jsonv2,
  list, math, mathrand, netip, randv2, regexp, sha256, strconv, strings, texttemplate, uuid}

/** Packages of the whole standard library (scalago-gostd), beyond the most used ones: arrays
 *  (slices of copies), struct{} (Unit), uint64 constants (their bits), objects named as Go import
 *  aliases (mathrand, randv2, texttemplate, htmltemplate), complex numbers (GoComplex), values of
 *  types Scala code cannot name (unexported, internal: binary.BigEndian, jsonv2 options).
 */
object Main:
  def bytesOf(s: String): GoSlice[GoByte] = GoSlice.of(s.getBytes(UTF_8).map(GoByte(_))*)
  def text(b: GoSlice[GoByte]): String = new String(b.iterator.map(_.toByte).toArray, UTF_8)

  def main(args: Array[String]): Unit =
    val re = regexp.MustCompile("a(b+)c")
    println(s"regexp: ${re.FindStringSubmatch("xabbbcx").iterator.mkString(" ")} ${re.ReplaceAllString("abc-abbc", "<$1>")}")

    // [32]byte: a slice of a copy.
    val digest = sha256.Sum256(bytesOf("abc"))
    println(s"sha256: ${digest.length} bytes, ${hex.EncodeToString(digest)}")
    println(s"crc32: ${crc32.ChecksumIEEE(bytesOf("hello"))}")

    val records = csv.NewReader(strings.NewReader("name,qty\npear,3\n")).ReadAll()
    println(s"csv: ${records.iterator.map(_.iterator.mkString("|")).mkString("; ")}")

    val l = list.New()
    l.PushBack(1L)
    l.PushBack(GoString("two"))
    l.PushFront(0L)
    val values = Iterator.iterate(l.Front())(_.Next()).takeWhile(_ != null).map(_.Value).toList
    println(s"list: ${l.Len()} $values")

    val buffer = new bytes.Buffer
    val template = texttemplate.New("greeting").Parse("Hello, {{.}}!")
    template.Execute(buffer, "Gopher")
    println(s"text/template: ${buffer.String()}")
    println(s"html/template: ${htmltemplate.HTMLEscapeString("<b>&</b>")}")

    val n = new big.Int
    n.SetString("123456789012345678901234567890", 10)
    println(s"math/big: ${new big.Int().Mul(n, n).String()}")

    // Constants beyond Long that fit a uint64 are their bits.
    println(s"math: ${math.MaxUint64} ${math.MaxUint32} ${math.MaxInt64}")

    println(s"mathrand: ${mathrand.New(mathrand.NewSource(1)).Intn(1000)}")
    println(s"randv2: ${randv2.New(randv2.NewPCG(1, 2)).IntN(1000)}")

    // [4]byte and [16]byte.
    val addr = netip.MustParseAddr("10.0.0.1")
    println(s"netip: ${addr.As4().iterator.mkString(".")} ${netip.AddrFrom4(GoSlice.of(Seq(192, 168, 0, 1).map(b => GoByte(b.toByte))*)).String()}")
    val id = uuid.MustParse("f47ac10b-58cc-4372-a567-0e02b2c3d479")
    println(s"uuid: ${id.String()} ${uuid.UUID(GoSlice.of((1 to 16).map(i => GoByte(i.toByte))*)).String()}")

    // <-chan struct{}: a GoChan[Unit].
    val (ctx, cancel) = context.WithCancel(context.Background())
    cancel()
    println(s"context: ${ctx.Done().receive()} ${ctx.Err().Error()}")

    val compressed = new bytes.Buffer
    val writer = gzip.NewWriter(compressed)
    writer.Write(bytesOf("hello, " * 20))
    writer.Close()
    println(s"gzip: ${text(io.ReadAll(gzip.NewReader(compressed))).length} bytes back")

    val flags = flag.NewFlagSet("demo", flag.ContinueOnError)
    val count = flags.Int("n", 1, "count")
    flags.Parse(GoSlice.of(GoString("-n"), GoString("5"), GoString("rest")))
    println(s"flag: ${count.get} ${flags.Args().iterator.mkString}")

    println(s"exec: ${text(exec.Command("echo", "from a subprocess").Output()).trim}")

    // Values of unexported types: BigEndian's type is binary's unexported bigEndian, a ByteOrder.
    val word = GoSlice.make[GoByte](4)
    binary.BigEndian.PutUint32(word, 0x01020304)
    println(s"binary: ${word.iterator.mkString(",")} ${binary.LittleEndian.Uint32(word)} ${binary.BigEndian.String()}")
    val appended = binary.LittleEndian.asInstanceOf[binary.AppendByteOrder].AppendUint16(GoSlice.make[GoByte](0), 258)
    println(s"binary: ${appended.iterator.mkString(",")}")
    // encoding/json/v2's options are of an internal package's type: calls go through reflection.
    val counts = GoMap.of(GoString("b") -> GoInt(2), GoString("a") -> GoInt(1))
    println(s"json/v2: ${text(jsonv2.Marshal(counts, jsonv2.Deterministic(true)))}")
    // A reflective call converts arguments at run time: one of the wrong type throws.
    try jsonv2.Marshal(counts, "not an option")
    catch case e: ClassCastException => println(s"json/v2: ${e.getMessage}")
    val request = http.NewRequest("POST", "http://example.com/", http.NoBody)
    println(s"http.NoBody: ${request.Method} ${request.ContentLength}")

    // complex128 is GoComplex.
    println(s"cmplx: ${cmplx.Abs(GoComplex(3, 4))} ${cmplx.Sqrt(GoComplex(-1, 0))} ${strconv.FormatComplex(GoComplex(1.5, -2), 'f'.toByte, 1, 128)}")
    println(s"complex: ${strconv.ParseComplex("(1+2i)", 128) * GoComplex(0, 1)} ${cmplx.Inf().real}")
