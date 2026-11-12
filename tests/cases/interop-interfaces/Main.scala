import scalago.go.*

// Values passed to Go interface types, and Go interfaces implemented in Scala
// (docs/_docs/spec/interop.md, "Interfaces" and "Go interfaces implemented in Scala").
@GoImport("example.com/shapes")
object shapes:
  def NewSquare(s: Double): Square = native
  def NewCircle(r: Double): Circle = native
  def NewTag(n: String): Tag = native
  def Kind(s: Shape): String = native
  @GoName("Kind") def kindOf(s: Any): String = native
  def KindAny(v: Any): String = native
  def Same(a: Any, b: Any): Boolean = native
  def Keep(v: Any): Any = native
  def Store(s: Shape): Unit = native
  def Stored(i: Int): Shape = native
  def Total(shapes: Shape*): Double = native
  def Describe(n: Named): String = native
  def RenameAll(to: String, ns: Named*): String = native
  def ErrEmpty: GoError = native
  def Validate(v: Validator, inputs: String*): String = native
  @GoName("Validate") def validateChecked(v: CheckedValidator, inputs: String*): String = native
  def Show(l: Labeled): String = native
  def Implements(v: Any): String = native

  trait Shape:
    def Area(): Double
  trait Named:
    def Name(): String
    def Rename(to: String): (String, Long)
  trait Labeled extends Named:
    def Label(): String
  trait Validator:
    def Validate(s: String): Unit

  class Square extends Shape:
    def S: Double = native
    def Area(): Double = native
  class Circle extends Shape:
    def Area(): Double = native
  class Tag:
    def Name: String = native

/** A second facade of shapes.Validator, whose method returns the error as a value. */
@GoImport("example.com/shapes", "Validator")
trait CheckedValidator:
  def Validate(s: String): GoError

@GoImport("fmt")
object fmt:
  trait Stringer:
    def String(): String

final case class Rect(w: Double, h: Double) extends shapes.Shape:
  def Area(): Double = w * h

final class Person(private var name: String) extends shapes.Labeled with fmt.Stringer:
  private var renames = 0
  def Name(): String = name
  def Rename(to: String): (String, Long) =
    if to.isEmpty then throw IllegalArgumentException("no name")
    val old = name
    name = to
    renames += 1
    (old, renames)
  def Label(): String = "person"
  def String(): String = s"Person($name)"

object NonEmpty extends shapes.Validator:
  def Validate(s: String): Unit =
    if s.isEmpty then throw GoException(shapes.ErrEmpty)
    if s.length > 3 then throw IllegalArgumentException(s"too long: $s")

object Lowercase extends CheckedValidator:
  def Validate(s: String): GoError = if s == s.toLowerCase then null else shapes.ErrEmpty

class NotAShape

object Main:
  def attempt(what: String)(body: => Any): Unit =
    val result =
      try String.valueOf(body)
      catch case e: Exception => s"throws ${e.getClass.getName}"
    println(s"$what: $result")

  def main(args: Array[String]): Unit =
    // Struct facades go by value when T's method set implements the interface, else as *T.
    println(shapes.Kind(shapes.NewSquare(2)))
    println(shapes.Kind(shapes.NewCircle(1)))
    println(shapes.KindAny(shapes.NewSquare(3)))
    println(shapes.KindAny(shapes.NewCircle(2)))
    println(shapes.KindAny(shapes.NewTag("t")))
    println(shapes.KindAny("text"))
    println(shapes.KindAny(null))
    println(shapes.Kind(Rect(2, 5)))
    println(s"total: ${shapes.Total(shapes.NewSquare(1), shapes.NewCircle(1), Rect(1, 2))}")

    // A value that does not implement the interface throws ClassCastException.
    attempt("a String as a Shape")(shapes.kindOf("text"))
    attempt("a Go struct without Area as a Shape")(shapes.kindOf(shapes.NewTag("t")))
    attempt("a Scala object not extending Shape")(shapes.kindOf(NotAShape()))
    attempt("null as a Shape")(shapes.kindOf(null))

    // Identity: a Scala object reaches Go as the same interface value, and comes back as itself.
    val r = Rect(1, 1)
    println(s"identity: ${shapes.Same(r, r)} ${shapes.Same(r, Rect(1, 1))} ${shapes.Keep(r).asInstanceOf[AnyRef] eq r}")
    shapes.Store(r)
    shapes.Store(shapes.NewSquare(4))
    println(s"stored: ${shapes.Stored(0) eq r} ${shapes.Stored(0) match { case Rect(w, h) => s"Rect $w x $h"; case _ => "?" }}")
    println(s"stored Go struct: ${shapes.Stored(1).Area()} ${shapes.Stored(1).isInstanceOf[shapes.Square]}")

    // Scala implementations: several results from a tuple, a thrown exception as the error.
    val ada = Person("ada")
    val bob = Person("bob")
    println(shapes.Describe(ada))
    println(shapes.RenameAll("eve", ada, bob))
    println(shapes.RenameAll("", ada))
    println(shapes.RenameAll("zoe", ada))
    println(shapes.Show(ada))
    println(shapes.Validate(NonEmpty, "", "abc", "abcd"))
    println(shapes.validateChecked(Lowercase, "abc", "aBc"))

    // Go type assertions on Scala objects: the interfaces whose facades the class extends.
    println(s"Rect: ${shapes.Implements(Rect(1, 1))}")
    println(s"Person: ${shapes.Implements(ada)}")
    println(s"isInstanceOf: ${(ada: Any).isInstanceOf[shapes.Named]} ${(r: Any).isInstanceOf[shapes.Named]} ${(shapes.NewCircle(1): Any).isInstanceOf[shapes.Shape]}")
