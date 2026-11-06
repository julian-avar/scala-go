import scalago.go.*

sealed trait Expr
case class Num(value: Double) extends Expr
case class Add(left: Expr, right: Expr) extends Expr
case class Mul(left: Expr, right: Expr) extends Expr

object Main {
  def eval(e: Expr): Double = e match {
    case Num(v)    => v
    case Add(l, r) => eval(l) + eval(r)
    case Mul(l, r) => eval(l) * eval(r)
  }

  def main(args: Array[String]): Unit = {
    val expr = Add(Num(1), Mul(Num(2), Num(3)))
    println(s"$expr = ${eval(expr)}")
    val names = if (args.isEmpty) List("world") else args.toList
    names.map(n => s"Hello, $n!").foreach(println)
    val g = "Grüße".toGoString
    println(s"${g.toJavaString}: ${g.byteLength} bytes as a Go string")
  }
}
