import scala.annotation.switch

// Pattern matching (docs/_docs/spec/language.md, section 8): extractors and their evaluation order,
// sequence, binder, alternative, typed, literal and stable-identifier patterns, guards,
// MatchError, @switch, and patterns in definitions, for and partial functions.
sealed trait Expr
case class Num(n: Int) extends Expr
case class Add(l: Expr, r: Expr) extends Expr
case class Mul(l: Expr, r: Expr) extends Expr
case class Neg(e: Expr) extends Expr

object Even:
  def unapply(n: Int): Boolean = n % 2 == 0
object Half:
  def unapply(n: Int): Option[Int] = if n % 2 == 0 then Some(n / 2) else None
object Words:
  def unapplySeq(s: String): Option[Seq[String]] = Option(s).map(_.split(" ").toSeq.filter(_.nonEmpty))
object Email:
  def unapply(s: String): Option[(String, String)] = s.split("@") match
    case Array(user, domain) => Some((user, domain))
    case _                   => None

// Name-based extractor: isEmpty and get, no Option.
class Parsed(val value: Int):
  def isEmpty: Boolean = value < 0
  def get: Int = value
object PosInt:
  def unapply(s: String): Parsed = Parsed(s.toIntOption.getOrElse(-1))

// Product-like extractor result: _1, _2 members.
class Pair(val a: String, val b: Int) extends Product:
  def _1 = a
  def _2 = b
  def canEqual(that: Any) = true
  def productArity = 2
  def productElement(n: Int) = if n == 0 then a else b
object KeyValue:
  def unapply(s: String): Pair = Pair(s.takeWhile(_ != '='), s.dropWhile(_ != '=').drop(1).length)

object Traced:
  var log = Vector.empty[String]
  def unapply(n: Int): Option[Int] =
    log :+= s"unapply($n)"
    Some(n)

