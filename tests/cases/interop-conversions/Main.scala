import scalago.go.*

// Values crossing between Scala and Go (docs/_docs/spec/interop.md, "Values across the boundary"),
// through a hand-written facade of the local module conv/.
@GoImport("example.com/conv")
object conv:
  def Uint8(x: Int): Int = native
  def Int8(x: Int): Int = native
  def Uint32(x: Int): Long = native
  def Uint64(x: Long): Long = native
  @GoName("Uint64") def uint64AsDouble(x: Long): Double = native
  def Int(x: Double): Long = native
  def Float32(x: Double): Double = native
  def Not(b: Boolean): Boolean = native
  def Show(x: Any): String = native
  def Bytes(s: String): String = native
  @GoName("Bytes") def goBytes(s: GoString): String = native
  def Invalid(): String = native
  @GoName("Invalid") def invalidGo(): GoString = native
  def Duration(d: Long): String = native
  def Body(): Double = native

  def NewPoint(x: Int, y: Int): Point = native
  def Shift(p: Point): Point = native
  def ShiftPtr(p: Point): Unit = native
  def GetOrigin(): Point = native
  def OriginPtr(): Point = native
  def OriginX(): Int = native

  def DoubleAll(xs: Array[Long]): Unit = native
  def DoubleInts(xs: Array[Int]): Array[Int] = native
  def IsNil(xs: Array[String]): Boolean = native
  def Join(xs: Seq[String]): String = native
  @GoName("Join") def joinArray(xs: Array[String]): String = native
  def GetShared(): Array[Long] = native
  def SharedSum(): Long = native
  def Pairs(m: Seq[(String, Int)]): String = native
  @GoName("Pairs") def pairsArray(m: Array[(String, Long)]): String = native
  def Sum(label: String, xs: Int*): String = native
  def Triple(): (Long, String, Boolean) = native

  def Big: Long = native
  def Small: Double = native
  def Name: String = native
  def Ratio: Double = native
  def Typed: Int = native
  def Counter: Int = native
  def Counter_=(x: Int): Unit = native
  def Bump(): Int = native

  def Fill(c: GoChan[Long], n: Int): Unit = native
  def Drain(c: GoChan[Long]): Long = native

  class Point:
    def X: Int = native
    def X_=(x: Int): Unit = native
    def Y: Int = native

@GoImport("time")
object time:
  def Second: Long = native

object Main:
  def attempt(what: String)(body: => Any): Unit =
    val result =
      try String.valueOf(body)
      catch case e: Exception => s"throws ${e.getClass.getName}"
    println(s"$what: $result")

  def main(args: Array[String]): Unit =
    // Numbers: Go's conversions (sign- or zero-extension, truncation, toward zero for floats).
    println(s"uint8: ${conv.Uint8(300)} ${conv.Uint8(-1)}; int8: ${conv.Int8(200)}")
    println(s"uint32: ${conv.Uint32(-1)}; uint64: ${conv.Uint64(-1L)} ${conv.uint64AsDouble(-1L)}")
    println(s"int from double: ${conv.Int(2.9)} ${conv.Int(-2.9)}")
    println(s"float32: ${conv.Float32(0.1)}; bool: ${conv.Not(true)}")
    println(s"duration: ${conv.Duration(1500000000L)} ${time.Second}; defined float: ${conv.Body()}")

    // Values passed as any: the Go types of Scala's primitives.
    val values: Seq[Any] = Seq("hi", 42, 42L, 1.5, 1.5f, true, 'x', 3.toByte, 4.toShort, null, go"gö")
    for v <- values do println(s"as any: ${conv.Show(v)}")

    // Strings: transcoded to UTF-8 and back; unpaired surrogates and invalid bytes become U+FFFD.
    println(s"bytes: ${conv.Bytes("é😀")} | ${conv.Bytes("a\uD800b")} | ${conv.goBytes(go"é")}")
    val invalid = conv.Invalid()
    println(s"invalid UTF-8: length ${invalid.length}, ${invalid.map(c => Integer.toHexString(c)).mkString(" ")}")
    println(s"invalid UTF-8 as GoString: ${conv.invalidGo().byteLength} bytes")
    attempt("null String")(conv.Bytes(null))

    // Structs: by value (a copy) and by pointer (shared).
    val p = conv.NewPoint(1, 2)
    val shifted = conv.Shift(p)
    println(s"by value: ${shifted.X} ${shifted.Y}, original ${p.X}")
    conv.ShiftPtr(p)
    println(s"by pointer: ${p.X}")
    attempt("null by value")(conv.Shift(null))
    val copy = conv.GetOrigin()
    copy.X = 50
    println(s"result by value is a copy: ${copy.X} ${conv.OriginX()}")
    conv.OriginPtr().X = 60
    println(s"result by pointer is shared: ${conv.OriginX()}")

    // Slices: Array[Long] shares []int64; Array[Int] for []int is copied.
    val longs = Array(1L, 2L, 3L)
    conv.DoubleAll(longs)
    println(s"shared: ${longs.mkString(",")}")
    val ints = Array(1, 2, 3)
    val doubled = conv.DoubleInts(ints)
    println(s"copied: ${ints.mkString(",")} -> ${doubled.mkString(",")}")
    println(s"null array is nil: ${conv.IsNil(null)} ${conv.IsNil(Array())}")
    println(s"seq: ${conv.Join(List("a", "b", "c"))}, array: ${conv.joinArray(Array("x", "ü"))}")
    val shared = conv.GetShared()
    shared(0) = 100
    println(s"result shared: ${conv.SharedSum()}")
    println(s"maps: ${conv.Pairs(Seq("b" -> 2, "a" -> 1, "b" -> 3))} | ${conv.pairsArray(Array("z" -> 26L))}")

    // Variadic parameters: literal arguments, or a slice from xs*.
    println(conv.Sum("none"))
    println(conv.Sum("literal", 1, 2, 3))
    println(conv.Sum("array", Array(4, 5)*))
    println(conv.Sum("seq", Vector(6, 7, 8)*))

    // Several results are a tuple.
    println(s"triple: ${conv.Triple()}")

    // Constants (converted to the facade's type) and variables.
    println(s"constants: ${conv.Big} ${conv.Small} ${conv.Name} ${conv.Ratio} ${conv.Typed}")
    conv.Counter = 10
    println(s"variable: ${conv.Bump()} ${conv.Counter}")

    // Go data for directional channel types.
    val ch = GoChan.make[Long](5)
    conv.Fill(ch, 4)
    println(s"send-only: ${ch.iterator.toList}")
    val in = GoChan.make[Long](3)
    in.send(5); in.send(6); in.close()
    println(s"receive-only: ${conv.Drain(in)}")
