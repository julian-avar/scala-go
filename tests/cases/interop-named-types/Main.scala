import scalago.go.*

// Defined Go types (docs/_docs/spec/interop.md, "Facade declarations" and "Named types"):
// conversions, composite literals, constants, methods on non-struct types, func and empty
// interface types, top-level facade classes and traits, and two facade objects of one package.
@GoImport("example.com/named")
object named:
  def Level(x: Int): Level = native
  def Debug: Level = native
  def Warn: Level = native
  @GoName("Info") def infoNumber: Int = native
  def Parse(s: String): Level = native
  @GoName("Parse") def parseNumber(s: String): Long = native
  def Describe(l: Level): String = native
  def Name(s: String): Name = native
  def Tags(elems: String*): Tags = native
  @GoName("Tags") def tagsFrom(xs: GoSlice[GoString]): Tags = native
  def Attrs(entries: (String, Int)*): Attrs = native
  @GoName("Attrs") def attrsFrom(m: GoMap[GoString, GoInt]): Attrs = native
  def CountTags(t: GoSlice[GoString]): Int = native
  def SizeAttrs(a: GoMap[GoString, GoInt]): Int = native
  type Handler = String => String
  def Run(h: Handler, s: String): String = native
  def Upper(): Handler = native
  type Msg = Any
  def Kind(m: Msg): String = native

  class Level:
    def String(): String = native
    def Above(other: Level): Boolean = native
  class Name:
    def Greeting(): String = native
  class Tags:
    def Joined(): String = native
  class Attrs:
    def Sorted(): String = native

/** A type of the package declared outside its object. */
@GoImport("example.com/named", "Config")
class Config extends Stringer:
  def Name: String = native
  def Name_=(n: String): Unit = native
  def Level: named.Level = native
  def Level_=(l: named.Level): Unit = native
  def Tags: named.Tags = native
  def Tags_=(t: named.Tags): Unit = native
  def String(): String = native

@GoImport("example.com/named", "Stringer")
trait Stringer:
  def String(): String

/** Another facade object of the same package: facades are matched by the Go types. */
@GoImport("example.com/named")
object namedToo:
  def Show(s: Stringer): String = native
  def Describe(l: named.Level): String = native

final class Version(major: Int, minor: Int) extends Stringer:
  def String(): String = s"v$major.$minor"

object Main:
  def main(args: Array[String]): Unit =
    // Conversions to a defined type, constants, methods with value receivers.
    val warn = named.Level(2)
    println(s"conversion: ${warn.String()} ${named.Describe(warn)}")
    println(s"constants: ${named.Debug.String()} ${named.infoNumber} ${named.Warn == warn} ${named.Debug == warn}")
    println(s"methods: ${warn.Above(named.Debug)} ${named.Debug.Above(warn)}")
    println(s"results: ${named.Parse("info").String()} ${named.parseNumber("warn")}")
    println(s"zero value: ${(new named.Level).String()}")
    println(s"toString: $warn")
    println(s"string type: ${named.Name("gopher").Greeting()}")

    // Composite literals of defined slice and map types; a repeated key's later entry wins.
    println(s"slice literal: ${named.Tags("a", "b", "c").Joined()} [${named.Tags().Joined()}]")
    println(s"slice conversion: ${named.tagsFrom(GoSlice.of(go"x", go"y")).Joined()}")
    println(s"map literal: ${named.Attrs("b" -> 2, "a" -> 1, "b" -> 3).Sorted()}")
    println(s"map conversion: ${named.attrsFrom(GoMap.of(go"k" -> GoInt(7))).Sorted()}")
    // Go data passed for a defined slice or map type is converted.
    println(s"data for defined types: ${named.CountTags(GoSlice.of(go"a", go"b"))} ${named.SizeAttrs(GoMap.of(go"k" -> GoInt(1)))}")

    // A defined func type is a Scala function type; an interface without methods is Any.
    println(s"func type: ${named.Run(s => s.reverse, "abc")} ${named.Run(named.Upper(), "abc")} ${named.Upper()("go")}")
    println(s"empty interface: ${named.Kind("s")} ${named.Kind(1)} ${named.Kind(warn)} ${named.Kind(null)}")

    // A top-level facade class: new is a zero struct; fields of defined types.
    val config = new Config
    println(s"zero struct: [${config.Name}] ${config.Level.String()} [${config.Tags.Joined()}]")
    config.Name = "app"
    config.Level = named.Warn
    config.Tags = named.Tags("x", "y")
    println(s"fields: ${config.String()}")

    // Another facade object of the package takes the first one's types.
    println(s"second facade: ${namedToo.Describe(named.Debug)} ${namedToo.Show(config)} ${namedToo.Show(Version(1, 2))}")
