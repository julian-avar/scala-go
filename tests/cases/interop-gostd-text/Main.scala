import java.nio.charset.StandardCharsets.UTF_8

import scalago.go.*
import scalago.gostd.{bytes, errors, fmt, sort, strconv, strings, unicode, utf8}

/** A Scala implementation of sort.Interface. */
final class ByLength(val words: Array[String]) extends sort.Interface:
  def Len(): Long = words.length
  def Less(i: Long, j: Long): Boolean = words(i.toInt).length < words(j.toInt).length
  def Swap(i: Long, j: Long): Unit =
    val t = words(i.toInt)
    words(i.toInt) = words(j.toInt)
    words(j.toInt) = t

/** A Scala fmt.Stringer and fmt.Formatter. */
final class Money(cents: Long) extends fmt.Stringer, fmt.Formatter:
  def String(): String = f"$$${cents / 100}.${cents % 100}%02d"
  def Format(state: fmt.State, verb: Int): Unit =
    fmt.Fprintf(state, "%s(%c, plus: %v)", String(), verb, state.Flag('+'.toLong))

/** scalago-gostd's text packages: strings, strconv, bytes, unicode, utf8, sort, fmt, errors
 *  (docs/_docs/libraries/gostd.md).
 */
object Main:
  def bytesOf(s: String): GoSlice[GoByte] = GoSlice.fromSeq(s.getBytes(UTF_8).toSeq.map(GoByte(_)))
  def text(b: GoSlice[GoByte]): String = String(b.toSeq.map(_.toByte).toArray, UTF_8)
  def strs(xs: GoSlice[GoString]): String = xs.iterator.map(_.toJavaString).mkString("[", ",", "]")

  def main(args: Array[String]): Unit =
    // strings: byte indices, as in Go.
    println(s"Split: ${strs(strings.Split("a,b,,c", ","))} ${strs(strings.SplitN("a,b,c", ",", 2))}")
    println(s"Index: ${strings.Index("héllo", "llo")} ${strings.LastIndex("go gopher", "go")} ${strings.Index("x", "y")}")
    println(s"Count: ${strings.Count("cheese", "e")} ${strings.Count("five", "")} EqualFold: ${strings.EqualFold("Gö", "GÖ")}")
    println(s"Replace: ${strings.Replace("oink oink oink", "k", "ky", 2)} ${strings.ReplaceAll("oink oink", "oink", "moo")}")
    println(s"Trim: [${strings.TrimSpace("  \t x y \n")}] [${strings.TrimLeft("xxhixx", "x")}] [${strings.TrimFunc("123abc456", unicode.IsDigit)}]")
    println(s"ToTitle: ${strings.ToTitle("grüße")} Compare: ${strings.Compare("a", "b")} ContainsRune: ${strings.ContainsRune("abc", 'b')}")
    val (before, after, found) = strings.Cut("k=v=w", "=")
    println(s"Cut: $before $after $found")
    val sb = new strings.Builder
    fmt.Fprintf(sb, "%d-%s", 7, "x")
    sb.WriteString("!")
    println(s"Builder: ${sb.String()} ${sb.Len()}")

    // strconv: errors are *strconv.NumError values.
    println(s"Format: ${strconv.FormatInt(-255, 16)} ${strconv.FormatUint(-1L, 10)} ${strconv.FormatFloat(1.0 / 3, 'f'.toByte, 4, 64)} ${strconv.FormatFloat(1e21, 'g'.toByte, -1, 64)} ${strconv.FormatBool(true)}")
    println(s"Parse: ${strconv.ParseInt("-ff", 16, 64)} ${strconv.ParseUint("777", 8, 64)} ${strconv.ParseFloat("2.5e3", 64)} ${strconv.ParseBool("TRUE")}")
    println(s"Quote: ${strconv.Quote("héllo\n")} ${strconv.QuoteToASCII("héllo")} ${strconv.QuoteRune('☺')} ${strconv.Unquote("\"a\\tb\"")}")
    for input <- Seq("12x", "99999999999999999999") do
      try strconv.ParseInt(input, 10, 64)
      catch case e: GoException =>
        e.error match
          case n: strconv.NumError =>
            println(s"NumError: ${n.Func} ${n.Num} syntax ${errors.Is(n, strconv.ErrSyntax)} range ${errors.Is(e.error, strconv.ErrRange)}")
          case other => println(s"other error $other")
    val appended = strconv.AppendInt(bytesOf("n="), 42, 10)
    println(s"Append: ${text(strconv.AppendQuote(appended, " q"))}")

    // bytes.
    println(s"Equal: ${bytes.Equal(bytesOf("ab"), bytesOf("ab"))} Compare: ${bytes.Compare(bytesOf("a"), bytesOf("b"))}")
    println(s"Fields: ${bytes.Fields(bytesOf(" x  yy z ")).iterator.map(text).mkString("|")} ToUpper: ${text(bytes.ToUpper(bytesOf("abc")))}")
    val buf = bytes.NewBufferString("line one\nline two\n")
    println(s"Buffer: ${buf.Len()} [${buf.ReadString('\n'.toByte).trim}] ${buf.Len()}")
    println(s"Runes: ${bytes.Runes(bytesOf("é😀")).mkString(",")}")

    // unicode and utf8: runes are Ints.
    println(s"unicode: ${unicode.IsUpper('Q')} ${unicode.IsLetter('7')} ${unicode.ToUpper('ä')} ${unicode.In('世', unicode.Han, unicode.Latin)} ${unicode.Is(unicode.Greek, 'λ')}")
    println(s"utf8: ${utf8.RuneCountInString("grüße")} ${utf8.RuneLen(0x1F600)} ${utf8.DecodeRuneInString("é!")} ${utf8.ValidString("a\uD800")} ${utf8.Valid(GoSlice.of(GoByte(-1)))} ${utf8.MaxRune}")
    println(s"AppendRune: ${text(utf8.AppendRune(bytesOf("x"), 0x4E16))}")

    // sort: a Scala sort.Interface, sort.Slice on a GoSlice, Search with a Scala predicate.
    val byLength = ByLength(Array("ccc", "a", "bb", "dddd"))
    sort.Sort(byLength)
    println(s"Sort: ${byLength.words.mkString(",")} ${sort.IsSorted(byLength)}")
    sort.Sort(sort.Reverse(byLength))
    println(s"Reverse: ${byLength.words.mkString(",")}")
    val people = GoSlice.of(go"carol:35", go"alice:30", go"bob:25")
    def age(i: Long): Int = people(i.toInt).toJavaString.split(":")(1).toInt
    sort.Slice(people, (i, j) => age(i) < age(j))
    println(s"Slice: ${strs(people)}")
    val sorted = GoSlice.of(GoInt(1), GoInt(3), GoInt(5), GoInt(7))
    println(s"Search: ${sort.Search(sorted.length, i => sorted(i.toInt).toLong >= 5)} ${sort.SearchInts(sorted, 4)}")
    val ints = sort.IntSlice(5, 2, 9)
    ints.Sort()
    println(s"IntSlice: ${ints.Len()} ${ints.Search(9)}")

    // fmt: Scala Stringers and Formatters, scanning into Go pointers.
    val money = Money(1234)
    println(fmt.Sprintf("%s %v [%10s]", money, money, "right"))
    println(fmt.Sprintf("%d %+d", money, money))
    println(fmt.Sprint("a", 1, 2, "b", true) + fmt.Sprintln("x", 3).stripLineEnd + "|")
    val n = GoPtr.alloc[GoInt]
    val word = GoPtr.alloc[GoString]
    val f = GoPtr.alloc[Double]
    val count = fmt.Sscanf("42 apples 2.5", "%d %s %g", n, word, f)
    println(s"Sscanf: $count ${n.get.toLong} ${word.get} ${f.get}")
    println(text(fmt.Appendf(bytesOf(">"), "%03d", 7)))

    // errors.
    val base = errors.New("base")
    val wrapped = fmt.Errorf("layer: %w", base)
    val joined = errors.Join(base, errors.New("other"))
    println(s"errors: ${wrapped.Error()} ${errors.Is(wrapped, base)} ${errors.Unwrap(wrapped) eq base} ${errors.Unwrap(base) == null}")
    println(s"Join: ${joined.Error().replace("\n", " / ")} ${errors.Is(joined, base)}")
