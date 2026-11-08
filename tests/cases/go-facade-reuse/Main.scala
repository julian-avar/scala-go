import java.nio.charset.StandardCharsets.UTF_8

import gofacades.{base, meter}
import scalago.go.*
import scalago.gostd.{io, strings, time}
import textfacades.text

/** A Go io.Reader implemented in Scala, with scalago-gostd's facade, which the generated facades
 *  take as theirs.
 */
final class Letters(s: String) extends io.Reader:
  private var done = false

  def Read(p: GoSlice[GoByte]): Long =
    if done then throw GoException(io.EOF)
    done = true
    val bytes = s.getBytes(UTF_8)
    for i <- bytes.indices do p(i) = GoByte(bytes(i))
    bytes.length

object Main:
  private def asString(bytes: GoSlice[GoByte]): String =
    String(Array.tabulate(bytes.length)(i => bytes(i).toByte), UTF_8)

  def main(args: Array[String]): Unit =
    // Facades generated for package text (facades-textfacades.txt) take and return scalago-gostd's
    // types: a strings.Reader is an io.Reader, and so is the generated text.Upper.
    val upper = text.NewUpper(strings.NewReader("hello, reuse"))
    println(asString(io.ReadAll(upper)))
    println(upper.Count())
    println(asString(io.ReadAll(text.NewUpper(Letters("scala reader")))))
    val b = new strings.Builder
    text.Fill(b, "ab", 3)
    println(b.String())
    println(text.Elapsed(time.Duration(1500L * 1000000L)))

    // Facades generated for package meter (facades.txt) reuse those of package text, of its type
    // base.Named, and scalago-gostd's.
    val wrapped: text.Upper = meter.Wrap(strings.NewReader("abc"))
    println(text.Describe(wrapped))
    println(meter.Measure(wrapped))
    println(wrapped.Count())
    // base.Unit is generated with meter's facades, and is the base.Named of text's facades.
    val unit: base.Unit = meter.Of("km")
    println(text.Describe(unit))
    val reading = meter.Take(Letters("12345"), time.Second)
    println(reading.String())
    reading.Unit = base.Unit("kB")
    println(s"${reading.Value} ${reading.Unit.Name()} ${text.Elapsed(reading.Took)}")
