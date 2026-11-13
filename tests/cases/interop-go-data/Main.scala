import scalago.go.*

// Go data semantics that interop-go-data-basics does not cover (docs/_docs/spec/interop.md,
// "Go data"; docs/_docs/interop/go-data.md): the same results on the JVM and as Go.
object Main:
  def attempt(what: String)(body: => Any): Unit =
    val result =
      try String.valueOf(body)
      catch case e: Exception => s"throws ${e.getClass.getSimpleName}"
    println(s"$what: $result")

  def main(args: Array[String]): Unit =
    // Go types of the GoType instances.
    println(Seq(GoType.of[Int].name, GoType.of[Long].name, GoType.of[GoInt].name, GoType.of[GoByte].name, GoType.of[Char].name,
      GoType.of[GoString].name, GoType.of[GoSlice[GoString]].name, GoType.of[GoMap[GoString, GoSlice[Int]]].name,
      GoType.of[GoChan[Double]].name, GoType.of[GoPtr[Boolean]].name).mkString(" "))
    println(Seq(GoType.of[Any].name, GoType.of[GoMap[GoString, Any]].name, GoType.of[GoSlice[Any]].name).mkString(" "))

    // Slices: length and capacity; append reuses the array while the capacity allows.
    val s = GoSlice.make[Int](2, 4)
    println(s"make: length ${s.length} capacity ${s.capacity} ${s.mkString(",")} empty ${GoSlice.empty[Long].isEmpty}")
    val a = s.appended(1)
    val b = s.appended(2) // same array: overwrites a(2)
    println(s"append within capacity: a=${a.mkString(",")} b=${b.mkString(",")}")
    val full = GoSlice.of(1, 2)
    val c = full.appended(3)
    c(0) = 10
    println(s"append beyond capacity copies: ${full.mkString(",")} / ${c.mkString(",")}")
    println(s"appendedAll: ${GoSlice.of(1L).appendedAll(GoSlice.of(2L, 3L)).mkString(",")}")
    val window = GoSlice.of(1, 2, 3, 4).slice(1, 3)
    println(s"slice: ${window.mkString(",")} capacity ${window.capacity}; reslice within capacity: ${window.slice(0, 3).mkString(",")}")
    attempt("index -1")(window(-1))
    attempt("write out of range")(window(2) = 0)
    val copied = GoSlice.make[Int](5).copyFrom(GoSlice.of(1, 2))
    println(s"copy: $copied")
    val slice: AnyRef = GoSlice.make[Int](1).asInstanceOf[AnyRef]
    val map: AnyRef = GoMap.make[Int, Int].asInstanceOf[AnyRef]
    println(s"eq: ${slice eq slice} ${map eq map} ${slice eq GoSlice.make[Int](1).asInstanceOf[AnyRef]} ${slice ne slice}")
    println(s"toSeq/toArray: ${GoSlice.of(go"a", go"b").toSeq} ${GoSlice.of(1.5, 2.5).toArray.toSeq}")

    // Unsigned values are kept bit for bit.
    println(s"uint64: ${GoSlice.of(GoUint64(-1L))(0).toLong} uint32: ${GoSlice.of(GoUint32(-1))(0).toInt} byte: ${GoSlice.of(GoByte(-1))(0).toInt} ${GoSlice.of(GoByte(-1))(0).toByte}")

    // Maps: Go's == on keys.
    val m = GoMap.make[Double, Int]
    m(Double.NaN) = 1
    m(0.0) = 2
    m(-0.0) = 3
    println(s"NaN key: ${m.get(Double.NaN)} size ${m.size}; -0.0 is 0.0: ${m.get(0.0)}")
    val words = GoMap.of(go"a" -> 1, go"b" -> 2)
    println(s"apply: ${words(go"a")} getOrElse: ${words.getOrElse(go"z", -1)} contains: ${words.contains(go"b")}")
    attempt("missing key")(words(go"z"))
    words.remove(go"z") // deleting a missing key is a no-op
    println(s"toMap: ${words.toMap.toSeq.sortBy(_._1.toJavaString)} keys: ${words.keys.map(_.toJavaString).toSeq.sorted}")
    val nested = GoMap.make[GoString, GoMap[GoString, Long]]
    nested(go"inner") = GoMap.of(go"x" -> 1L)
    nested(go"inner")(go"y") = 2L // the inner map is shared
    println(s"nested: ${nested(go"inner").size}")

    // Channels.
    attempt("negative capacity")(GoChan.make[Int](-1))
    val unbuffered = GoChan.make[Int]
    println(s"unbuffered: trySend ${unbuffered.trySend(1)} tryReceive ${unbuffered.tryReceive()} capacity ${unbuffered.capacity}")
    val ch = GoChan.make[GoString](2)
    ch.send(go"x")
    println(s"buffered: length ${ch.length} tryReceive ${ch.tryReceive()} then ${ch.tryReceive()}")
    ch.send(go"y")
    ch.close()
    println(s"closed: receive ${ch.receive()} then ${ch.receive()} tryReceive ${ch.tryReceive()}")
    attempt("close twice")(try ch.close() catch case e: RuntimeException => throw RuntimeException(e))
    attempt("send on closed")(try ch.send(go"z") catch case e: RuntimeException => throw RuntimeException(e))
    attempt("trySend on closed")(try ch.trySend(go"z") catch case e: RuntimeException => throw RuntimeException(e))
    val numbers = GoChan.make[Long](3)
    for i <- 1L to 3L do numbers.send(i)
    numbers.close()
    var total = 0L
    for n <- numbers do total += n
    println(s"for over a channel: $total")

    // A blocked receive is interrupted by Thread.interrupt.
    val never = GoChan.make[Int]
    val receiver = Thread(() =>
      try println(s"received ${never.receive()}")
      catch case _: InterruptedException => println("receive interrupted")
    )
    receiver.start()
    Thread.sleep(50)
    receiver.interrupt()
    receiver.join()

    // A send happens before the receive completes: values are published to the receiver.
    final class Box(var value: String)
    val boxes = GoChan.make[Any]
    val producer = Thread(() => for i <- 1 to 3 do boxes.send(Box(s"box $i")))
    producer.start()
    println(List.fill(3)(boxes.receive().get.asInstanceOf[Box].value).mkString(", "))
    producer.join()

    // Pointers.
    val p = GoPtr.to(GoString("a"))
    val q = GoPtr.alloc[GoString]
    q.set(p.get.concat(go"b"))
    println(s"pointers: ${p.get} ${q.get} zero: [${GoPtr.alloc[GoString].get}] ${GoPtr.alloc[Boolean].get} ${GoPtr.alloc[Double].get}")
    val nilPtr = GoPtr.alloc[GoPtr[Long]].get
    println(s"nil pointer: isNil ${nilPtr.isNil}")
    attempt("get through nil")(nilPtr.get)
    val ptrToSlice = GoPtr.to(GoSlice.of(1, 2))
    ptrToSlice.set(ptrToSlice.get.appended(3))
    println(s"pointer to a slice: ${ptrToSlice.get.mkString(",")}")
    attempt("null slice")(null.asInstanceOf[GoSlice[Int]].length)

    // GoData: Option, Char, nested maps keyed by toString; toScala sorts keys.
    println(GoData.toScala(GoData(Map(2 -> Some('x'), 1 -> None, 10 -> Map("k" -> Array(1.5f))))))
    println(GoData.toScala(GoData(List(Left(1), Right("r")))))
