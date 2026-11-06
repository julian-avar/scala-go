package scalago.ir

/** A source position, kept for diagnostics only. */
final case class Position(source: String, line: Int, column: Int):
  override def toString: String = if this == Position.NoPosition then "<no position>" else s"$source:$line:$column"

object Position:
  val NoPosition: Position = Position("", 0, 0)

// ---------------------------------------------------------------------------------------------
// Definitions
// ---------------------------------------------------------------------------------------------

enum ClassKind:
  case Class, ModuleClass, Interface

final case class ClassDef(
    name: ClassName,
    kind: ClassKind,
    isAbstract: Boolean,
    superClass: Option[ClassName],
    interfaces: List[ClassName],
    fields: List[FieldDef],
    methods: List[MethodDef],
    pos: Position,
    /** Set for Go facades: classes standing for a Go package or a Go type (see [[GoFacade]]). */
    goFacade: Option[GoFacade] = None,
    /** Set for classes exported to Go (`@GoExport`): the Go type name. */
    goExport: Option[String] = None
)

/** A Scala declaration of Go code (`@GoImport`). Facades have no IR bodies: the linker turns uses
 *  of their members into Go code, converting values at the boundary.
 *
 *  @param pkg  the Go import path; `""` for builtin types (`error`)
 *  @param name the Go type name, or `""` for an object standing for a whole Go package
 */
final case class GoFacade(pkg: String, name: String):
  def isPackage: Boolean = name.isEmpty

/** A field. `isVolatile` fields (`@volatile`, and lazy val states) are accessed atomically. */
final case class FieldDef(name: String, tpe: Type, isStatic: Boolean, pos: Position, isVolatile: Boolean = false)

final case class ParamDef(name: LocalName, tpe: Type)

/** `body == None` for abstract methods. Private methods are never dispatched virtually. */
final case class MethodDef(
    name: MethodName,
    params: List[ParamDef],
    body: Option[Tree],
    isStatic: Boolean,
    isPrivate: Boolean,
    pos: Position,
    /** Members of Go facades: the Go name and the Scala (pre-erasure) signature. */
    goMember: Option[GoMember] = None,
    /** Methods and constructors exported to Go (`@GoExport`). */
    goExport: Option[GoExport] = None
):
  def isAbstract: Boolean = body.isEmpty

/** A member of a Go facade.
 *
 *  @param goName     the Go function, method, field or variable it stands for
 *  @param params     the Scala types of the parameters, before erasure
 *  @param result     the Scala result type, before erasure
 *  @param isProperty declared without a parameter list (`def X: T`): may be a Go field or variable
 */
final case class GoMember(goName: String, params: List[ScalaType], result: ScalaType, isProperty: Boolean)

/** A method (of a top-level object or an exported class) or constructor exported to Go.
 *
 *  @param goName       the Go function or method name (`New<Type>` for constructors)
 *  @param params       the Scala types of the parameters, before erasure
 *  @param result       the Scala result type, before erasure
 *  @param returnsError the method declares `@throws`: Scala exceptions become a trailing `error`
 *                      result (otherwise they are Go panics)
 */
final case class GoExport(goName: String, params: List[ScalaType], result: ScalaType, returnsError: Boolean)

/** A Scala type before erasure, as far as Go interop needs it: type arguments of functions,
 *  tuples, sequences and arrays, and opaque types such as `scalago.go.GoString`.
 */
enum ScalaType:
  /** A primitive type, or `Void` for `Unit`. */
  case Prim(tpe: Type)
  /** A class or trait (binary name) or an opaque type (full name), with its type arguments. */
  case Ref(name: String, args: List[ScalaType])
  case Arr(elem: ScalaType)

  override def toString: String = this match
    case Prim(t)         => t.toString
    case Ref(name, Nil)  => name
    case Ref(name, args) => args.mkString(s"$name[", ", ", "]")
    case Arr(elem)       => s"Array[$elem]"

/** Local variable names are unique within a method body (the plugin renames shadowed locals). */
type LocalName = String

