import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.zip.*

// Compressed bytes are not printed: Go's compress/flate does not produce zlib's bytes. The test
// checks round trips, sizes of uncompressed data, checksums, metadata, and the decompression of
// data the JDK compressed (embedded below, made with JDK 25 in UTC).
object Main:
  def bytes(hex: String): Array[Byte] = java.util.HexFormat.of().parseHex(hex)

  val jdkGzip = bytes(
    "1f8b08000000000000fff348cdc9c957482bcacf5528c94855f072f1562f56a8cac94cd253f018951ad95221407e6a5e" +
    "8a1e17009c3eb58d250200001f8b08000000000000ff2b4e4dcecf4b51c84dcd4d4a2de2020036184b0e0e000000")
  val jdkZlib = bytes(
    "78daf348cdc9c957482bcacf5528c94855f072f1562f56a8cac94cd253f018951ad95221407e6a5e8a1e1700fbccb3cd")
  val jdkRawDict = bytes(
    "f3c010512f56a8cac94cd25318951ae15221407e6a5e8a1e1700")
  val jdkZlibDict = bytes(
    "78bb3d3c0623f3c010512f56a8cac94cd25318951ae15221407e6a5e8a1e1700fbccb3cd")
  val jdkZip = bytes(
    "504b0304140008080800aab16e57000000000000000000000000040000006469722f0300504b07080000000002000000" +
    "00000000504b0304140008080800abb16e570000000000000000000000000d0000006469722f68656c6c6f2e747874f3" +
    "48cdc9c957482bcacf5528c94855f072f1562f56a8cac94cd253f018951ad95221407e6a5e8a1e1700504b07089c3eb5" +
    "8d2a00000025020000504b03040a00000800000015ee4a853e1d8264000000640000000a00000073746f7265642e6269" +
    "6e00070e151c232a31383f464d545b626970777e858c939aa1a8afb6bdc4cbd2d9e0e7eef5fc030a11181f262d343b42" +
    "4950575e656c737a81888f969da4abb2b9c0c7ced5dce3eaf1f8ff060d141b222930373e454c535a61686f767d848b92" +
    "99a0a7aeb5504b030414000808080054632d5100000000000000000000000017000f006772c3bcc39f652fc3bc6ec3af" +
    "63c3b664c3a92e747874555405000100105e5ffeca02002a2b3bbc27eff0fae4c3db520eafe40200504b070880bf6f3e" +
    "0f0000000c000000504b01021400140008080800aab16e57000000000200000000000000040000000000000000000000" +
    "0000000000006469722f504b01021400140008080800abb16e579c3eb58d2a000000250200000d000000080000000000" +
    "00000000340000006469722f68656c6c6f2e7478746465666c61746564504b01020a000a00000800000015ee4a853e1d" +
    "8264000000640000000a000000000000000000000000009900000073746f7265642e62696e504b010214001400080808" +
    "0054632d5180bf6f3e0f0000000c00000017000f0000000000000000000000250100006772c3bcc39f652fc3bc6ec3af" +
    "63c3b664c3a92e747874555405000100105e5ffeca02002a2b504b0506000000000400040001010000880100000f006d" +
    "61646520627920746865204a444b")

  val text = "Hello from the JDK's zlib. " * 20 + "The end.\n"

  /** Compressible data with some noise. */
  def sample(n: Int, seed: Long): Array[Byte] =
    val r = new java.util.Random(seed)
    val words = Array("alpha ", "beta ", "gamma ", "delta\n", "epsilon ")
    val out = new ByteArrayOutputStream()
    while out.size() < n do
      out.write(words(r.nextInt(words.length)).getBytes)
      if r.nextInt(10) == 0 then out.write(r.nextInt(256))
    java.util.Arrays.copyOf(out.toByteArray(), n)

  def attempt(label: String)(body: => Any): Unit =
    try println(s"$label: $body")
    catch case e: Exception => println(s"$label: $e")

  /** For exceptions whose class depends on the JDK's version (JDK 25 changed ended deflaters' and
   *  inflaters' from NullPointerException to IllegalStateException).
   */
  def message(label: String)(body: => Any): Unit =
    try println(s"$label: $body")
    catch case e: RuntimeException => println(s"$label: ${e.getMessage}")

  def hex(v: Long): String = java.lang.Long.toHexString(v)

  def checksums(): Unit =
    val digits = "123456789".getBytes
    for c <- List(new CRC32, new CRC32C, new Adler32) do
      c.update(digits)
      val whole = c.getValue
      c.reset()
      c.update(digits, 0, 4)
      c.update(digits(4))
      c.update(ByteBuffer.wrap(digits, 5, 2))
      val direct = ByteBuffer.allocateDirect(2)
      direct.put(digits, 7, 2).flip()
      c.update(direct)
      println(s"${c.getClass.getSimpleName}: ${hex(whole)} ${hex(c.getValue)} ${direct.remaining}")
      c.reset()
      println(s"  reset: ${hex(c.getValue)}")
      attempt("  range")(c.update(digits, 5, 5))
    val big = sample(1 << 20, 1)
    val crc = new CRC32
    crc.update(big)
    val adler = new Adler32
    adler.update(big)
    println(s"large: ${hex(crc.getValue)} ${hex(adler.getValue)}")
    val checked = new CheckedInputStream(new ByteArrayInputStream(big), new CRC32)
    checked.read()
    checked.skip(1000)
    checked.readAllBytes()
    println(s"checked input: ${checked.getChecksum.getValue == crc.getValue}")
    val sink = new CheckedOutputStream(OutputStream.nullOutputStream(), new Adler32)
    sink.write(big, 0, 10)
    sink.write(big(10))
    sink.write(big, 11, big.length - 11)
    println(s"checked output: ${sink.getChecksum.getValue == adler.getValue}")

  /** Deflates in pieces of `in` bytes into buffers of `out` bytes. */
  def deflate(d: Deflater, data: Array[Byte], in: Int, out: Int, flush: Int): Array[Byte] =
    val result = new ByteArrayOutputStream()
    val buf = new Array[Byte](out)
    var off = 0
    while off < data.length do
      val n = Math.min(in, data.length - off)
      d.setInput(data, off, n)
      off += n
      while !d.needsInput() do result.write(buf, 0, d.deflate(buf, 0, buf.length, Deflater.NO_FLUSH))
      if flush != Deflater.NO_FLUSH then
        var m = d.deflate(buf, 0, buf.length, flush)
        while m > 0 do
          result.write(buf, 0, m)
          m = d.deflate(buf, 0, buf.length, flush)
    d.finish()
    while !d.finished() do result.write(buf, 0, d.deflate(buf))
    result.toByteArray()

  /** Inflates input given in pieces of `in` bytes into buffers of `out` bytes. */
  def inflate(i: Inflater, data: Array[Byte], in: Int, out: Int): Array[Byte] =
    val result = new ByteArrayOutputStream()
    val buf = new Array[Byte](out)
    var off = 0
    while !i.finished() do
      val n = i.inflate(buf)
      result.write(buf, 0, n)
      if n == 0 && i.needsInput() then
        if off >= data.length then throw new EOFException("more input needed")
        val len = Math.min(in, data.length - off)
        i.setInput(data, off, len)
        off += len
    result.toByteArray()

  def deflaterInflater(): Unit =
    val data = sample(300000, 2)
    val adler = new Adler32
    adler.update(data)
    println(s"data: ${data.length} bytes, adler ${hex(adler.getValue)}")
    for nowrap <- List(false, true); level <- List(-1, 0, 1, 6, 9); flush <- List(0, 2, 3) do
      val d = new Deflater(level, nowrap)
      val compressed = deflate(d, data, 70000, 4096, flush)
      val i = new Inflater(nowrap)
      val back = inflate(i, compressed, 1000, 777)
      println(s"nowrap=$nowrap level=$level flush=$flush: ${java.util.Arrays.equals(back, data)} " +
        s"read ${d.getBytesRead} ${d.getTotalIn} written=${d.getBytesWritten == compressed.length} " +
        s"inflated ${i.getBytesWritten} ${i.getTotalOut} consumed=${i.getBytesRead == compressed.length} " +
        s"adler ${hex(d.getAdler & 0xffffffffL)} ${hex(i.getAdler & 0xffffffffL)} remaining ${i.getRemaining}")
      d.end()
      i.end()

    // States, step by step.
    val d = new Deflater()
    println(s"new: needsInput=${d.needsInput} finished=${d.finished} adler=${d.getAdler} raw ${new Deflater(1, true).getAdler} ${new Inflater(true).getAdler}")
    d.setInput("abcabcabc".getBytes)
    println(s"input set: needsInput=${d.needsInput}")
    val buf = new Array[Byte](100)
    var n = d.deflate(buf)
    println(s"deflated: needsInput=${d.needsInput} finished=${d.finished} read=${d.getBytesRead}")
    d.finish()
    n += d.deflate(buf, n, buf.length - n)
    println(s"finished=${d.finished} needsInput=${d.needsInput} adler=${hex(d.getAdler)}")
    println(s"more: ${d.deflate(buf)}")
    d.reset()
    println(s"reset: finished=${d.finished} read=${d.getBytesRead} written=${d.getBytesWritten} adler=${d.getAdler}")
    d.end()
    message("ended")(d.deflate(buf))
    message("ended adler")(d.getAdler)
    d.setInput(buf)
    println(s"ended: finished=${d.finished} needsInput=${d.needsInput}")
    attempt("level")(new Deflater(10))
    attempt("set level")(new Deflater().setLevel(-2))
    attempt("strategy")(new Deflater().setStrategy(5))
    attempt("flush")(new Deflater().deflate(buf, 0, 10, 4))
    attempt("range")(new Deflater().setInput(buf, 90, 20))

    val i = new Inflater()
    println(s"inflater: needsInput=${i.needsInput} finished=${i.finished} remaining=${i.getRemaining} ${i.inflate(buf)}")
    val withTrailer = java.util.Arrays.copyOf(buf, n + 5)
    i.setInput(withTrailer)
    val out = new Array[Byte](4)
    val first = i.inflate(out)
    println(s"first: $first finished=${i.finished} needsInput=${i.needsInput}")
    var rest = 0
    while !i.finished() do rest += i.inflate(out)
    println(s"rest: $rest finished=${i.finished} remaining=${i.getRemaining} read=${i.getBytesRead == n} " +
      s"written=${i.getBytesWritten} adler=${hex(i.getAdler)} again=${i.inflate(out)}")
    i.reset()
    println(s"reset: finished=${i.finished} read=${i.getBytesRead} adler=${i.getAdler}")
    i.end()
    message("ended")(i.inflate(out))
    i.setInput(buf)
    println(s"ended: finished=${i.finished} needsInput=${i.needsInput} remaining=${i.getRemaining}")

    // Changing the level and strategy in the middle of a stream.
    val changing = new Deflater(1)
    val bytes = new ByteArrayOutputStream()
    for (level, part) <- List(1, 9, 0, 5).zipWithIndex do
      changing.setLevel(level)
      if part == 2 then changing.setStrategy(Deflater.HUFFMAN_ONLY)
      if part == 3 then changing.setStrategy(Deflater.FILTERED)
      changing.setInput(data, part * 50000, 50000)
      while !changing.needsInput() do bytes.write(buf, 0, changing.deflate(buf))
    changing.finish()
    while !changing.finished() do bytes.write(buf, 0, changing.deflate(buf))
    println(s"levels: ${java.util.Arrays.equals(inflate(new Inflater(), bytes.toByteArray(), 4096, 4096), java.util.Arrays.copyOf(data, 200000))}")

    // Buffers.
    val bd = new Deflater()
    val input = ByteBuffer.wrap(data, 1000, 5000)
    bd.setInput(input)
    bd.finish()
    val compressed = ByteBuffer.allocate(10000)
    while !bd.finished() do bd.deflate(compressed)
    println(s"buffers: input position ${input.position} remaining ${input.remaining}")
    compressed.flip()
    val bi = new Inflater()
    bi.setInput(compressed)
    val inflated = ByteBuffer.allocateDirect(6000)
    bi.inflate(inflated)
    println(s"  ${inflated.position} finished=${bi.finished} input position=${compressed.position == compressed.limit}")

  def jdkData(): Unit =
    val i = new Inflater()
    i.setInput(jdkZlib)
    val out = new Array[Byte](1000)
    val n = i.inflate(out)
    println(s"jdk zlib: ${new String(out, 0, n, UTF_8) == text} $n finished=${i.finished} adler=${hex(i.getAdler)}")
    val raw = new Inflater(true)
    raw.setDictionary("Hello from the JDK".getBytes)
    raw.setInput(jdkRawDict)
    println(s"jdk raw with dictionary: ${new String(out, 0, raw.inflate(out), UTF_8) == text} finished=${raw.finished}")
    val dict = new Inflater()
    dict.setInput(jdkZlibDict)
    println(s"jdk zlib with dictionary: ${dict.inflate(out)} needsDictionary=${dict.needsDictionary} adler=${hex(dict.getAdler)}")
    attempt("  wrong dictionary")(dict.setDictionary("Hello".getBytes))
    dict.setDictionary("Hello from the JDK".getBytes)
    println(s"  ${new String(out, 0, dict.inflate(out), UTF_8) == text} finished=${dict.finished} adler=${hex(dict.getAdler)}")

    // Our dictionaries.
    val dd = new Deflater()
    dd.setDictionary("Hello from".getBytes)
    println(s"dictionary adler: ${hex(dd.getAdler)}")
    dd.setInput(text.getBytes)
    dd.finish()
    val withDict = new Array[Byte](1000)
    val m = dd.deflate(withDict)
    val di = new Inflater()
    di.setInput(withDict, 0, m)
    println(s"  ${di.inflate(out)} ${di.needsDictionary} ${hex(di.getAdler)}")
    di.setDictionary("Hello from".getBytes)
    println(s"  ${new String(out, 0, di.inflate(out), UTF_8) == text}")

    // Corrupt data.
    val corrupt = jdkZlib.clone()
    corrupt(corrupt.length - 1) = (corrupt(corrupt.length - 1) ^ 1).toByte
    val ci = new Inflater()
    ci.setInput(corrupt)
    attempt("bad checksum")(ci.inflate(out))
    val hi = new Inflater()
    hi.setInput(Array[Byte](1, 2, 3, 4))
    attempt("bad header")(hi.inflate(out))
    val bi = new Inflater(true)
    bi.setInput(Array[Byte](-1, -1, -1, -1))
    attempt("bad data")(try bi.inflate(out) catch case e: DataFormatException => "DataFormatException")

    // GZIP, with two members.
    val gz = new GZIPInputStream(new ByteArrayInputStream(jdkGzip))
    println(s"jdk gzip: ${new String(gz.readAllBytes(), UTF_8) == text + "second member\n"} ${gz.read()} ${gz.available}")
    val gz2 = new GZIPInputStream(new ByteArrayInputStream(jdkGzip), 7)
    val small = new Array[Byte](13)
    var total = 0
    var r = gz2.read(small)
    while r != -1 do
      total += r
      r = gz2.read(small)
    println(s"  small buffers: $total")
    gz2.close()
    attempt("  closed")(gz2.read())

  def streams(): Unit =
    val data = sample(100000, 3)
    // Deflater and inflater streams, with sync flushes: what is flushed can be read at once.
    val pipeOut = new PipedOutputStream()
    val pipeIn = new PipedInputStream(pipeOut, 1 << 16)
    val dos = new DeflaterOutputStream(pipeOut, true)
    val iis = new InflaterInputStream(pipeIn)
    for msg <- List("first message", "second one", "third") do
      dos.write(msg.getBytes)
      dos.flush()
      val got = new Array[Byte](msg.length)
      var off = 0
      while off < got.length do off += iis.read(got, off, got.length - off)
      println(s"flushed: ${new String(got)}")
    dos.close()
    println(s"end: ${iis.read()} ${iis.available}")

    val bytes = new ByteArrayOutputStream()
    val out = new DeflaterOutputStream(bytes, new Deflater(9), 100)
    out.write(data, 0, 50000)
    out.write(data(50000))
    out.write(data, 50001, data.length - 50001)
    out.finish()
    attempt("write after finish")(out.write(1))
    val in = new InflaterInputStream(new ByteArrayInputStream(bytes.toByteArray()), new Inflater(), 33)
    println(s"deflater stream: ${java.util.Arrays.equals(in.readAllBytes(), data)}")
    val skipping = new InflaterInputStream(new ByteArrayInputStream(bytes.toByteArray()))
    println(s"skip: ${skipping.skip(99990)} ${skipping.read()} ${skipping.skip(100)} ${skipping.read()}")
    val truncated = new InflaterInputStream(new ByteArrayInputStream(bytes.toByteArray(), 0, 100))
    attempt("truncated")(truncated.readAllBytes())
    attempt("not zlib")(new InflaterInputStream(new ByteArrayInputStream("not zlib".getBytes)).read())

    // GZIP.
    val gzBytes = new ByteArrayOutputStream()
    val gz = new GZIPOutputStream(gzBytes, 1000)
    gz.write(data)
    gz.close()
    val gzData = gzBytes.toByteArray()
    println(s"gzip header: ${java.util.HexFormat.of().formatHex(gzData, 0, 10)}")
    val trailer = ByteBuffer.wrap(gzData, gzData.length - 8, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    val crc = new CRC32
    crc.update(data)
    println(s"gzip trailer: ${hex(trailer.getInt() & 0xffffffffL)} ${trailer.getInt()} crc ${hex(crc.getValue)}")
    println(s"gzip: ${java.util.Arrays.equals(new GZIPInputStream(new ByteArrayInputStream(gzData)).readAllBytes(), data)}")
    // Concatenated with JDK members, and followed by garbage, which is ignored.
    val concatenated = new ByteArrayOutputStream()
    concatenated.write(gzData)
    concatenated.write(jdkGzip)
    concatenated.write("garbage after the members, long enough to look like one".getBytes)
    val all = new GZIPInputStream(new ByteArrayInputStream(concatenated.toByteArray())).readAllBytes()
    println(s"concatenated: ${all.length} ${new String(all, data.length, all.length - data.length, UTF_8).takeRight(14)}")
    attempt("not gzip")(new GZIPInputStream(new ByteArrayInputStream("not gzip at all".getBytes)))
    attempt("empty")(new GZIPInputStream(new ByteArrayInputStream(new Array[Byte](0))))
    val badCrc = gzData.clone()
    badCrc(badCrc.length - 8) = (badCrc(badCrc.length - 8) ^ 1).toByte
    attempt("bad trailer")(new GZIPInputStream(new ByteArrayInputStream(badCrc)).readAllBytes().length)
    val syncGz = new ByteArrayOutputStream()
    val sgz = new GZIPOutputStream(syncGz, true)
    sgz.write("partial".getBytes)
    sgz.flush()
    val partial = new GZIPInputStream(new ByteArrayInputStream(syncGz.toByteArray()))
    val pbuf = new Array[Byte](7)
    println(s"sync flushed gzip: ${partial.read(pbuf)} ${new String(pbuf)}")

    // The reverse streams.
    val inflated = new ByteArrayOutputStream()
    val ios = new InflaterOutputStream(inflated)
    val compressed = bytes.toByteArray()
    var off = 0
    while off < compressed.length do
      val n = Math.min(999, compressed.length - off)
      ios.write(compressed, off, n)
      off += n
    ios.close()
    println(s"inflater output stream: ${java.util.Arrays.equals(inflated.toByteArray(), data)}")
    val dis = new DeflaterInputStream(new ByteArrayInputStream(data), new Deflater(1), 4000)
    val viaDis = dis.readAllBytes()
    println(s"deflater input stream: ${java.util.Arrays.equals(new InflaterInputStream(new ByteArrayInputStream(viaDis)).readAllBytes(), data)} ${dis.read()} ${dis.available}")

  def printEntry(e: ZipEntry): Unit =
    val compressed = if e.getMethod == ZipEntry.STORED then s" csize ${e.getCompressedSize}" else ""
    println(s"  ${e.getName}: dir=${e.isDirectory} method ${e.getMethod} size ${e.getSize}$compressed crc ${hex(e.getCrc)} " +
      s"time ${e.getTime} ${e.getLastModifiedTime} ${e.getTimeLocal} comment ${e.getComment} " +
      s"extra ${Option(e.getExtra).map(java.util.HexFormat.of().formatHex).orNull}")

  def zipStreams(dir: java.nio.file.Path): Unit =
    println("jdk zip, streamed:")
    val zin = new ZipInputStream(new ByteArrayInputStream(jdkZip))
    var e = zin.getNextEntry
    while e != null do
      printEntry(e)
      val content = zin.readAllBytes()
      println(s"  -> ${content.length} bytes, then ${zin.available}, entry now: size ${e.getSize} crc ${hex(e.getCrc)}")
      e = zin.getNextEntry
    zin.close()

    // Writing.
    val file = dir.resolve("test.zip")
    val zout = new ZipOutputStream(new FileOutputStream(file.toFile))
    zout.setComment("made by the test")
    val data = sample(50000, 4)
    val e1 = new ZipEntry("data/sample.txt")
    e1.setTime(1700000000123L)
    e1.setComment("sampled")
    zout.putNextEntry(e1)
    zout.write(data)
    zout.closeEntry()
    println(s"after closeEntry: size ${e1.getSize} crc ${hex(e1.getCrc)} time ${e1.getTime}")
    zout.setLevel(Deflater.BEST_SPEED)
    val e2 = new ZipEntry("data/")
    e2.setTime(1700000000000L)
    zout.putNextEntry(e2)
    val stored = new ZipEntry("stored.bin")
    val storedData = Array.tabulate[Byte](300)(i => (i * 13).toByte)
    val crc = new CRC32
    crc.update(storedData)
    stored.setMethod(ZipEntry.STORED)
    stored.setSize(storedData.length)
    stored.setCrc(crc.getValue)
    stored.setTime(946684800000L)
    zout.putNextEntry(stored)
    zout.write(storedData, 0, 100)
    zout.write(storedData, 100, 200)
    val other = new ZipOutputStream(OutputStream.nullOutputStream())
    other.putNextEntry(stored.clone().asInstanceOf[ZipEntry])
    attempt("past the end")(other.write(storedData ++ Array[Byte](1)))
    attempt("duplicate")(zout.putNextEntry(new ZipEntry("data/")))
    val old = new ZipEntry("old.txt")
    old.setTime(315000000000L) // 1979: before DOS times
    zout.putNextEntry(old)
    zout.write("old".getBytes)
    val future = new ZipEntry("future.txt")
    future.setTime(4200000000000L) // 2103
    future.setLastAccessTime(FileTime.fromMillis(1000000000000L))
    zout.putNextEntry(future)
    val unicode = new ZipEntry("ünïcödé ✓.txt")
    unicode.setExtra(Array[Byte](0xfe.toByte, 0xca.toByte, 1, 0, 9))
    unicode.setTime(1700000000000L)
    zout.putNextEntry(unicode)
    zout.write("✓".getBytes(UTF_8))
    val missing = new ZipEntry("missing.bin")
    missing.setMethod(ZipEntry.STORED)
    attempt("stored without crc")(zout.putNextEntry(missing))
    zout.close()
    attempt("closed")(zout.putNextEntry(new ZipEntry("late")))
    attempt("no entry")(new ZipOutputStream(OutputStream.nullOutputStream()).write(1))

    println("ours, streamed:")
    val zin2 = new ZipInputStream(new FileInputStream(file.toFile))
    e = zin2.getNextEntry
    while e != null do
      val content = zin2.readAllBytes()
      printEntry(e)
      println(s"  -> ${content.length} bytes")
      e = zin2.getNextEntry
    zin2.close()

  def zipFiles(dir: java.nio.file.Path): Unit =
    val jdkFile = dir.resolve("jdk.zip")
    Files.write(jdkFile, jdkZip)
    for path <- List(jdkFile, dir.resolve("test.zip")) do
      val zf = new ZipFile(path.toFile)
      println(s"${path.getFileName}: ${zf.size} entries, comment ${zf.getComment}, name ${zf.getName == path.toString}")
      val it = zf.entries()
      while it.hasMoreElements do
        val e = it.nextElement()
        printEntry(e)
        val content = zf.getInputStream(e).readAllBytes()
        val crc = new CRC32
        crc.update(content)
        println(s"  -> ${content.length} bytes, crc ok ${crc.getValue == e.getCrc}")
      println(s"  stream: ${zf.stream().map(_.getName).toList}")
      println(s"  missing: ${zf.getEntry("nothing")}")
      zf.close()
      attempt("  closed")(zf.size)
    val zf = new ZipFile(jdkFile.toFile)
    println(s"getEntry dir: ${zf.getEntry("dir")}")
    val hello = zf.getEntry("dir/hello.txt")
    val a = zf.getInputStream(hello)
    val b = zf.getInputStream(hello)
    val first = new Array[Byte](5)
    a.read(first)
    println(s"two streams: ${new String(first)} ${new String(b.readNBytes(5))} ${a.available} ${new String(a.readAllBytes(), UTF_8).length}")
    val s = zf.getInputStream(zf.getEntry("stored.bin"))
    s.skip(98)
    println(s"stored: ${s.read()} ${s.read()} ${s.read()}")
    zf.close()
    attempt("read after close")(b.read())
    Files.write(dir.resolve("text.txt"), "not a zip file".getBytes)
    attempt("not a zip")(new ZipFile(dir.resolve("text.txt").toString))
    try new ZipFile(dir.resolve("absent.zip").toFile)
    catch case e: IOException => println(s"absent: ${e.getClass.getName}")

  def entries(): Unit =
    val e = new ZipEntry("a/b.txt")
    println(s"new entry: ${e.getTime} ${e.getSize} ${e.getCompressedSize} ${e.getCrc} ${e.getMethod} ${e.getLastModifiedTime} ${e.getExtra} ${e.isDirectory} $e")
    e.setTime(1234567890123L)
    println(s"time: ${e.getTime} ${e.getLastModifiedTime} ${e.getTimeLocal}")
    e.setTimeLocal(java.time.LocalDateTime.of(2001, 2, 3, 4, 5, 7, 500000000))
    println(s"local: ${e.getTime} ${e.getTimeLocal}")
    e.setLastModifiedTime(FileTime.fromMillis(1000L))
    println(s"modified: ${e.getTime} ${e.getLastModifiedTime}")
    attempt("size")(e.setSize(-2))
    attempt("crc")(e.setCrc(1L << 32))
    attempt("method")(e.setMethod(3))
    e.setExtra(Array[Byte](1, 2))
    val copy = e.clone().asInstanceOf[ZipEntry]
    copy.getExtra()(0) = 9
    println(s"clone: ${copy.getName} ${e.getExtra()(0)} ${new ZipEntry(e).getTime} ${e.hashCode == "a/b.txt".hashCode}")

  def main(args: Array[String]): Unit =
    checksums()
    deflaterInflater()
    jdkData()
    streams()
    entries()
    val dir = Files.createTempDirectory("zip-test")
    try
      zipStreams(dir)
      zipFiles(dir)
    finally
      Files.list(dir).forEach(Files.delete)
      Files.delete(dir)
