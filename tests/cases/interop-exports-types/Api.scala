import scalago.go.*

// Exported Scala code with the types that go-library does not use (docs/_docs/spec/interop.md,
// "Exports"; docs/_docs/interop/exports.md): primitives, GoString, arrays, Go data, facades,
// GoError, functions in both directions, exported class hierarchies, initialization on first use
// and output flushing.
@GoImport("strings")
object strings:
  class Builder:
    def WriteString(s: String): Int = native

@GoImport("errors")
object errors:
  def New(text: String): GoError = native

object Registry:
  println("[scala] Registry initialized")
  private val names = collection.mutable.ArrayBuffer.empty[String]
  def add(name: String): Int = synchronized { names += name; names.size }

object Api:
  @GoExport def add(a: Long, b: Double): Double = a + b
  @GoExport def describe(b: Boolean, s: Short, c: Char, y: Byte, f: Float): String = s"$b $s $c $y $f"
  @GoExport("Shout") def upper(s: GoString): GoString = s.toJavaString.toUpperCase.toGoString
  @GoExport def utf16Length(s: String): Int = s.length
  @GoExport def reversed(xs: Array[Int]): Array[Int] = xs.reverse
  @GoExport def longs(n: Int): Array[Long] = Array.tabulate(n)(i => i.toLong * i)
  @GoExport def clear(xs: GoSlice[GoByte]): Unit = for i <- 0 until xs.length do xs(i) = GoByte(0)
  @GoExport def words(s: String): GoSlice[GoString] = GoSlice.fromSeq(s.split(" ").toSeq.map(GoString(_)))
  @GoExport def total(c: GoChan[GoInt]): GoInt = GoInt(c.iterator.map(_.toLong).sum)
  @GoExport def bump(p: GoPtr[GoInt]): Unit = p.set(GoInt(p.get.toLong + 1))
  @GoExport def write(b: strings.Builder, s: String): Unit = b.WriteString(s"<$s>")
  @GoExport def check(err: GoError): String = if err == null then "nil error" else s"error: ${err.Error()}"
  @GoExport def failure(msg: String): GoError = if msg.isEmpty then null else errors.New(msg)
  @GoExport def adder(n: Int): Int => Int = x => x + n
  @GoExport def applyTwice(f: (String, Int) => String, s: String): String = f(f(s, 1), 2)
  @GoExport def keep(a: Animal): Animal = a
  @GoExport def register(name: String): Int = Registry.add(name)
  @GoExport def log(msg: String): Unit = print(s"[scala] $msg; ")

/** An exported hierarchy: each class's interface has its own and its superclasses' methods. */
@GoExport abstract class Animal(animalName: String):
  @GoExport def name(): String = animalName
  @GoExport def sound(): String
  @GoExport def speak(): String = s"${name()} says ${sound()}"

@GoExport class Dog(name: String) extends Animal(name):
  def sound(): String = "woof"

@GoExport class Puppy(name: String, months: Int) extends Dog(name):
  override def sound(): String = "yip"
  @GoExport def age(): Int = months
