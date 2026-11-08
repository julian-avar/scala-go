package scalago.go

import scala.quoted.*

extension (inline sc: StringContext)
  /** A [[GoString]] literal, interpolated like `s"..."`: `go"x = \$x, \${y + 1}"`.
   *
   *  Escapes are those of `s` (`\n`, `\u00e9`, `\$\$`, ...), checked at compile time. Arguments of
   *  static type `GoString` are inserted as they are; any other is rendered by `String.valueOf`
   *  ([[GoString.valueOf]]), as `s` does. There is no `f`-style formatting: format into a `String`
   *  (`"%.2f".format(x)`) and interpolate that.
   *
   *  On Go, the literal parts are Go string constants and the whole is one Go concatenation, so a
   *  `go"..."` without arguments costs nothing at run time.
   */
  inline def go(inline args: Any*): GoString = ${ GoInterpolator.expand('sc, 'args) }

private object GoInterpolator:
  /** `GoString(part0).concat(arg0).concat(GoString(part1))...`, without empty parts. The linker
   *  turns the chain into one Go concatenation of constants and Go strings.
   */
  def expand(sc: Expr[StringContext], args: Expr[Seq[Any]])(using Quotes): Expr[GoString] =
    import quotes.reflect.*
    val parts = sc match
      case '{ StringContext(${ Varargs(parts) }*) } => parts
      case _                                       => report.errorAndAbort("go\"...\" needs literal parts", sc)
    val values = args match
      case Varargs(values) => values
      case _               => report.errorAndAbort("go\"...\" needs explicit arguments", args)
    val literals = parts.map { part =>
      val pos = part.asTerm.pos
      try StringContext.processEscapes(part.valueOrAbort)
      catch
        case e: StringContext.InvalidEscapeException =>
          report.errorAndAbort(e.getMessage, Position(pos.sourceFile, pos.start + e.index, pos.start + e.index + 1))
        case e: IllegalArgumentException => report.errorAndAbort(e.getMessage, pos) // a bad \u escape
    }
    val pieces = literals.zipAll(values.map(Some(_)), "", None).flatMap { (literal, value) =>
      Option.when(literal.nonEmpty)('{ GoString(${ Expr(literal) }) }) ++ value.map(render)
    }
    pieces.reduceLeftOption((a, b) => '{ $a.concat($b) }).getOrElse('{ GoString("") })

  private def render(value: Expr[Any])(using Quotes): Expr[GoString] = value match
    case '{ $g: GoString } => g
    case _                 => '{ GoString.valueOf($value) }
