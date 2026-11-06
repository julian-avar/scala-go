import scalago.go.*

/** Go slices, maps, channels and pointers used from Scala alone: the same program runs on the
 *  JVM (with the library's JVM implementation) and as Go.
 */
object Main:
  def main(args: Array[String]): Unit =
    val xs = GoSlice.of[Long](3, 1, 2)
    println(s"slice: ${xs.mkString(",")} length=${xs.length}")
    xs(0) = 30
    val tail = xs.slice(1, 3)
    tail(0) = 10 // shares memory with xs
    println(s"after writes: ${xs.mkString(",")}; tail ${tail.mkString(",")}")
    val grown = xs.appended(4).appended(5)
    println(s"appended: ${grown.mkString(",")} (xs still ${xs.length})")
    println(s"sum: ${grown.iterator.sum}, as Seq: ${grown.toSeq}")

    val bytes = GoSlice.make[GoByte](3)
    bytes(0) = GoByte(-1)
    println(s"bytes: ${bytes(0).toInt} ${bytes(1).toInt}")

    val words = GoSlice.of(GoString("go"), GoString("scala"))
    println(s"GoStrings: ${words.iterator.map(_.toJavaString).mkString(" ")}")

    val dst = GoSlice.make[Int](2)
    val copied = dst.copyFrom(GoSlice.of(7, 8, 9))
    println(s"copied $copied: ${dst.mkString(",")}")

    val outOfRange =
      try
        xs(10)
        "no exception"
      catch case e: ArrayIndexOutOfBoundsException => e.getMessage
    println(s"out of range: $outOfRange")

    val counts = GoMap.make[GoString, GoInt]
    for w <- "the cat and the hat".split(" ") do
      val key = GoString(w)
      counts(key) = GoInt(counts.getOrElse(key, GoInt(0)).toLong + 1)
    val sorted = counts.iterator.map((k, v) => s"${k.toJavaString}=${v.toLong}").toSeq.sorted
    println(s"map: ${sorted.mkString(" ")} size=${counts.size}")
    counts.remove(GoString("the"))
    println(s"after remove: contains the=${counts.contains(GoString("the"))} cat=${counts.get(GoString("cat")).map(_.toLong)}")

    val nested = GoMap.make[Int, GoSlice[GoString]]
    nested(1) = GoSlice.of(GoString("one"))
    println(s"nested: ${nested(1)(0).toJavaString}")

    val ch = GoChan.make[Int]
    val producer = new Thread(() =>
      for i <- 1 to 5 do ch.send(i * i)
      ch.close()
    )
    producer.start()
    println(s"channel: ${ch.iterator.toList}")
    producer.join()
    println(s"closed: ${ch.receive()}")

    val buffered = GoChan.make[GoString](2)
    println(s"trySend: ${buffered.trySend(GoString("a"))} ${buffered.trySend(GoString("b"))} ${buffered.trySend(GoString("c"))}")
    println(s"buffered: length=${buffered.length} capacity=${buffered.capacity} first=${buffered.tryReceive().map(_.toJavaString)}")

    val p = GoPtr.to[Double](1.5)
    p.set(p.get * 2)
    println(s"pointer: ${p.get}")
    val counter = GoPtr.alloc[GoInt]
    println(s"zero: ${counter.get.toLong}")