// ---------------------------------------------------------------------------------------------
// Operators
// ---------------------------------------------------------------------------------------------

enum UnaryOp:
  /** Arithmetic negation, bitwise not, boolean not. Operand and result types are equal. */
  case Neg, BitNot, Not

enum BinaryOp:
  /** Arithmetic and bitwise ops: both operands are already converted to the operation type. */
  case Add, Sub, Mul, Div, Rem, Or, And, Xor
  /** Shifts: the lhs has the operation type; the rhs (shift count) is Int or Long. */
  case Shl, Shr, UShr
  /** Primitive comparisons: both operands are already converted to a common type. Result Boolean. */
  case Eq, Ne, Lt, Le, Gt, Ge
  /** Reference identity (`eq`/`ne`). */
  case RefEq, RefNe
  /** Scala universal equality on references (`==`/`!=`), null-safe and numerically cooperative. */
  case UnivEq, UnivNe
  /** `String.valueOf(lhs) + String.valueOf(rhs)`. */
  case StringConcat

// ---------------------------------------------------------------------------------------------
// Trees
// ---------------------------------------------------------------------------------------------

sealed trait Tree extends Product:
  def tpe: Type
  def pos: Position

object Tree:
  // ---- Statements and control flow -------------------------------------------------------

  final case class VarDef(name: LocalName, vtpe: Type, mutable: Boolean, rhs: Tree)(val pos: Position) extends Tree:
    def tpe: Type = Type.Void

  /** Evaluates `stats` for their effects, then `expr`, whose value is the value of the block. */
  final case class Block(stats: List[Tree], expr: Tree)(val pos: Position) extends Tree:
    def tpe: Type = expr.tpe

  final case class Skip()(val pos: Position) extends Tree:
    def tpe: Type = Type.Void

  /** A block that can be exited early with `Return(label, value)`. */
  final case class Labeled(label: String, tpe: Type, body: Tree)(val pos: Position) extends Tree

  /** Returns from the enclosing method (`label == None`) or exits the enclosing `Labeled`. */
  final case class Return(label: Option[String], expr: Tree)(val pos: Position) extends Tree:
    def tpe: Type = Type.Nothing

  final case class If(cond: Tree, thenp: Tree, elsep: Tree, tpe: Type)(val pos: Position) extends Tree

  final case class While(cond: Tree, body: Tree)(val pos: Position) extends Tree:
    def tpe: Type = Type.Void

  /** `lhs` is a `VarRef`, `Select`, `SelectStatic` or `ArraySelect`. */
  final case class Assign(lhs: Tree, rhs: Tree)(val pos: Position) extends Tree:
    def tpe: Type = Type.Void

  // ---- Literals ------------------------------------------------------------------------------

  final case class BooleanLiteral(value: scala.Boolean)(val pos: Position) extends Tree:
    def tpe: Type = Type.Boolean
  final case class CharLiteral(value: scala.Char)(val pos: Position) extends Tree:
    def tpe: Type = Type.Char
  final case class ByteLiteral(value: scala.Byte)(val pos: Position) extends Tree:
    def tpe: Type = Type.Byte
  final case class ShortLiteral(value: scala.Short)(val pos: Position) extends Tree:
    def tpe: Type = Type.Short
  final case class IntLiteral(value: scala.Int)(val pos: Position) extends Tree:
    def tpe: Type = Type.Int
  final case class LongLiteral(value: scala.Long)(val pos: Position) extends Tree:
    def tpe: Type = Type.Long
  final case class FloatLiteral(value: scala.Float)(val pos: Position) extends Tree:
    def tpe: Type = Type.Float
  final case class DoubleLiteral(value: scala.Double)(val pos: Position) extends Tree:
    def tpe: Type = Type.Double
  final case class StringLiteral(value: String)(val pos: Position) extends Tree:
    def tpe: Type = Type.String
  final case class Null()(val pos: Position) extends Tree:
    def tpe: Type = Type.Null

  // ---- References ----------------------------------------------------------------------------

  final case class VarRef(name: LocalName, tpe: Type)(val pos: Position) extends Tree
  final case class This(tpe: Type)(val pos: Position) extends Tree
  final case class LoadModule(cls: ClassName)(val pos: Position) extends Tree:
    def tpe: Type = Type.Class(cls)

  /** Instance field `cls.field` of `qualifier`. */
  final case class Select(qualifier: Tree, cls: ClassName, field: String, tpe: Type)(val pos: Position) extends Tree
  final case class SelectStatic(cls: ClassName, field: String, tpe: Type)(val pos: Position) extends Tree

  /** Atomically sets a volatile field (a `Select` or `SelectStatic`) to `update` if it holds
   *  `expected` (reference identity for objects); true if it did. Lazy vals and
   *  `scalago.runtime.Intrinsics.compareAndSet` produce it.
   */
  final case class CompareAndSet(field: Tree, expected: Tree, update: Tree)(val pos: Position) extends Tree:
    def tpe: Type = Type.Boolean

  // ---- Calls ---------------------------------------------------------------------------------

  /** Allocates an instance of `cls` and runs constructor `ctor` on it. */
  final case class New(cls: ClassName, ctor: MethodName, args: List[Tree])(val pos: Position) extends Tree:
    def tpe: Type = Type.Class(cls)

  /** Virtual call. `declaringClass` is the class in which the compiler resolved `method`. */
  final case class Apply(receiver: Tree, declaringClass: ClassName, method: MethodName, args: List[Tree], tpe: Type)(
      val pos: Position
  ) extends Tree

  /** Non-virtual call of the implementation in `cls`: super calls, private methods, constructor chaining. */
  final case class ApplyStatically(receiver: Tree, cls: ClassName, method: MethodName, args: List[Tree], tpe: Type)(
      val pos: Position
  ) extends Tree

  /** Call of a static method. */
  final case class ApplyStatic(cls: ClassName, method: MethodName, args: List[Tree], tpe: Type)(val pos: Position)
      extends Tree

  // ---- Primitive operations ------------------------------------------------------------------

  final case class Unary(op: UnaryOp, arg: Tree)(val pos: Position) extends Tree:
    def tpe: Type = arg.tpe

  /** `opType` is the type the operands were converted to (for shifts: the lhs type). */
  final case class Binary(op: BinaryOp, opType: Type, lhs: Tree, rhs: Tree)(val pos: Position) extends Tree:
    def tpe: Type = op match
      case BinaryOp.Eq | BinaryOp.Ne | BinaryOp.Lt | BinaryOp.Le | BinaryOp.Gt | BinaryOp.Ge | BinaryOp.RefEq |
          BinaryOp.RefNe | BinaryOp.UnivEq | BinaryOp.UnivNe =>
        Type.Boolean
      case BinaryOp.StringConcat => Type.String
      case _                     => opType

  /** Numeric conversion between primitive types (`Int -> Long`, `Double -> Int`, ...). */
  final case class Convert(arg: Tree, tpe: Type)(val pos: Position) extends Tree

  /** Boxes a primitive into its `java.lang` box class. */
  final case class Box(arg: Tree)(val pos: Position) extends Tree:
    def tpe: Type = Type.Object

  /** Unboxes a reference into primitive type `tpe` (`null` unboxes to the zero value). */
  final case class Unbox(arg: Tree, tpe: Type)(val pos: Position) extends Tree

  final case class AsInstanceOf(arg: Tree, tpe: Type)(val pos: Position) extends Tree

  // ---- Arrays --------------------------------------------------------------------------------

  final case class NewArray(elem: Type, length: Tree)(val pos: Position) extends Tree:
    def tpe: Type = Type.Array(elem)
  final case class ArrayValue(elem: Type, elems: List[Tree])(val pos: Position) extends Tree:
    def tpe: Type = Type.Array(elem)
  final case class ArrayLength(array: Tree)(val pos: Position) extends Tree:
    def tpe: Type = Type.Int
  final case class ArraySelect(array: Tree, index: Tree, tpe: Type)(val pos: Position) extends Tree

  // ---- Type tests and classes ----------------------------------------------------------------

  final case class IsInstanceOf(expr: Tree, testType: Type)(val pos: Position) extends Tree:
    def tpe: Type = Type.Boolean

  /** `classOf[T]`: the `java.lang.Class` of `cls`. */
  final case class ClassOf(cls: Type)(val pos: Position) extends Tree:
    def tpe: Type = Type.Class("java.lang.Class")

  // ---- Exceptions ----------------------------------------------------------------------------

  final case class Throw(expr: Tree)(val pos: Position) extends Tree:
    def tpe: Type = Type.Nothing

  /** One `catch` clause: catches instances of `tpe` (a class type), optionally binding them. */
  final case class CatchCase(binder: Option[LocalName], tpe: Type, body: Tree)

  /** Exceptions not matched by any case are rethrown. */
  final case class TryCatch(block: Tree, cases: List[CatchCase], tpe: Type)(val pos: Position) extends Tree

  final case class TryFinally(block: Tree, finalizer: Tree, tpe: Type)(val pos: Position) extends Tree

  // ---- Switches ------------------------------------------------------------------------------

  /** A switch on an Int, Char, Byte, Short or String value. Each case lists literal alternatives. */
  final case class Match(selector: Tree, cases: List[(List[Tree], Tree)], default: Tree, tpe: Type)(
      val pos: Position
  ) extends Tree

  // ---- Closures ------------------------------------------------------------------------------

  /** A lambda: an instance of the functional interface `samInterface` whose abstract method
   *  `samMethod` calls `targetMethod` of `targetClass` with `captures` followed by its own
   *  arguments. For a non-static target the first capture is the receiver.
   *  Signatures may differ by boxing and casts, which the linker adapts.
   */
  final case class Closure(
      samInterface: ClassName,
      samMethod: MethodName,
      targetClass: ClassName,
      targetMethod: MethodName,
      targetIsStatic: Boolean,
      captures: List[Tree]
  )(val pos: Position)
      extends Tree:
    def tpe: Type = Type.Class(samInterface)

  // ---- Unsupported ---------------------------------------------------------------------------

  /** Code the plugin could not translate (in lenient mode). Linking fails if it is reachable. */
  final case class Unsupported(message: String, tpe: Type)(val pos: Position) extends Tree
