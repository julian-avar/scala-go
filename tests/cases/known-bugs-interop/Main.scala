import scalago.go.*

// Known bugs of Go interop: each line prints what scala-go does today; the comment says what it
// should print. Fixing a bug makes this case fail: move the check to the interop-* case named in
// the comment, and update the docs.

@GoImport("fmt")
object fmt:
  trait Stringer:
    def String(): String
  def Sprint(args: Any*): String = native

object Main:
  def main(args: Array[String]): Unit =
    // A Scala String passes a type test for a Go interface it does not implement in Scala
    // (fmt.Stringer: the runtime's string type has a String method). Go code that gets the
    // String as an `any` sees a Go string, which is not a Stringer. Should be false, as on the
    // JVM (interop-go-values).
    val s: Any = "scala"
    println(s"String isInstanceOf[fmt.Stringer]: ${s.isInstanceOf[fmt.Stringer]}")
    println(s"String matches fmt.Stringer: ${s match { case _: fmt.Stringer => true; case _ => false }}")

    // GoType names differ between the JVM and Go for `any`: the JVM prints "any" and
    // "map[string]any" (docs/_docs/spec/interop.md, "Go data": Go data has the same results on
    // both). They should agree (interop-go-data).
    println(s"GoType names: ${GoType.of[Any].name} ${GoType.of[GoMap[GoString, Any]].name}")
