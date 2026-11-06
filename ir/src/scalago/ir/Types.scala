package scalago.ir

/** Erased (JVM-level) types, as seen after the Scala compiler's `erasure` phase. */
enum Type:
  /** `Unit` in result position, or a tree evaluated only for its effects. */
  case Void
  case Boolean, Char, Byte, Short, Int, Long, Float, Double
  /** Type of trees that never complete normally (`throw`, `return`). */
  case Nothing
  /** Type of the `null` literal. */
  case Null
  /** A reference to a class, by binary name (e.g. `java.lang.String`, `Main$`). */
  case Class(name: ClassName)
  case Array(elem: Type)

  def isPrimitive: scala.Boolean = this match
    case Boolean | Char | Byte | Short | Int | Long | Float | Double => true
    case _                                                          => false

  def isNumeric: scala.Boolean = this match
    case Char | Byte | Short | Int | Long | Float | Double => true
    case _                                                 => false

  /** Compact descriptor, used in method signatures. */
  def descriptor: String = this match
    case Void        => "V"
    case Boolean     => "Z"
    case Char        => "C"
    case Byte        => "B"
    case Short       => "S"
    case Int         => "I"
    case Long        => "J"
    case Float       => "F"
    case Double      => "D"
    case Nothing     => "N"
    case Null        => "0"
    case Class(name) => s"L$name;"
    case Array(elem) => s"[${elem.descriptor}"

  override def toString: String = this match
    case Class(name) => name
    case Array(elem) => s"Array[$elem]"
    case other       => other.productPrefix

object Type:
  val Object: Type = Class(ClassName.Object)
  val String: Type = Class(ClassName.String)