end Tree

/** Generic traversal of IR trees. */
object Traversal:
  import Tree.*

  /** The direct subtrees of `tree`, in evaluation order. */
  def children(tree: Tree): List[Tree] = tree match
    case VarDef(_, _, _, rhs)                   => List(rhs)
    case Block(stats, expr)                     => stats :+ expr
    case Labeled(_, _, body)                    => List(body)
    case Return(_, expr)                        => List(expr)
    case If(c, t, e, _)                         => List(c, t, e)
    case While(c, b)                            => List(c, b)
    case Assign(l, r)                           => List(l, r)
    case Select(q, _, _, _)                     => List(q)
    case CompareAndSet(f, e, u)                 => List(f, e, u)
    case New(_, _, args)                        => args
    case Apply(r, _, _, args, _)                => r :: args
    case ApplyStatically(r, _, _, args, _)      => r :: args
    case ApplyStatic(_, _, args, _)             => args
    case Unary(_, a)                            => List(a)
    case Binary(_, _, l, r)                     => List(l, r)
    case Convert(a, _)                          => List(a)
    case Box(a)                                 => List(a)
    case Unbox(a, _)                            => List(a)
    case AsInstanceOf(a, _)                     => List(a)
    case NewArray(_, l)                         => List(l)
    case ArrayValue(_, elems)                   => elems
    case ArrayLength(a)                         => List(a)
    case ArraySelect(a, i, _)                   => List(a, i)
    case IsInstanceOf(e, _)                     => List(e)
    case Throw(e)                               => List(e)
    case TryCatch(b, cases, _)                  => b :: cases.map(_.body)
    case TryFinally(b, f, _)                    => List(b, f)
    case Match(s, cases, d, _)                  => (s :: cases.flatMap((alts, body) => alts :+ body)) :+ d
    case Closure(_, _, _, _, _, caps)           => caps
    case _: Skip | _: BooleanLiteral | _: CharLiteral | _: ByteLiteral | _: ShortLiteral | _: IntLiteral |
        _: LongLiteral | _: FloatLiteral | _: DoubleLiteral | _: StringLiteral | _: Null | _: VarRef | _: This |
        _: LoadModule | _: SelectStatic | _: ClassOf | _: Unsupported =>
      Nil

  def foreach(tree: Tree)(f: Tree => Unit): Unit =
    f(tree)
    children(tree).foreach(foreach(_)(f))

  def exists(tree: Tree)(p: Tree => Boolean): Boolean =
    p(tree) || children(tree).exists(exists(_)(p))

  /** Rebuilds `tree` bottom-up, applying `f` to every node after its children. */
  def transform(tree: Tree)(f: PartialFunction[Tree, Tree]): Tree =
    def t(x: Tree): Tree = transform(x)(f)
    def ts(xs: List[Tree]): List[Tree] = xs.map(t)
    val p = tree.pos
    val rebuilt = tree match
      case VarDef(n, tp, m, rhs)              => VarDef(n, tp, m, t(rhs))(p)
      case Block(stats, expr)                 => Block(ts(stats), t(expr))(p)
      case Labeled(l, tp, body)               => Labeled(l, tp, t(body))(p)
      case Return(l, expr)                    => Return(l, t(expr))(p)
      case If(c, th, el, tp)                  => If(t(c), t(th), t(el), tp)(p)
      case While(c, b)                        => While(t(c), t(b))(p)
      case Assign(l, r)                       => Assign(t(l), t(r))(p)
      case Select(q, c, fl, tp)               => Select(t(q), c, fl, tp)(p)
      case CompareAndSet(f, e, u)             => CompareAndSet(t(f), t(e), t(u))(p)
      case New(c, m, args)                    => New(c, m, ts(args))(p)
      case Apply(r, c, m, args, tp)           => Apply(t(r), c, m, ts(args), tp)(p)
      case ApplyStatically(r, c, m, args, tp) => ApplyStatically(t(r), c, m, ts(args), tp)(p)
      case ApplyStatic(c, m, args, tp)        => ApplyStatic(c, m, ts(args), tp)(p)
      case Unary(op, a)                       => Unary(op, t(a))(p)
      case Binary(op, ot, l, r)               => Binary(op, ot, t(l), t(r))(p)
      case Convert(a, tp)                     => Convert(t(a), tp)(p)
      case Box(a)                             => Box(t(a))(p)
      case Unbox(a, tp)                       => Unbox(t(a), tp)(p)
      case AsInstanceOf(a, tp)                => AsInstanceOf(t(a), tp)(p)
      case NewArray(e, l)                     => NewArray(e, t(l))(p)
      case ArrayValue(e, elems)               => ArrayValue(e, ts(elems))(p)
      case ArrayLength(a)                     => ArrayLength(t(a))(p)
      case ArraySelect(a, i, tp)              => ArraySelect(t(a), t(i), tp)(p)
      case IsInstanceOf(e, tp)                => IsInstanceOf(t(e), tp)(p)
      case Throw(e)                           => Throw(t(e))(p)
      case TryCatch(b, cases, tp)             => TryCatch(t(b), cases.map(c => c.copy(body = t(c.body))), tp)(p)
      case TryFinally(b, fin, tp)             => TryFinally(t(b), t(fin), tp)(p)
      case Match(s, cases, d, tp)             => Match(t(s), cases.map((alts, body) => (alts, t(body))), t(d), tp)(p)
      case Closure(i, sm, tc, tm, st, caps)   => Closure(i, sm, tc, tm, st, ts(caps))(p)
      case leaf                               => leaf
    f.applyOrElse(rebuilt, identity)
