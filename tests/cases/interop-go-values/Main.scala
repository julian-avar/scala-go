import scalago.go.*

// Go values held by Scala code in an Any or a facade type (docs/_docs/spec/interop.md, "Go values
// in Scala code"): type tests, casts, boxes, equality, hashing, toString, getClass, monitors.
@GoImport("example.com/vals")
object vals:
  def NewPair(a: Int, b: Int): Pair = native
  def PairValue(a: Int, b: Int): Any = native
  def NewNamed(n: String): Named = native
  def AnyLevel(l: Int): Any = native
  def AnyInt(): Any = native
  def AnyInt32(): Any = native
  def AnyInt64(): Any = native
  def AnyUint8(): Any = native
  def AnyFloat64(): Any = native
  def AnyBool(): Any = native
  def AnyString(): Any = native
  def AnyError(): Any = native
  def AnyNil(): Any = native
  def AnySlice(): Any = native
  def AnyMap(): Any = native
  def OtherSlice(): Any = native
  def AnyTagged(): Any = native
  def OtherTagged(): Any = native
  def AnyBoxedSlice(): Any = native
  def OtherBoxedSlice(): Any = native

  class Pair:
    def A: Int = native
    def A_=(a: Int): Unit = native
    def B: Int = native
  class Named:
    def N: String = native
  class Level:
    def String(): String = native

@GoImport("fmt")
object fmt:
  trait Stringer:
    def String(): String

object Main:
  def kind(x: Any): String = x match
    case null           => "null"
    case p: vals.Pair   => s"Pair(${p.A}, ${p.B})"
    case e: GoError     => s"error ${e.Error()}"
    case s: fmt.Stringer => s"Stringer ${s.String()}"
    case i: Int         => s"Int $i"
    case l: Long        => s"Long $l"
    case d: Double      => s"Double $d"
    case b: Boolean     => s"Boolean $b"
    case other          => s"other ${other.getClass.getName}"

  /** Hashes of pointers are addresses: hide them. */
  def masked(s: String): String = s.replaceAll("@[0-9a-f]+$", "@<hash>")

  def main(args: Array[String]): Unit =
    // Type tests: Go type assertions; a struct facade accepts both T and *T.
    val pointer: Any = vals.NewPair(1, 2)
    val value: Any = vals.PairValue(3, 4)
    for x <- Seq(pointer, value, vals.NewNamed("n"), vals.AnyLevel(1), vals.AnyError(), vals.AnyNil()) do
      println(s"kind: ${kind(x)}")
    println(s"isInstanceOf: ${pointer.isInstanceOf[vals.Pair]} ${value.isInstanceOf[vals.Pair]} ${pointer.isInstanceOf[vals.Named]}")

    // Casts: null passes; a struct value is copied to a new pointer.
    val nothing: vals.Pair = (null: Any).asInstanceOf[vals.Pair]
    println(s"null cast: $nothing")
    val cast = value.asInstanceOf[vals.Pair]
    cast.A = 30
    println(s"cast copy: ${cast.A} ${value.asInstanceOf[vals.Pair].A}")
    try
      vals.NewNamed("x").asInstanceOf[Any].asInstanceOf[vals.Pair]
      println("no exception")
    catch case e: ClassCastException => println("bad cast: ClassCastException")

    // Boxes: only the Go types of Scala's primitives are Scala numbers.
    println(s"int32: ${kind(vals.AnyInt32())}, int64: ${kind(vals.AnyInt64())}, float64: ${kind(vals.AnyFloat64())}, bool: ${kind(vals.AnyBool())}")
    println(s"Go int: ${vals.AnyInt().isInstanceOf[Long]} ${vals.AnyInt().isInstanceOf[Int]}, as data: ${GoData.toScala(vals.AnyInt())}")
    println(s"Go uint8: ${vals.AnyUint8().isInstanceOf[Byte]}, as data: ${GoData.toScala(vals.AnyUint8())}")
    println(s"Go string: ${vals.AnyString().isInstanceOf[String]} ${vals.AnyString() == go"go"}, as data: ${GoData.toScala(vals.AnyString())}")

    // Equality: Go's == for comparable values of one type, identity for pointers, slices, maps.
    println(s"struct values: ${vals.PairValue(1, 2) == vals.PairValue(1, 2)} ${vals.PairValue(1, 2) == vals.PairValue(1, 3)}")
    println(s"struct pointers: ${vals.NewPair(1, 2) == vals.NewPair(1, 2)} ${pointer == pointer}")
    println(s"defined ints: ${vals.AnyLevel(2) == vals.AnyLevel(2)} ${vals.AnyLevel(2) == vals.AnyLevel(1)}")
    println(s"different types: ${vals.AnyInt() == vals.AnyInt64()} ${vals.AnyInt() == vals.AnyLevel(1)}")
    println(s"Go ints: ${vals.AnyInt() == vals.AnyInt()} ${vals.AnyInt().equals(vals.AnyInt())}")
    println(s"slices: ${vals.AnySlice() == vals.AnySlice()} ${vals.AnySlice() == vals.OtherSlice()}; maps: ${vals.AnyMap() == vals.AnyMap()}")
    // Structs (and interfaces in them) holding values Go cannot compare: by their parts.
    println(s"uncomparable structs: ${vals.AnyTagged() == vals.AnyTagged()} ${vals.AnyTagged() == vals.OtherTagged()}")
    println(s"interface fields: ${vals.AnyBoxedSlice() == vals.AnyBoxedSlice()} ${vals.AnyBoxedSlice() == vals.OtherBoxedSlice()}")
    // eq is Go's ==, extended to them as ==.
    val tagged = vals.AnyTagged().asInstanceOf[AnyRef]
    println(s"eq: ${tagged eq tagged} ${vals.AnySlice().asInstanceOf[AnyRef] eq vals.AnySlice().asInstanceOf[AnyRef]} ${vals.AnySlice().asInstanceOf[AnyRef] eq vals.OtherSlice().asInstanceOf[AnyRef]}")

    // hashCode is consistent with ==.
    println(s"hashes: ${vals.PairValue(5, 6).hashCode == vals.PairValue(5, 6).hashCode} ${vals.AnyInt().hashCode == vals.AnyInt().hashCode} ${vals.AnyMap().hashCode == vals.AnyMap().hashCode}")
    println(s"in a set: ${Set(vals.PairValue(1, 1), vals.PairValue(1, 1), vals.PairValue(2, 2)).size}")

    // toString: Error(), String(), else the Go type and a hash.
    println(s"toString: ${vals.AnyError()} | ${vals.NewNamed("n")} | ${vals.AnyLevel(0)} | ${masked(pointer.toString)}")
    println(s"string interpolation: ${vals.AnyLevel(2)}")

    // getClass.getName is the Go type.
    for x <- Seq(pointer, value, vals.AnyInt(), vals.AnyLevel(0), vals.AnySlice(), vals.AnyMap(), vals.AnyError()) do
      println(s"class: ${x.getClass.getName}")

    // A monitor keyed by the Go value.
    val shared = vals.AnyMap().asInstanceOf[AnyRef]
    val counter = new java.util.concurrent.atomic.AtomicInteger
    val threads = (1 to 4).map(_ => Thread(() => for _ <- 1 to 100 do shared.synchronized(counter.incrementAndGet())))
    threads.foreach(_.start())
    threads.foreach(_.join())
    println(s"synchronized: ${counter.get}")
    pointer.asInstanceOf[AnyRef].synchronized(println("synchronized on a struct pointer"))
