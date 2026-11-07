package scalago.linker

import scala.collection.mutable

import scalago.ir.*

/** Collects linking errors so that all of them can be reported at once. */
final class Errors:
  private val messages = mutable.LinkedHashSet.empty[String]
  def add(pos: Position, msg: String): Unit = messages += s"$pos: $msg"
  def add(msg: String): Unit = messages += msg
  def all: List[String] = messages.toList

/** Shared state for code generation after analysis: Go types, and the program-wide helpers
 *  (string literals, dispatchers, type tests) that method bodies request.
 */
final class LinkContext(val program: Program, val analyzer: Analyzer, val errors: Errors, val goApi: GoApi = GoApi.empty,
    val libraryMode: Boolean = false):
  /** Go facades: conversions, calls and Go types (see [[GoInterop]]). */
  val interop: GoInterop = GoInterop(this)

  /** Exports to Go (library builds, see [[Exports]]). */
  lazy val exports: ExportEmitter = ExportEmitter(this)

  /** Classes that exist at run time: instantiated ones and their ancestors. They get Go types. */
  val declared: Set[ClassName] =
    val instantiated = analyzer.instantiated.toSet
    (instantiated ++ instantiated.flatMap(program.ancestors)).filter(program.contains)

  /** Whether references of type `cls` are typed with its generated interface (rather than `any`). */
  def hasInterfaceType(cls: ClassName): Boolean =
    declared(cls) && !Hijacked.hijackedAncestors(cls) && !Hijacked.isHijacked(cls)

  def goType(t: Type): String = t match
    case Type.Void | Type.Nothing => ""
    case Type.Null                => "any"
    case Type.Class(name) if Hijacked.staticGoType.contains(name) => Hijacked.staticGoType(name)
    case Type.Class(name) if hasInterfaceType(name) => Mangling.iface(name)
    case _: Type.Class            => "any"
    case Type.Array(elem)         => s"*rt.Array[${goArrayElemType(elem)}]"
    case prim                     => Hijacked.primitiveGoType(prim)

  /** Whether field `cls.field` is volatile: it then has Go type `rt.Volatile[T]`. */
  def isVolatile(cls: ClassName, field: String): Boolean =
    program.get(cls).exists(_.fields.exists(f => f.name == field && f.isVolatile))

  /** The Go type of a field's storage. */
  def fieldGoType(f: FieldDef): String =
    if f.isVolatile then s"rt.Volatile[${goType(f.tpe)}]" else goType(f.tpe)

  /** Arrays of references all share one Go type, so that array covariance works. */
  def goArrayElemType(elem: Type): String =
    if elem.isPrimitive then Hijacked.primitiveGoType(elem) else "any"

  // ---- String literals ------------------------------------------------------------------------

  val stringLiterals = mutable.LinkedHashMap.empty[String, String]

  def stringLiteral(s: String): String = stringLiterals.getOrElseUpdate(s, s"SL_${stringLiterals.size}")

  /** `classOf` constants by JVM class name: package variables, so that using one is a load (the
   *  runtime interns `Class` objects by name).
   */
  val classLiterals = mutable.LinkedHashMap.empty[String, String]

  def classLiteral(jvmName: String): String = classLiterals.getOrElseUpdate(jvmName, s"CL_${classLiterals.size}")

  // ---- Dynamic dispatch and type tests ----------------------------------------------------------

  val dispatchers = mutable.LinkedHashSet.empty[MethodName]

  def dispatcher(m: MethodName): String =
    dispatchers += m
    Mangling.dispatcher(m)

  val instanceTests = mutable.LinkedHashSet.empty[ClassName]

  /** A Go boolean expression testing whether `e` (any reference) is an instance of `t`. */
  def typeTest(e: String, t: Type): String = t match
    case Type.Class(ClassName.Object)           => s"($e != nil)"
    case Type.Class(c) if program.isFacade(c)   => interop.typeTest(e, c)
    case Type.Class(c) if Hijacked.isHijacked(c) => s"rt.Is[${Hijacked.instanceGoType(c)}]($e)"
    case Type.Class(c) =>
      if Hijacked.subclassesOf(c).nonEmpty then
        instanceTests += c
        s"${Mangling.instanceTest(c)}($e)"
      else if declared(c) then ifaceTest(c, e)
      else s"rt.Never($e)"
    case Type.Array(elem) => s"rt.Is[*rt.Array[${goArrayElemType(elem)}]]($e)"
    case _                => "false"

  /** Go expression converting `e` (of IR type `from`) to IR type `to`, checking the cast. */
  def cast(e: String, from: Type, to: Type): String =
    val target = goType(to)
    val source = goType(from)
    to match
      case _ if from == to                       => e
      case Type.Class(c) if program.isFacade(c)  => interop.cast(e, c)
      case _                                     => castTo(e, to, source, target)

  private def castTo(e: String, to: Type, source: String, target: String): String =
    if target.isEmpty || target == source then e
    else if target == "any" then
      to match
        case Type.Class(c) if program.contains(c) && !declared(c) && !Hijacked.hijackedAncestors(c) => s"rt.CastNull($e)"
        case _ =>
          if source == "any" then e
          else if source.startsWith("*") then s"rt.AnyRef($e)" // a nil pointer becomes a nil `any`
          else s"any($e)"
    else to match
      case Type.Class(c) if target == Mangling.iface(c) => ifaceCast(c, e)
      case _                                            => s"rt.Cast[$target]($e)"

  // ---- Casts and type tests to class interfaces --------------------------------------------------

  /** Classes whose interface (`C_<class>`) is the target of casts or type tests. Each gets plain
   *  Go helpers in support.go: a type assertion in a generic function such as `rt.Cast[T]` is
   *  about three times slower than in a plain one, which has a per-site cache.
   */
  val ifaceCasts = mutable.LinkedHashSet.empty[ClassName]
  val ifaceTests = mutable.LinkedHashSet.empty[ClassName]

  /** Casts `e` (an `any`) to the interface of `cls`; null stays null. */
  def ifaceCast(cls: ClassName, e: String): String =
    ifaceCasts += cls
    s"${Mangling.ifaceCast(cls)}($e)"

  def ifaceTest(cls: ClassName, e: String): String =
    ifaceTests += cls
    s"${Mangling.ifaceTest(cls)}($e)"
