import gofacades.{example_com_kit_a_util, example_com_kit_b_util, kit}
import scalago.go.*

/** A Scala visitor: an exception becomes the Go func's error. */
object Picky extends (String => Unit):
  def apply(name: String): Unit = if name.startsWith("x") then throw GoException(kit.ErrClosed)

/** A hand-written facade of the same package, next to the generated one. */
@GoImport("example.com/kit")
object kitVariables:
  def Default: String = native
  def Default_=(s: String): Unit = native
  def Count: Long = native
  def Count_=(n: Long): Unit = native

// Generated facades (docs/_docs/interop/generated-facades.md): packages with the same name, errors
// as values or failures, promoted fields and methods, variables, constants, types extending every
// interface they implement, and func types returning only an error.
object Main:
  def main(args: Array[String]): Unit =
    // Packages with the same name are named after their import paths.
    println(s"${example_com_kit_a_util.Name()} / ${example_com_kit_b_util.Name()}")

    // A trailing error is thrown, except where the error is the value (New..., Err).
    println(s"NewError: ${kit.NewError("a value").Error()}")
    kit.Validate("fine")
    try kit.Validate("")
    catch case e: GoException => println(s"Validate: ${e.getMessage}")
    try kit.Open("")
    catch case e: GoException => println(s"Open: ${e.getMessage}")
    val conn = kit.Open("db")
    println(s"conn: ${conn.Name} ${conn.ID} ${conn.Describe()} Err ${conn.Err()}")
    conn.ID = 7
    conn.Close()
    println(s"closed: ${conn.Describe()} Err ${conn.Err().Error()}")
    try conn.Close()
    catch case e: GoException => println(s"Close again: ${e.error eq kit.ErrClosed}")

    // Variables and constants. Generated facades have no setters for package variables: a
    // hand-written facade next to them assigns.
    println(s"variables: ${kit.Default} ${kit.Count}")
    kitVariables.Default = "changed"
    kitVariables.Count = kit.Count + 5
    println(s"assigned: ${kit.Default} ${kit.Count}")
    println(s"constants: ${kit.Pi} ${kit.Answer} ${kit.Greeting} ${kit.Big} ${kit.Yes} ${kit.Red.String()} ${kit.Color(0).String()}")

    // Square extends both interfaces it implements.
    val square = kit.NewSquare(2)
    val shape: kit.Shape = square
    val named: kit.Named = square
    println(s"interfaces: ${shape.Area()} ${named.Name()} ${kit.Total(square, kit.NewSquare(1))}")

    // A func type returning only an error is a Scala function that throws.
    val names = GoSlice.of(go"ann", go"bob", go"xena", go"zed")
    println(s"Visit: ${kit.Visit(names.slice(0, 2), Picky)}")
    try kit.Visit(names, Picky)
    catch case e: GoException => println(s"Visit failed: ${e.getMessage}")
    println(s"Visit with a lambda: ${kit.Visit(names, n => println(s"visiting $n"))}")

    // Go data in generated signatures uses canonical views.
    val joined = kit.Join(GoSlice.of(go"a", go"b"), "+")
    println(s"Join: ${joined.iterator.map((k, v) => s"${k.toJavaString}=$v").mkString}")
