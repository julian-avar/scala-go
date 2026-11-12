import scalago.go.*

// Scala functions as Go funcs, Go funcs as Scala functions (docs/_docs/spec/interop.md,
// "Functions"), and the order of evaluation and output around facade calls ("Calls").
@GoImport("example.com/funcs")
object funcs:
  type Op = (Long, Long) => Long
  def Apply(op: Op, a: Long, b: Long): Long = native
  def Map(s: String, f: String => String): String = native
  def IsNil(f: () => Unit): Boolean = native
  def Zero(): () => String = native
  def Two(): (Long, Long) => Long = native
  def Three(): (String, String, String) => String = native
  def Nil(): () => Long = native
  def Compose(f: Long => Long, g: Long => Long): Long => Long = native
  def Identity(f: Long => Long): Long => Long = native
  def Checked(f: Long => Long, x: Long): String = native
  def Results(f: String => (String, Long)): String = native
  def Each(xs: Array[String], f: (Long, String) => Unit): Unit = native
  def Say(a: Int, b: Int, c: Int): Unit = native
  def NewBox(): Box = native

  class Box:
    def N: Long = native
    def N_=(n: Long): Unit = native
    def Add(x: Int, y: Int): Long = native

object Main:
  def main(args: Array[String]): Unit =
    // Scala functions passed to Go, also for a defined func type.
    println(s"defined func type: ${funcs.Apply((a, b) => a - b, 10, 3)}")
    val suffix = "!"
    println(s"closure: ${funcs.Map("a,b,c", _.toUpperCase + suffix)}")
    println(s"null is a nil func: ${funcs.IsNil(null)} ${funcs.IsNil(() => ())}")

    // Go funcs returned to Scala.
    val zero = funcs.Zero()
    println(s"0 parameters: ${zero()} ($zero)")
    println(s"2 parameters: ${funcs.Two()(4, 2)}")
    println(s"3 parameters: ${funcs.Three()("a", "b", "c")}")
    println(s"nil func: ${funcs.Nil() == null}")
    val composed = funcs.Compose(_ + 1, _ * 10)
    println(s"composed: ${composed(4)} ${List(1L, 2L).map(composed)}")
    println(s"round trip: ${funcs.Identity(_ * 3)(5)}")

    // An exception in a Scala function becomes the Go func's error result.
    println(funcs.Checked(x => 100 / x, 4))
    println(funcs.Checked(x => 100 / x, 0))
    println(funcs.Checked(x => throw GoException(errors.New("from Go's errors")), 1))
    println(s"two results: ${funcs.Results(s => (s.toUpperCase, s.length.toLong))}")

    // Buffered Scala output is flushed before Go code runs, and before callbacks return.
    print("scala before; ")
    funcs.Each(Array("x", "y"), (i, s) => print(s"scala callback $i $s; "))
    println("scala after")

    // Receiver and arguments are evaluated left to right, before the call.
    var n = 0
    def next(): Int = { n += 1; print(s"arg$n "); n }
    funcs.Say(next(), next(), next())
    val box = funcs.NewBox()
    def theBox(): funcs.Box = { print("receiver "); box }
    println(s"= ${theBox().Add(next(), next())}")
    theBox().N = { print("value "); 7 }
    println(s"assigned ${box.N}")

@GoImport("errors")
object errors:
  def New(text: String): GoError = native