object Main:
  val Answer = 42
  val lower = "lower"

  def eval(e: Expr): Int = e match
    case Num(n)                 => n
    case Add(Num(0), r)         => eval(r)
    case Add(l, r)              => eval(l) + eval(r)
    case Mul(Num(0), _) | Mul(_, Num(0)) => 0
    case Mul(l, r)              => eval(l) * eval(r)
    case Neg(Neg(inner))        => eval(inner)
    case Neg(e)                 => -eval(e)

  def simplify(e: Expr): Expr = e match
    case Add(x, Num(0))           => simplify(x)
    case Mul(x, Num(1))           => simplify(x)
    case n @ Neg(Num(_))          => n
    case Add(l, r)                => Add(simplify(l), simplify(r))
    case other                    => other

  def classify(n: Int): String = n match
    case 0                 => "zero"
    case Answer            => "the answer"
    case Even() if n > 100 => "big even"
    case Half(h) if h > 10 => s"even, half $h"
    case Even()            => "small even"
    case _                 => "odd"

  def seqs(xs: Any): String = xs match
    case List()                  => "empty list"
    case List(x)                 => s"one: $x"
    case List(1, rest*)          => s"starts with 1, then $rest"
    case first :: second :: Nil  => s"two: $first $second"
    case Vector(a, _, b*)        => s"vector $a, rest $b"
    case Array(a: Int, b: Int)   => s"int array $a $b"
    case Seq(a, all @ _*)        => s"seq $a ${all.length}"
    case _                       => "other"

  def literals(x: Any): String = x match
    case 1L          => "long one"
    case 'c'         => "char c"
    case 2.5         => "double 2.5"
    case true        => "true"
    case "str"       => "string str"
    case `lower`     => "stable identifier"
    case null        => "null"
    case ()          => "unit"
    case _: Int      => "some int"
    case _           => "other"

  def typed(x: Any): String = x match
    case s: String if s.length > 3   => s"long string $s"
    case s: (String | Char)           => s"short text $s"
    case n: (Int | Long)              => s"integral $n"
    case l: List[?]                   => s"list of ${l.size}"
    case m: Map[?, ?] @unchecked      => s"map of ${m.size}"
    case a: Array[Int]                => s"int array ${a.length}"
    case f: Function1[?, ?]           => "function"
    case e: RuntimeException          => s"runtime exception ${e.getMessage}"
    case t: Throwable                 => s"throwable ${t.getClass.getSimpleName}"
    case Some(Some(inner))            => s"nested option $inner"
    case (a, (b, c))                  => s"nested tuple $a $b $c"
    case _                            => "unknown"

  def opcode(c: Char): Int = (c: @switch) match
    case '+' => 1
    case '-' => 2
    case '*' => 3
    case '/' => 4
    case _   => 0

  def main(args: Array[String]): Unit =
    val expr = Add(Mul(Num(2), Num(3)), Neg(Neg(Num(4))))
    println(s"eval: ${eval(expr)} ${eval(Mul(Num(5), Num(0)))} ${eval(Add(Num(0), Num(9)))}")
    println(s"simplify: ${simplify(Add(Mul(Num(7), Num(1)), Num(0)))} ${simplify(Neg(Num(1)))}")
    println(s"classify: ${List(0, 42, 200, 30, 8, 7).map(classify)}")
    println(s"seqs: ${List[Any](Nil, List(5), List(1, 2, 3), List(4, 5), Vector(1, 2, 3, 4), Array(1, 2), Seq(9, 8, 7), "x").map(seqs).mkString(" | ")}")
    println(s"literals: ${List[Any](1L, 'c', 2.5, true, "str", "lower", null, (), 5, 1).map(literals).mkString(" | ")}")
    println(s"typed: ${List[Any]("hello", "hi", 'x', 3, 4L, List(1, 2), Map(1 -> 2), Array(1, 2, 3), (x: Int) => x, IllegalStateException("bad"), Error("e"), Some(Some(1)), (1, (2, 3)), 1.5).map(typed).mkString(" | ")}")
    println(s"switch: ${"+-*/x".map(opcode)}")

    // Extractors.
    "a  quick fox" match
      case Words(first, rest*) => println(s"unapplySeq: $first ${rest.mkString(",")}")
    "user@example.com" match
      case Email(user, domain) => println(s"tuple extractor: $user at $domain")
    println(s"name-based: ${List("12", "x", "-3").map { case PosInt(n) => s"int $n"; case s => s"not $s" }}")
    "key=value" match
      case KeyValue(k, len) => println(s"product-like: $k $len")

    // Evaluation order of extractors and guards.
    val traced = 5 match
      case Traced(n) if { Traced.log :+= s"guard1($n)"; n > 10 } => "first"
      case Traced(n) if { Traced.log :+= s"guard2($n)"; n > 1 }  => "second"
      case _                                                    => "none"
    println(s"evaluation order: $traced ${Traced.log.mkString(" ")}")

    // Binders.
    List(1, 2, 3) match
      case all @ (head :: (tail @ (_ :: _))) => println(s"binders: $all $head $tail")
      case _                                 => println("binders: no")
    Option(Num(3)) match
      case Some(n @ Num(v)) => println(s"binder in extractor: $n $v")
      case _                => ()

    // MatchError.
    try
      val x: Any = 3.5f
      x match
        case _: String => ()
    catch case e: MatchError => println(s"MatchError: ${e.getMessage}")
    try
      (Num(1): Expr) match
        case Add(_, _) => ()
    catch case e: MatchError => println(s"MatchError of a case class: ${e.getMessage}")

    // Patterns in definitions and for comprehensions.
    val (a, List(b, c)) = (1, List(2, 3)): @unchecked
    val Num(numVal) = Num(4)
    println(s"definitions: $a $b $c $numVal")
    val pairs = List((1, "one"), (2, "two"), (3, "three"))
    val evens = for (n, name) <- pairs if n % 2 == 1 yield s"$n=$name"
    val somes = for case Some(x) <- List(Some(1), None, Some(3)) yield x
    println(s"for patterns: $evens $somes")

    // Partial functions.
    val pf: PartialFunction[Any, String] =
      case i: Int if i > 0 => s"positive $i"
      case s: String       => s"string $s"
    val fallback: PartialFunction[Any, String] = { case x => s"fallback $x" }
    println(s"partial functions: ${pf.isDefinedAt(1)} ${pf.isDefinedAt(-1)} ${List[Any](1, -1, "a", 2.0).collect(pf)} ${(pf orElse fallback)(-1)} ${pf.lift(2.0)} ${pf.applyOrElse(-5, (x: Any) => s"else $x")}")
    val total = List(Some(2), None, Some(5)).foldLeft(0) {
      case (acc, Some(v)) => acc + v
      case (acc, None)    => acc - 1
    }
    println(s"pattern-matching lambdas: $total")

    // Regular expressions and string interpolator patterns.
    val Date = raw"(\d{4})-(\d{2})-(\d{2})".r
    "2024-03-15" match
      case Date(y, m, d) => println(s"regex: $d/$m/$y")
    "name: Ada, age: 36" match
      case s"name: $name, age: $age" => println(s"interpolator pattern: $name ${age.toInt + 1}")
