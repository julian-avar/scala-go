import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.{CharacterCodingException, Charset, CoderResult, CodingErrorAction, StandardCharsets}
import java.util.HexFormat

// java.nio.charset's coders used directly (Charset.encode/decode, CharsetEncoder/Decoder with
// REPORT, REPLACE and IGNORE, streaming in small buffers, CoderResults), and java.util.HexFormat.
object Main:
  def attempt(label: String)(body: => Any): Unit =
    val r =
      try String.valueOf(body)
      catch case e: Exception => s"${e.getClass.getName}: ${e.getMessage}"
    println(s"$label: $r")

  def hex(bb: ByteBuffer): String =
    val b = new Array[Byte](bb.remaining)
    bb.duplicate().get(b)
    b.map(x => f"${x & 0xff}%02x").mkString(" ")

  def codes(cb: CharBuffer): String = cb.toString.map(c => Integer.toHexString(c)).mkString(",")

  val charsets: Seq[Charset] = Seq(StandardCharsets.UTF_8, StandardCharsets.ISO_8859_1, StandardCharsets.US_ASCII)

  def main(args: Array[String]): Unit =
    charsetBasics()
    encoders()
    decoders()
    streaming()
    hexFormat()

  def charsetBasics(): Unit =
    println("== Charset")
    for cs <- charsets do
      println(s"${cs.name} ${cs.displayName} ${cs.canEncode} ${cs.isRegistered} contains UTF-8 ${cs.contains(StandardCharsets.UTF_8)} contains ASCII ${cs.contains(StandardCharsets.US_ASCII)}")
      println(s"  encode ${hex(cs.encode("aé€😀"))} decode ${codes(cs.decode(ByteBuffer.wrap(Array[Byte](0x61, 0xe9.toByte, 0xc3.toByte, 0xa9.toByte))))}")
      val enc = cs.newEncoder()
      val dec = cs.newDecoder()
      println(s"  encoder ${enc.charset} ${enc.averageBytesPerChar} ${enc.maxBytesPerChar} ${enc.replacement.mkString(",")} ${enc.malformedInputAction} ${enc.unmappableCharacterAction}")
      println(s"  decoder ${dec.charset} ${dec.averageCharsPerByte} ${dec.maxCharsPerByte} ${codes(CharBuffer.wrap(dec.replacement))} ${dec.malformedInputAction}")
      println(s"  canEncode ${enc.canEncode('a')} ${enc.canEncode('é')} ${enc.canEncode('€')} ${enc.canEncode('\ud83d')} ${enc.canEncode("a😀")} ${enc.canEncode("\ud83d")}")
    println(s"forName ${Charset.forName("UTF-8") == StandardCharsets.UTF_8} ${Charset.forName("iso-8859-1")} ${Charset.forName("ascii")} ${StandardCharsets.UTF_8.compareTo(StandardCharsets.US_ASCII) > 0}")
    attempt("forName illegal name")(Charset.forName("bad name!"))
    attempt("forName(null)")(Charset.forName(null))

  def encoders(): Unit =
    println("== CharsetEncoder")
    val text = "aé€😀\ud800z"
    for cs <- charsets; action <- Seq(CodingErrorAction.REPORT, CodingErrorAction.REPLACE, CodingErrorAction.IGNORE) do
      val enc = cs.newEncoder().onMalformedInput(action).onUnmappableCharacter(action)
      attempt(s"${cs.name} $action")(hex(enc.encode(CharBuffer.wrap(text))))
    val custom = StandardCharsets.US_ASCII.newEncoder().onUnmappableCharacter(CodingErrorAction.REPLACE).replaceWith(Array[Byte]('#'))
    println(s"replaceWith ${hex(custom.encode(CharBuffer.wrap("héllo")))} legal ${custom.isLegalReplacement(Array[Byte]('#'))}")
    attempt("illegal replacement")(StandardCharsets.US_ASCII.newEncoder().replaceWith(Array[Byte](0x80.toByte)))
    try StandardCharsets.ISO_8859_1.newEncoder().encode(CharBuffer.wrap("ok€"))
    catch case e: CharacterCodingException => println(s"caught ${e.getClass.getName}: ${e.getMessage}")

  def decoders(): Unit =
    println("== CharsetDecoder")
    val bytes = Array[Byte](0x61, 0xc3.toByte, 0xa9.toByte, 0xff.toByte, 0xe2.toByte, 0x82.toByte, 0x62, 0x80.toByte, 0xf0.toByte, 0x9f.toByte, 0x98.toByte, 0x80.toByte)
    for cs <- charsets; action <- Seq(CodingErrorAction.REPORT, CodingErrorAction.REPLACE, CodingErrorAction.IGNORE) do
      val dec = cs.newDecoder().onMalformedInput(action).onUnmappableCharacter(action)
      attempt(s"${cs.name} $action")(codes(dec.decode(ByteBuffer.wrap(bytes))))
    val custom = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).replaceWith("?")
    println(s"replaceWith ${custom.decode(ByteBuffer.wrap(Array[Byte](0x41, 0xff.toByte, 0x42)))}")
    // Overlong encodings and code points above U+10FFFF are malformed (encoded surrogates: known-bugs-jdk).
    for bad <- Seq(Array(0xc0, 0x80), Array(0xe0, 0x80, 0x80), Array(0xf4, 0x90, 0x80, 0x80), Array(0xf8, 0x88, 0x80, 0x80, 0x80)) do
      val dec = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
      println(s"${bad.map(Integer.toHexString).mkString(" ")} -> ${codes(dec.decode(ByteBuffer.wrap(bad.map(_.toByte))))}")

  def streaming(): Unit =
    println("== streaming")
    // Encode into a 3-byte buffer at a time: overflow results, nothing split inside a character.
    val enc = StandardCharsets.UTF_8.newEncoder()
    val in = CharBuffer.wrap("añ€😀!")
    val chunks = Seq.newBuilder[String]
    var done = false
    while !done do
      val out = ByteBuffer.allocate(4)
      val r = enc.encode(in, out, true)
      out.flip()
      chunks += s"${hex(out)} ($r)"
      if r.isUnderflow then done = true
    enc.flush(ByteBuffer.allocate(4))
    println(chunks.result().mkString(" | "))
    // Decode one byte at a time: a split character waits for the rest of its bytes.
    val dec = StandardCharsets.UTF_8.newDecoder()
    val src = "x€😀".getBytes(StandardCharsets.UTF_8)
    val out = CharBuffer.allocate(8)
    val results = Seq.newBuilder[String]
    val pending = ByteBuffer.allocate(8)
    for b <- src do
      pending.put(b)
      pending.flip()
      val r = dec.decode(pending, out, false)
      results += s"${r.isUnderflow}/${out.position}"
      pending.compact()
    pending.flip()
    val last = dec.decode(pending, out, true)
    dec.flush(out)
    out.flip()
    println(s"${results.result().mkString(" ")} end ${last.isUnderflow} -> ${codes(out)}")
    // A truncated character at the end of input is malformed.
    val truncated = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Array[Byte](0x61, 0xe2.toByte, 0x82.toByte)), CharBuffer.allocate(8), true)
    println(s"truncated: ${truncated.isMalformed} ${truncated.length} $truncated")
    println(s"CoderResult ${CoderResult.UNDERFLOW} ${CoderResult.OVERFLOW} ${CoderResult.malformedForLength(2)} ${CoderResult.unmappableForLength(1).isUnmappable} ${CoderResult.OVERFLOW.isError}")
    attempt("UNDERFLOW.length")(CoderResult.UNDERFLOW.length)
    attempt("malformed throwException")(CoderResult.malformedForLength(1).throwException())
    attempt("unmappable throwException")(CoderResult.unmappableForLength(2).throwException())

  def hexFormat(): Unit =
    println("== HexFormat")
    val bytes = Array[Byte](0, 1, 0x7f, 0x80.toByte, 0xab.toByte, 0xff.toByte)
    val hf = HexFormat.of()
    println(s"${hf.formatHex(bytes)} ${hf.withUpperCase().formatHex(bytes)} ${hf.formatHex(bytes, 2, 4)} [${hf.formatHex(Array[Byte]())}]")
    val fancy = HexFormat.ofDelimiter(", ").withPrefix("0x").withSuffix("h").withUpperCase()
    println(s"${fancy.formatHex(bytes)} | ${fancy.delimiter}|${fancy.prefix}|${fancy.suffix}|${fancy.isUpperCase} ${fancy.withLowerCase().isUpperCase}")
    println(s"${fancy.parseHex("0x0Ah, 0xffh").mkString(",")} ${hf.parseHex("00ff7F").mkString(",")} ${hf.parseHex("").length} ${hf.parseHex("xx0102yy", 2, 6).mkString(",")} ${hf.parseHex("0a0b".toCharArray, 2, 4).mkString(",")}")
    println(s"${hf.formatHex(new java.lang.StringBuilder("> "), bytes, 0, 2)} ${hf.toHexDigits(0xab.toByte)} ${hf.toHexDigits('A')} ${hf.toHexDigits((-1).toShort)} ${hf.toHexDigits(255)} ${hf.toHexDigits(-2L)} ${hf.toHexDigits(0x1234L, 3)}")
    println(s"${hf.toLowHexDigit(0xab)} ${hf.toHighHexDigit(0xab)} ${hf.withUpperCase().toHexDigits(new java.lang.StringBuilder(), 0xcd.toByte)}")
    println(s"${HexFormat.isHexDigit('f')} ${HexFormat.isHexDigit('G')} ${HexFormat.fromHexDigit('B')} ${HexFormat.fromHexDigits("7fffffff")} ${HexFormat.fromHexDigits("ffffffff")} ${HexFormat.fromHexDigits("x12y", 1, 3)} ${HexFormat.fromHexDigitsToLong("ffffffffffffffff")} ${HexFormat.fromHexDigitsToLong("123")}")
    println(s"$hf | $fancy")
    attempt("parseHex odd length")(hf.parseHex("abc"))
    attempt("parseHex bad digit")(hf.parseHex("zz"))
    attempt("parseHex missing delimiter")(HexFormat.ofDelimiter(":").parseHex("aabb"))
    attempt("fromHexDigit('g')")(HexFormat.fromHexDigit('g'))
    attempt("fromHexDigits too long")(HexFormat.fromHexDigits("123456789"))
    attempt("toHexDigits(1L, 17)")(hf.toHexDigits(1L, 17))
