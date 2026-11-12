import java.nio.charset.StandardCharsets.UTF_8

import scalago.go.*
import scalago.gostd.{base64, errors, hex, io, json, os, strings}

final case class Item(name: String, qty: Int, tags: Seq[String], note: Option[String])

class Opaque(val secret: Int)

/** scalago-gostd's encoding/json, encoding/base64 and encoding/hex (docs/_docs/libraries/gostd.md),
 *  with GoData (docs/_docs/interop/go-data.md, "Scala data for Go code").
 */
object Main:
  def bytesOf(s: String): GoSlice[GoByte] = GoSlice.fromSeq(s.getBytes(UTF_8).toSeq.map(GoByte(_)))
  def text(b: GoSlice[GoByte]): String = String(b.toSeq.map(_.toByte).toArray, UTF_8)

  def main(args: Array[String]): Unit =
    // Marshalling GoData: case classes as maps with sorted keys; None is null.
    val item = Item("pen", 2, Seq("office", "<blue>"), None)
    println(text(json.Marshal(GoData(item))))
    println(text(json.MarshalIndent(GoData(Map("b" -> 1, "a" -> List(1.5, true))), "", "  ")))
    println(text(json.Marshal(GoSlice.of(go"x", go"y"))))
    // Go code cannot see inside Scala objects: no exported fields.
    println(text(json.Marshal(Opaque(42))))
    json.NewEncoder(os.Stdout).Encode(GoData(Seq(1, 2)))

    // Unmarshalling into a GoPtr[Any]: numbers are float64, so Doubles in Scala.
    val target = GoPtr.alloc[Any]
    json.Unmarshal(bytesOf("""{"name": "ink", "qty": 3, "tags": ["a"], "price": 1.25, "ok": true, "none": null}"""), target)
    println(GoData.toScala(target.get))
    println(s"Valid: ${json.Valid(bytesOf("[1, 2]"))} ${json.Valid(bytesOf("{"))}")
    try json.Unmarshal(bytesOf("{\"a\": "), target)
    catch case e: GoException => println(s"Unmarshal failed: ${e.getMessage}")
    val number = GoPtr.alloc[GoInt]
    json.Unmarshal(bytesOf("42"), number)
    println(s"into *int: ${number.get.toLong}")
    try json.Unmarshal(bytesOf("\"text\""), number)
    catch case e: GoException => println(s"type error: ${e.getMessage}")

    // A stream of values, until io.EOF.
    val decoder = json.NewDecoder(strings.NewReader("""{"n": 1} {"n": 2} [3]"""))
    val values = collection.mutable.ListBuffer.empty[Any]
    try
      while true do
        val v = GoPtr.alloc[Any]
        decoder.Decode(v)
        values += GoData.toScala(v.get)
    catch case e: GoException if errors.Is(e.error, io.EOF) => ()
    println(s"stream: ${values.mkString(" ")}")

    // base64 and hex.
    val data = bytesOf("hello, gö?>")
    println(s"base64: ${base64.StdEncoding.EncodeToString(data)} ${base64.URLEncoding.EncodeToString(data)} ${base64.RawStdEncoding.EncodeToString(bytesOf("a"))}")
    println(s"decoded: ${text(base64.StdEncoding.DecodeString("aGVsbG8="))}")
    try base64.StdEncoding.DecodeString("a!==")
    catch case e: GoException => println(s"corrupt: ${e.error.isInstanceOf[base64.CorruptInputError]} ${e.getMessage}")
    println(s"hex: ${hex.EncodeToString(bytesOf("Go!"))} ${text(hex.DecodeString("476f21"))}")
    try hex.DecodeString("zz")
    catch case e: GoException => println(s"invalid: ${e.error.isInstanceOf[hex.InvalidByteError]} ${e.getMessage}")
    try hex.DecodeString("abc")
    catch case e: GoException => println(s"odd length: ${e.error eq hex.ErrLength} ${e.getMessage}")
    print(hex.Dump(bytesOf("0123456789abcdefXYZ")))
