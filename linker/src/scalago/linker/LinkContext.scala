package scalago.linker

import scala.collection.mutable

import scalago.ir.*

/** Collects linking errors (and warnings) so that all of them can be reported at once. */
final class Errors:
  private val messages = mutable.LinkedHashSet.empty[String]
  private val warningMessages = mutable.LinkedHashSet.empty[String]
  def add(pos: Position, msg: String): Unit = messages += s"$pos: $msg"
  def add(msg: String): Unit = messages += msg
  def warn(pos: Position, msg: String): Unit = warningMessages += s"$pos: warning: $msg"
  def all: List[String] = messages.toList
  def warnings: List[String] = warningMessages.toList

/** Shared state for code generation after analysis: Go types, and the program-wide helpers
 *  (string literals, dispatchers, type tests) that method bodies request.
 */
final class LinkContext(val program: Program, val analyzer: Analyzer, val errors: Errors, val goApi: GoApi = GoApi.empty,
    val libraryMode: Boolean = false, val lineDirectives: Boolean = true):
  /** Go facades: conversions, calls and Go types (see [[GoInterop]]). */
  val interop: GoInterop = GoInterop(this)

  /** Exports to Go (library builds, see [[Exports]]). */
  lazy val exports: ExportEmitter = ExportEmitter(this)

  /** Classes that exist at run time: instantiated ones and their ancestors. They get Go types. */
  val declared: Set[ClassName] =
    val instantiated = analyzer.instantiated.toSet
    (instantiated ++ instantiated.flatMap(program.ancestors)).filter(program.contains)

  // ---- Go method sets ----------------------------------------------------------------------------
  //
  // A class's Go interface declares only the methods called on receivers of that static type
  // (its parents' interfaces declare theirs), so an instantiated class needs Go methods only for
  // those of its ancestors' interfaces, plus the methods called without a static class (through
  // dispatchers, by Go code or the runtime). Using every called method of the vtable instead would
  // give each collection class a Go method for every collection method called anywhere.

  /** Whether a call of `m` on a receiver of static type `cls` goes through `cls`'s Go interface
   *  (otherwise it goes through a dispatcher; see FunctionEmitter.genApply).
   */
  private def callsThroughInterface(cls: ClassName, m: MethodName): Boolean =
    goType(Type.Class(cls)) == Mangling.iface(cls) && program.vtable(cls).contains(m)

  /** Methods every class with them in its vtable needs as Go methods. */
  private lazy val untypedMethods: Set[MethodName] =
    analyzer.untypedCalls.toSet ++ analyzer.typedCalls.collect { case (c, m) if !callsThroughInterface(c, m) => m }

  private lazy val typedByClass: Map[ClassName, Set[MethodName]] =
    analyzer.typedCalls.toList.filter(callsThroughInterface).groupMap(_._1)(_._2).view.mapValues(_.toSet).toMap

  /** The methods `cls`'s Go interface declares (besides its parents'), in a stable order. Methods
   *  called without a static class are declared too: code generated outside method bodies (the
   *  entry point, exports, Go interop) calls them through class interfaces, and every class has
   *  them anyway.
   */
  def interfaceMethods(cls: ClassName): List[(MethodName, ClassName)] =
    val called = typedByClass.getOrElse(cls, Set.empty)
    program.vtable(cls).toList.filter((m, _) => called.contains(m) || untypedMethods.contains(m)).sortBy(_._1.descriptor)

  /** The virtual methods an instantiated class needs as Go methods, with their implementing class. */
  def goMethods(cls: ClassName): List[(MethodName, ClassName)] =
    val fromInterfaces = (program.ancestors(cls) + cls).flatMap(typedByClass.getOrElse(_, Set.empty))
    program.vtable(cls).toList
      .filter((m, _) => untypedMethods.contains(m) || fromInterfaces.contains(m))
      .sortBy(_._1.descriptor)

  // ---- Exact receivers ----------------------------------------------------------------------------

  /** For each class, the instantiated classes that are it or extend it. */
  private lazy val instantiatedSubclasses: Map[ClassName, Set[ClassName]] =
    analyzer.instantiated.toList.flatMap(c => (program.ancestors(c) + c).map(_ -> c)).groupMap(_._1)(_._2)
      .view.mapValues(_.toSet).toMap

  /** The class of every `this` in `cls`'s method bodies, if only one class can be: the only
   *  instantiated class that is `cls` or extends it (objects, lambdas, final and leaf classes,
   *  traits with one implementation). Those bodies take `this` as that class's struct pointer
   *  instead of an interface, so field accesses and calls on `this` are static Go calls, which
   *  Go inlines. Types that strings, boxes or arrays implement never qualify.
   */
  def exactReceiver(cls: ClassName): Option[ClassName] =
    if Hijacked.hijackedAncestors(cls) || Hijacked.isHijacked(cls) || program.isFacade(cls) then None
    else instantiatedSubclasses.get(cls).filter(_.size == 1).map(_.head)

  /** The Go type of `this` in `cls`'s method bodies. */
  def thisGoType(cls: ClassName): String =
    exactReceiver(cls).fold(goType(Type.Class(cls)))(d => s"*${Mangling.struct(d)}")

  /** Classes whose `Exact_<class>` helper (in support.go) converts receivers to their struct. */
  val exactReceiverConversions = mutable.LinkedHashSet.empty[ClassName]

  /** Converts a receiver `e` (any reference) for a method body whose `this` is exactly `cls`. */
  def toExactReceiver(cls: ClassName, e: String): String =
    exactReceiverConversions += cls
    s"${Mangling.exactReceiver(cls)}($e)"

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

  /** Whether field `cls.field` is volatile: it then has an atomic Go type (see `fieldGoType`). */
  def isVolatile(cls: ClassName, field: String): Boolean =
    program.get(cls).exists(_.fields.exists(f => f.name == field && f.isVolatile))

  /** The Go type of a field's storage. Volatile fields of primitive types are `sync/atomic`
   *  values (`rt.VolatileInt32`, ...), of reference types `rt.Volatile[T]` (an atomic pointer to
   *  the value). All have `Load`, `Store`, `CompareAndSet` and `GetAndSet`, integers `GetAndAdd`.
   */
  def fieldGoType(f: FieldDef): String =
    if !f.isVolatile then goType(f.tpe)
    else if f.tpe.isPrimitive then s"rt.Volatile${goType(f.tpe).capitalize}"
    else s"rt.Volatile[${goType(f.tpe)}]"

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
    case Type.Class(c) if Hijacked.arrayAncestors(c) => s"(rt.IsArray($e) || ${classTypeTest(e, c)})"
    case Type.Class(c) if program.isFacade(c)   => interop.typeTest(e, c)
    case Type.Class(c) if Hijacked.isHijacked(c) => s"rt.Is[${Hijacked.instanceGoType(c)}]($e)"
    case Type.Class(c)                           => classTypeTest(e, c)
    case Type.Array(elem) => s"rt.Is[*rt.Array[${goArrayElemType(elem)}]]($e)"
    case _                => "false"

  /** Whether `e` is an instance of the (non-hijacked) class `c`, leaving arrays aside. */
  private def classTypeTest(e: String, c: ClassName): String =
    if Hijacked.subclassesOf(c).nonEmpty then
      instanceTests += c
      s"${Mangling.instanceTest(c)}($e)"
    else if declared(c) then ifaceTest(c, e)
    else s"rt.Never($e)"

  /** Go expression converting `e` (of IR type `from`) to IR type `to`, checking the cast. */
  def cast(e: String, from: Type, to: Type): String =
    val target = goType(to)
    val source = goType(from)
    to match
      case _ if from == to                       => e
      case Type.Class(c) if program.isFacade(c)  => interop.cast(e, c)
      case _                                     => castTo(e, to, source, target)

  private def castTo(e: String, to: Type, source: String, target: String): String =
    if target.isEmpty then e
    else if target == "any" && source == "any" then
      // A downcast between types that are both Go `any`.
      to match
        case Type.Class(c) if needsCheckedCast(c) => checkedCast(c, e)
        case _                                    => e
    else if target == source then e
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

  // ---- Casts to types that strings and boxes implement ---------------------------------------------

  /** Whether a cast to `cls` must be checked although its references are Go `any`: the types that
   *  strings, boxes, other hijacked values and arrays implement (`Comparable`, `CharSequence`,
   *  `Number`, `Cloneable`, ...), and those classes themselves. Otherwise a value of the wrong
   *  class would go through and fail later, at a call, instead of with the JVM's
   *  `ClassCastException`.
   */
  private def needsCheckedCast(cls: ClassName): Boolean =
    cls != ClassName.Object && (Hijacked.isHijacked(cls) || Hijacked.hijackedAncestors(cls))

  /** Classes casts to which are checked by a `CheckCast_<class>` helper in support.go. */
  val checkedCasts = mutable.LinkedHashSet.empty[ClassName]

  private def checkedCast(cls: ClassName, e: String): String =
    checkedCasts += cls
    s"${Mangling.checkedCast(cls)}($e)"

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
