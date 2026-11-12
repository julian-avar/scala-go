import scala.quoted.*

// Macros run in the compiler; the program contains only their expansions.
object Macros:
  inline def power(x: Double, inline n: Int): Double = ${ powerCode('x, 'n) }

  def powerCode(x: Expr[Double], n: Expr[Int])(using Quotes): Expr[Double] =
    val k = n.valueOrAbort
    if k == 0 then '{ 1.0 }
    else if k % 2 == 0 then '{ val y = $x * $x; ${ powerCode('y, Expr(k / 2)) } }
    else '{ $x * ${ powerCode(x, Expr(k - 1)) } }

  inline def showCode(inline expr: Any): String = ${ showCodeImpl('expr) }
  def showCodeImpl(expr: Expr[Any])(using Quotes): Expr[String] = Expr(expr.show)

  inline def fieldNames[T]: List[String] = ${ fieldNamesImpl[T] }
  def fieldNamesImpl[T: Type](using Quotes): Expr[List[String]] =
    import quotes.reflect.*
    Expr(TypeRepr.of[T].typeSymbol.caseFields.map(_.name))

  inline def debug(inline expr: Any): String = ${ debugImpl('expr) }
  def debugImpl(expr: Expr[Any])(using Quotes): Expr[String] =
    '{ ${ Expr(expr.show) } + " = " + $expr }
