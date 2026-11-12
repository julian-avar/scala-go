import scalago.go.*

// Generic Go functions through monomorphic facades, one member per instantiation; Go infers the
// type arguments (docs/_docs/spec/interop.md, "Generic functions"; facades.md, "Generic functions").
@GoImport("example.com/gen")
object gen:
  def Map(xs: GoSlice[GoInt], f: GoInt => GoString): GoSlice[GoString] = native
  @GoName("Map") def lengths(xs: Array[String], f: String => Int): Array[Int] = native
  def First(xs: GoSlice[GoString]): (GoString, Boolean) = native
  @GoName("First") def firstLong(xs: Array[Long]): (Long, Boolean) = native
  def Larger(a: Long, b: Long): Long = native
  @GoName("Larger") def largerString(a: String, b: String): String = native
  @GoName("Larger") def largerDouble(a: Double, b: Double): Double = native
  def Describe(s: fmt.Stringer): String = native
  def TypeOf(x: Long): String = native
  @GoName("TypeOf") def typeOfInt(x: Int): String = native
  @GoName("TypeOf") def typeOfGoInt(x: GoInt): String = native
  @GoName("TypeOf") def typeOfString(x: String): String = native
  @GoName("TypeOf") def typeOfSlice(x: GoSlice[GoByte]): String = native
  def Count(m: GoMap[GoString, GoInt]): Int = native
  def Ptr(x: Long): GoPtr[Long] = native
  def NewPoint(x: Int, y: Int): Point = native

  class Point extends fmt.Stringer:
    def String(): String = native

@GoImport("fmt")
object fmt:
  trait Stringer:
    def String(): String

@GoImport("slices")
object slices:
  def Reverse(xs: GoSlice[GoString]): Unit = native
  def BinarySearch(xs: GoSlice[GoInt], x: GoInt): (Int, Boolean) = native
  def Equal(a: GoSlice[Long], b: GoSlice[Long]): Boolean = native
  def IndexFunc(xs: GoSlice[GoString], f: GoString => Boolean): Int = native
  def Insert(xs: GoSlice[GoInt], i: Int, vs: GoInt*): GoSlice[GoInt] = native

@GoImport("maps")
object maps:
  def Clone(m: GoMap[GoString, GoInt]): GoMap[GoString, GoInt] = native

@GoImport("cmp")
object cmp:
  def Compare(a: String, b: String): Int = native
  @GoName("Compare") def compareDoubles(a: Double, b: Double): Int = native

final class Temperature(c: Double) extends fmt.Stringer:
  def String(): String = s"$c°C"

object Main:
  def strings(xs: GoSlice[GoString]): String = xs.iterator.map(_.toJavaString).mkString(",")

  def main(args: Array[String]): Unit =
    // Type parameters inferred from the arguments, also from a Scala function's Go func type.
    val ints = GoSlice.of(GoInt(1), GoInt(22), GoInt(333))
    println(s"Map: ${strings(gen.Map(ints, n => go"#$n"))}")
    println(s"Map: ${gen.lengths(Array("a", "bb", "ccc"), _.length).mkString(",")}")
    println(s"First: ${gen.First(GoSlice.of(go"x", go"y"))} ${gen.First(GoSlice.empty[GoString])._2} ${gen.firstLong(Array(5L))}")

    // A type set constraint: each facade names a type of the set.
    println(s"Larger: ${gen.Larger(3, 7)} ${gen.largerString("pear", "apple")} ${gen.largerDouble(-0.5, -1.5)}")
    println(s"TypeOf: ${gen.TypeOf(1L)} ${gen.typeOfInt(1)} ${gen.typeOfGoInt(GoInt(1))} ${gen.typeOfString("s")} ${gen.typeOfSlice(GoSlice.of(GoByte(1)))}")

    // An interface constraint: the argument is converted to the interface.
    println(s"Describe: ${gen.Describe(gen.NewPoint(1, 2))} / ${gen.Describe(Temperature(21.5))}")
    val counts = GoMap.of(go"a" -> GoInt(1), go"b" -> GoInt(2))
    println(s"Count: ${gen.Count(counts)}")
    val p = gen.Ptr(41)
    p.set(p.get + 1)
    println(s"Ptr: ${p.get}")

    // Go's slices, maps and cmp packages.
    val words = GoSlice.of(go"a", go"b", go"c")
    slices.Reverse(words)
    println(s"Reverse: ${strings(words)}")
    println(s"BinarySearch: ${slices.BinarySearch(ints, GoInt(22))} ${slices.BinarySearch(ints, GoInt(5))}")
    println(s"Equal: ${slices.Equal(GoSlice.of(1L, 2L), GoSlice.of(1L, 2L))} ${slices.Equal(GoSlice.of(1L), GoSlice.of(2L))}")
    println(s"IndexFunc: ${slices.IndexFunc(words, _.toJavaString == "a")}")
    println(s"Insert: ${slices.Insert(ints, 1, GoInt(10), GoInt(11)).iterator.map(_.toLong).mkString(",")}")
    val copy = maps.Clone(counts)
    copy(go"c") = GoInt(3)
    println(s"Clone: ${copy.size} ${counts.size}")
    println(s"Compare: ${cmp.Compare("a", "b")} ${cmp.Compare("b", "a")} ${cmp.compareDoubles(Double.NaN, 1)} ${cmp.compareDoubles(2, 2)}")
