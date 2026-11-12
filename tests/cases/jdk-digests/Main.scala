import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.*
import java.util.HexFormat
import javax.crypto.{Mac, ShortBufferException}
import javax.crypto.spec.SecretKeySpec

object Main:
  val hex = HexFormat.of()

  def attempt(label: String)(body: => Any): Unit =
    try println(s"$label: ${body}")
    catch case e: Exception => println(s"$label: $e")

  def digests(): Unit =
    val inputs = List("", "abc", "The quick brown fox jumps over the lazy dog", "x" * 1000)
    for alg <- List("MD5", "SHA-1", "SHA-224", "SHA-256", "SHA-384", "SHA-512", "SHA-512/224", "SHA-512/256") do
      val md = MessageDigest.getInstance(alg)
      println(s"$alg (${md.getDigestLength}): ${inputs.map(s => hex.formatHex(md.digest(s.getBytes(UTF_8)))).mkString(" ")}")
    for name <- List("md5", "SHA", "sha1", "SHA256", "sha512/256", "OID.2.16.840.1.101.3.4.2.1", "1.3.14.3.2.26") do
      val md = MessageDigest.getInstance(name)
      println(s"$name -> ${md.getAlgorithm} ${md.getDigestLength} ${hex.formatHex(md.digest("abc".getBytes))}")
    attempt("FOO")(MessageDigest.getInstance("FOO"))
    attempt("null")(MessageDigest.getInstance(null))
    attempt("provider")(MessageDigest.getInstance("SHA-256", "SUN").getAlgorithm)

    // Incremental updates, every overload, toString, reset and clone.
    val md = MessageDigest.getInstance("SHA-256")
    print(md)
    md.update('T'.toByte)
    md.update("he quick".getBytes, 0, 8)
    md.update(" brown fox jumps ".getBytes)
    md.update(ByteBuffer.wrap("over the lazy dog".getBytes))
    print(md)
    val copy = md.clone().asInstanceOf[MessageDigest]
    print(copy)
    println(hex.formatHex(md.digest()))
    print(md)
    copy.update("!".getBytes)
    println(hex.formatHex(copy.digest()))
    md.update("garbage".getBytes)
    md.reset()
    println(hex.formatHex(md.digest("abc".getBytes)))
    val direct = ByteBuffer.allocateDirect(3)
    direct.put("abc".getBytes).flip()
    md.update(direct)
    println(s"direct buffer: ${hex.formatHex(md.digest())} remaining ${direct.remaining}")
    val out = new Array[Byte](40)
    md.update("abc".getBytes)
    println(s"into a buffer: ${md.digest(out, 4, 32)} ${hex.formatHex(out, 4, 36)}")
    attempt("short buffer")(md.digest(out, 0, 16))
    attempt("small buffer")(md.digest(out, 30, 32))
    attempt("update past the end")(md.update(out, 38, 4))
    println(MessageDigest.isEqual(md.digest("a".getBytes), MessageDigest.getInstance("SHA-256").digest("a".getBytes)))
    println(s"${MessageDigest.isEqual("a".getBytes, "b".getBytes)} ${MessageDigest.isEqual(null, null)} " +
      s"${MessageDigest.isEqual(Array[Byte](1), null)} ${MessageDigest.isEqual(Array[Byte](1), Array[Byte](1, 2))}")
    // A large input in pieces.
    val big = Array.tabulate[Byte](1 << 20)(i => (i * 31 + i / 7).toByte)
    val sha = MessageDigest.getInstance("SHA-512")
    var i = 0
    while i < big.length do
      sha.update(big, i, Math.min(4097, big.length - i))
      i += 4097
    println(s"pieces: ${hex.formatHex(sha.digest()) == hex.formatHex(MessageDigest.getInstance("SHA-512").digest(big))}")

    // Streams.
    val dis = new DigestInputStream(new ByteArrayInputStream("hello, world".getBytes), MessageDigest.getInstance("MD5"))
    val buf = new Array[Byte](5)
    dis.read()
    dis.on(false)
    dis.read(buf)
    dis.on(true)
    while dis.read(buf) != -1 do ()
    println(s"digest input: ${hex.formatHex(dis.getMessageDigest.digest())}")
    val bytes = new ByteArrayOutputStream()
    val dos = new DigestOutputStream(bytes, MessageDigest.getInstance("SHA-1"))
    dos.write("hello, ".getBytes)
    dos.write('w')
    dos.write("world".getBytes, 0, 5)
    println(s"digest output: ${hex.formatHex(dos.getMessageDigest.digest())} ${bytes}")

    // A digest written in Scala.
    class Xor extends MessageDigest("XOR"):
      private var x = 0
      protected def engineUpdate(b: Byte): Unit = x ^= b
      protected def engineUpdate(b: Array[Byte], off: Int, len: Int): Unit =
        for i <- off until off + len do x ^= b(i)
      protected def engineDigest(): Array[Byte] =
        val r = Array(x.toByte)
        x = 0
        r
      protected def engineReset(): Unit = x = 0
    val xor = new Xor
    print(xor)
    println(s"${hex.formatHex(xor.digest("abc".getBytes))} ${xor.getDigestLength}")
    attempt("clone")(xor.clone())

  def macs(): Unit =
    val key = new SecretKeySpec("key".getBytes, "HmacSHA256")
    // (The JDK's hashCode changed in JDK 25.)
    println(s"${key.getAlgorithm} ${key.getFormat} ${hex.formatHex(key.getEncoded)} ${key.hashCode == new SecretKeySpec("key".getBytes, "HMACSHA256").hashCode}")
    println(key == new SecretKeySpec("key".getBytes, "HMACSHA256"))
    println(key == new SecretKeySpec("kez".getBytes, "HmacSHA256"))
    println(hex.formatHex(new SecretKeySpec("0123456789".getBytes, 2, 3, "x").getEncoded))
    attempt("empty key")(new SecretKeySpec(new Array[Byte](0), "HmacSHA256"))
    attempt("null key")(new SecretKeySpec(null, "HmacSHA256"))
    val msg = "The quick brown fox jumps over the lazy dog".getBytes
    val longKey = ("k" * 200).getBytes
    for alg <- List("HmacMD5", "HmacSHA1", "HmacSHA224", "HmacSHA256", "HmacSHA384", "HmacSHA512", "HmacSHA512/224",
        "HmacSHA512/256", "hmacsha256")
    do
      val mac = Mac.getInstance(alg)
      mac.init(new SecretKeySpec("key".getBytes, alg))
      val a = hex.formatHex(mac.doFinal(msg))
      mac.init(new SecretKeySpec(longKey, alg))
      println(s"${mac.getAlgorithm} ${mac.getMacLength}: $a ${hex.formatHex(mac.doFinal(msg))}")
    attempt("Foo")(Mac.getInstance("Foo"))
    val mac = Mac.getInstance("HmacSHA256")
    println(mac.getMacLength)
    attempt("not initialized")(mac.doFinal())
    attempt("update not initialized")(mac.update(1.toByte))
    attempt("null key")(mac.init(null))
    mac.init(key)
    mac.update("The quick".getBytes)
    val copy = mac.clone().asInstanceOf[Mac]
    mac.update(' '.toByte)
    mac.update("brown fox jumps over the lazy dog".getBytes, 0, 33)
    println(hex.formatHex(mac.doFinal()))
    println(hex.formatHex(copy.doFinal(" brown fox jumps over the lazy dog".getBytes)))
    mac.update(ByteBuffer.wrap(msg))
    val out = new Array[Byte](40)
    mac.doFinal(out, 8)
    println(hex.formatHex(out))
    attempt("short output")(mac.doFinal(out, 20))
    attempt("bad arguments")(mac.update(out, 30, 20))
    mac.update("junk".getBytes)
    mac.reset()
    println(hex.formatHex(mac.doFinal(msg)))

  def secureRandom(): Unit =
    val r = new SecureRandom()
    println(r.getAlgorithm)
    val bytes = new Array[Byte](64)
    r.nextBytes(bytes)
    val more = new Array[Byte](64)
    r.nextBytes(more)
    println(s"distinct: ${!java.util.Arrays.equals(bytes, more)}")
    println(s"ints in range: ${(1 to 1000).forall { _ => val n = r.nextInt(10); n >= 0 && n < 10 }}")
    println(s"doubles in range: ${(1 to 1000).forall { _ => val d = r.nextDouble(); d >= 0 && d < 1 }}")
    println(s"seed: ${r.generateSeed(7).length} ${SecureRandom.getSeed(3).length}")
    for alg <- List("SHA1PRNG", "NativePRNG", "DRBG", "NativePRNGNonBlocking", "foo") do
      attempt(alg)(SecureRandom.getInstance(alg).getAlgorithm)
    println(SecureRandom.getInstanceStrong.getAlgorithm)
    val seeded = new SecureRandom("seed".getBytes)
    seeded.setSeed(42L)
    println(seeded.nextLong() != seeded.nextLong())
    val random: java.util.Random = new SecureRandom()
    println(random.ints(5, 0, 3).allMatch(i => i >= 0 && i < 3))

  def hexFormat(): Unit =
    val bytes = Array[Byte](0, 1, 0x7f, -128, -1, 0x2a)
    println(hex.formatHex(bytes))
    println(hex)
    val fancy = HexFormat.ofDelimiter(", ").withPrefix("#").withSuffix(";").withUpperCase()
    println(fancy)
    println(fancy.formatHex(bytes))
    println(fancy.formatHex(bytes, 1, 4))
    println(fancy.formatHex(new java.lang.StringBuilder("> "), bytes))
    println(java.util.Arrays.toString(fancy.parseHex(fancy.formatHex(bytes))))
    println(java.util.Arrays.toString(hex.parseHex("00ff7F80")))
    println(java.util.Arrays.toString(hex.parseHex("xx0102yy", 2, 6)))
    println(java.util.Arrays.toString(HexFormat.ofDelimiter(":").parseHex("ab:cd:ef".toCharArray, 0, 8)))
    println(hex.parseHex("").length)
    attempt("odd")(hex.parseHex("abc"))
    attempt("bad digit")(hex.parseHex("zz"))
    attempt("missing delimiter")(HexFormat.ofDelimiter(":").parseHex("ab:cdef"))
    attempt("wrong delimiter")(HexFormat.ofDelimiter(":").parseHex("ab-cd"))
    println(s"${hex.toHexDigits(0x2a.toByte)} ${hex.toHexDigits('A')} ${hex.toHexDigits((-2).toShort)}")
    println(s"${hex.toHexDigits(0xcafe)} ${hex.withUpperCase().toHexDigits(-1L)} ${hex.toHexDigits(0x1234L, 3)}")
    println(s"${hex.toHighHexDigit(0xab)}${hex.toLowHexDigit(0xab)} ${hex.toHexDigits(new java.lang.StringBuilder, 7.toByte)}")
    println(s"${HexFormat.isHexDigit('f')} ${HexFormat.isHexDigit('g')} ${HexFormat.fromHexDigit('B')}")
    println(s"${HexFormat.fromHexDigits("7fffffff")} ${HexFormat.fromHexDigits("ffffffff")} ${HexFormat.fromHexDigits("x12y", 1, 3)}")
    println(s"${HexFormat.fromHexDigitsToLong("ffffffffffffffff")} ${HexFormat.fromHexDigitsToLong("123456789")}")
    attempt("too long")(HexFormat.fromHexDigits("123456789"))
    attempt("not hex")(HexFormat.fromHexDigit('q'))
    println(s"${hex == HexFormat.of()} ${hex == hex.withUpperCase()} ${hex.withUpperCase() == HexFormat.of().withUpperCase()}")
    println(hex.withUpperCase().hashCode == HexFormat.of().withUpperCase().hashCode)
    println(s"${fancy.delimiter} ${fancy.prefix} ${fancy.suffix} ${fancy.isUpperCase} ${fancy.withLowerCase().isUpperCase}")

  def main(args: Array[String]): Unit =
    digests()
    macs()
    secureRandom()
    hexFormat()
