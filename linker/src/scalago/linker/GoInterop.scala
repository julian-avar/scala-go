package scalago.linker

import scala.collection.mutable

import scalago.ir.*
import scalago.ir.Tree.*

/** Code generation for Go facades (`@GoImport`): calls from Scala into Go packages, Go methods
 *  of Scala classes implementing Go interfaces, and type tests on Go types.
 *
 *  Values are converted according to two types: the Scala type the facade declares (before
 *  erasure, [[ScalaType]]) and the Go type of the real declaration ([[GoType]], from [[GoApi]]).
 *  Facade references hold the Go value in an `any`: a pointer for Go struct types, the value
 *  itself for every other type.
 */
object GoInterop:
  val SeqClass: ClassName = "scala.collection.immutable.Seq"
  val SeqLength: MethodName = MethodName("length", Nil, Type.Int)
  val SeqApply: MethodName = MethodName("apply", List(Type.Int), Type.Object)
  val GoException: ClassName = "scalago.go.GoException"
  val GoErrorClass: ClassName = "scalago.go.GoError"
  val GoExceptionConstructor: MethodName = MethodName(MethodName.ConstructorSimpleName, List(Type.Class(GoErrorClass)), Type.Void)
  val GoStringType = "scalago.go.GoString"
  private val ScalaRunTime: ClassName = "scala.runtime.ScalaRunTime$"

  /** The array of a varargs argument built by the compiler (`ScalaRunTime.wrapRefArray(Array(...))`). */
  def varargsArray(tree: Tree): Option[Tree] = tree match
    case Apply(LoadModule(ScalaRunTime), _, m, List(array), _)
        if (m.simple.startsWith("wrap") || m.simple == "genericWrapArray") && array.tpe.isInstanceOf[Type.Array] =>
      Some(array)
    case _ => None

  /** The erased IR type of values of Scala type `t`. */
  def erased(t: ScalaType): Type = t match
    case ScalaType.Prim(p)              => p
    case ScalaType.Ref(GoStringType, _) => Type.Object
    case ScalaType.Ref(name, _)         => Type.Class(name)
    case ScalaType.Arr(e)               => Type.Array(erased(e))

  /** The facade's arguments: varargs built by the compiler are passed as their array. */
  def facadeArgs(args: List[Tree], params: List[ScalaType]): List[(Tree, ScalaType)] =
    args.zip(params).map { (arg, t) =>
      (t, varargsArray(arg)) match
        case (ScalaType.Ref(SeqClass, List(elem)), Some(array)) => (array, ScalaType.Arr(elem))
        case _                                                  => (arg, t)
    }

  private final class Unconvertible(message: String) extends Exception(message)

  /** A class name as written in Scala (`strings$Builder` is `strings.Builder`). */
  def scalaName(cls: ClassName): String = cls.stripSuffix("$").replace('$', '.')

  private object Arity:
    def unapply(name: String): Option[(String, Int)] =
      List("scala.Function", "scala.Tuple").collectFirst {
        case prefix if name.startsWith(prefix) && name.stripPrefix(prefix).toIntOption.isDefined =>
          (prefix, name.stripPrefix(prefix).toInt)
      }

  private val integerBasics = Set("int", "int8", "int16", "int32", "int64", "uint", "uint8", "uint16", "uint32", "uint64",
    "uintptr", "byte", "rune")
  private val numericBasics = integerBasics ++ Set("float32", "float64")

  private def normalizeBasic(b: String): String = b match
    case "byte" => "uint8"
    case "rune" => "int32"
    case other  => other.stripPrefix("untyped-")
end GoInterop

final class GoInterop(ctx: LinkContext):
  import GoInterop.*
  import ScalaType.{Arr, Prim, Ref}

  private def api = ctx.goApi
  private def program = ctx.program

  // ---- Packages ---------------------------------------------------------------------------------

  /** Import aliases used by the generated code, to Go import paths. */
  val imports = mutable.LinkedHashMap.empty[String, String]
  private val aliases = mutable.HashMap.empty[String, String]

  /** `gp_<last path element>`, numbered when two packages would get the same alias. */
  private def alias(path: String): String =
    aliases.getOrElseUpdate(path, {
      val base = s"gp_${Mangling.escape(path.split('/').last)}"
      val a = Iterator.from(1).map(i => if i == 1 then base else s"${base}_$i").find(!imports.contains(_)).get
      imports(a) = path
      a
    })

  private def qualified(pkg: String, name: String): String = if pkg.isEmpty then name else s"${alias(pkg)}.$name"

  // ---- Types --------------------------------------------------------------------------------------

  private def fail(message: String): Nothing = throw Unconvertible(message)

  /** Reports conversion problems as link errors at `pos`. */
  private def reporting(pos: Position, what: => String)(body: => String): String =
    try body
    catch
      case e: Unconvertible =>
        ctx.errors.add(pos, s"$what: ${e.getMessage}")
        "nil"

  /** The Go spelling of `t`, with package aliases. */
  def typeString(t: GoType): String = t match
    case GoType.Basic(b) if !b.startsWith("untyped") && (numericBasics(b) || b == "string" || b == "bool") => b
    case GoType.Named(p, n)    => qualified(p, n)
    case GoType.Ptr(e)         => s"*${typeString(e)}"
    case GoType.Slice(e)       => s"[]${typeString(e)}"
    case GoType.ArrayOf(n, e)  => s"[$n]${typeString(e)}"
    case GoType.MapOf(k, v)    => s"map[${typeString(k)}]${typeString(v)}"
    case GoType.Chan(e)        => s"chan ${typeString(e)}"
    case GoType.Func(sig)      => s"func${signatureString(sig, None)}"
    case GoType.EmptyInterface => "any"
    case other                 => fail(s"Go type $other is not supported")

  private def signatureString(sig: GoSignature, paramNames: Option[List[String]]): String =
    val params = sig.params.zipWithIndex.map { (t, i) =>
      val tpe =
        if sig.variadic && i == sig.params.size - 1 then s"...${typeString(t.asInstanceOf[GoType.Slice].elem)}"
        else typeString(t)
      paramNames.fold(tpe)(names => s"${names(i)} $tpe")
    }
    val results = sig.results.map(typeString) match
      case Nil       => ""
      case List(one) => s" $one"
      case several   => several.mkString(" (", ", ", ")")
    s"(${params.mkString(", ")})$results"

  /** A Go type that a facade class stands for. */
  private final case class FacadeType(facade: GoFacade, decl: GoTypeDecl):
    def name: String = qualified(facade.pkg, facade.name)
    def isStruct: Boolean = decl.kind == GoTypeKind.Struct
    /** The Go type of the values facade references hold. */
    def stored: String = if isStruct then s"*$name" else name

  private def facadeType(cls: ClassName): FacadeType =
    val facade = program.facade(cls).flatMap(_.goFacade).getOrElse(fail(s"$cls is not a Go facade"))
    if facade.isPackage then fail(s"$cls stands for a Go package, not a type")
    val decl = api.typeDecl(facade.pkg, facade.name)
      .getOrElse(fail(s"Go package ${facade.pkg} has no exported type ${facade.name} (facade ${scalaName(cls)})"))
    FacadeType(facade, decl)

  /** Whether facade `cls` stands for the Go named type `pkg.name` (directly or through an alias). */
  private def standsFor(cls: ClassName, pkg: String, name: String): Boolean =
    program.facade(cls).flatMap(_.goFacade).exists { f =>
      (f.pkg == pkg && f.name == name) ||
      api.packages.get(f.pkg).flatMap(_.aliases.get(f.name)).contains(GoType.Named(pkg, name))
    }

  private def namedDecl(pkg: String, name: String): GoTypeDecl =
    api.typeDecl(pkg, name).getOrElse(fail(s"unknown Go type ${qualified(pkg, name)}"))

  private def scalaGoType(s: ScalaType): String =
    val t = ctx.goType(erased(s))
    if t.isEmpty then "any" else t

  // ---- Scala to Go ------------------------------------------------------------------------------

  private var counter = 0
  private def fresh(prefix: String): String =
    counter += 1
    s"$prefix$counter"

  /** Converts `e`, a Go expression for a Scala value of type `s`, to Go type `g`. */
  def toGo(e: String, s: ScalaType, g: GoType): String = (s, g) match
    case (_, GoType.Basic(b)) => basicToGo(e, s, b)
    case (Prim(p), GoType.Named(pkg, n)) if p.isNumeric && namedDecl(pkg, n).kind == GoTypeKind.Other =>
      s"${qualified(pkg, n)}($e)"
    case (Ref(cls, _), GoType.Named(pkg, n)) =>
      val decl = namedDecl(pkg, n)
      val goT = qualified(pkg, n)
      decl.kind match
        case GoTypeKind.Interface => s"rt.Cast[$goT]($e)"
        case GoTypeKind.Struct if standsFor(cls, pkg, n) => s"rt.Deref[$goT]($e)"
        case GoTypeKind.Other if standsFor(cls, pkg, n)  => s"rt.Cast[$goT]($e)"
        case GoTypeKind.Other if cls == ClassName.String  => s"$goT($e.GoString())"
        case _ => fail(s"cannot pass Scala type $s as Go type $g")
    case (Ref(cls, _), GoType.Ptr(GoType.Named(pkg, n))) if standsFor(cls, pkg, n) =>
      s"rt.Cast[*${qualified(pkg, n)}]($e)"
    case (Arr(se), GoType.Slice(ge))                 => sliceToGo(e, se, ge)
    case (Ref(SeqClass, List(se)), GoType.Slice(ge)) => seqToGo(e, se, ge)
    case (Ref(Arity("scala.Function", _), args), GoType.Func(sig)) => funcToGo(e, args, sig)
    case (Prim(p), GoType.EmptyInterface) if p.isPrimitive => s"any($e)"
    case (Ref(ClassName.String | ClassName.Object, _), GoType.EmptyInterface) => s"rt.ToGoAny($e)"
    case (_: Ref, GoType.EmptyInterface)              => e
    case _ => fail(s"cannot pass Scala type $s as Go type $g")

  private def basicToGo(e: String, s: ScalaType, b: String): String = (s, normalizeBasic(b)) match
    case (Ref(ClassName.String, _), "string") => s"$e.GoString()"
    case (Ref(GoStringType, _), "string")     => s"rt.Cast[string]($e)"
    case (Prim(Type.Boolean), "bool")         => e
    case (Prim(p), goB) if p.isNumeric && numericBasics(goB) =>
      if Hijacked.primitiveGoType(p) == goB then e else s"$b($e)"
    case _ => fail(s"cannot pass Scala type $s as Go type ${b.replace('-', ' ')}")

  private def sliceToGo(e: String, se: ScalaType, ge: GoType): String = (se, ge) match
    case (Prim(Type.Byte), GoType.Basic(b)) if normalizeBasic(b) == "uint8" => s"rt.Reinterpret[int8, uint8](rt.SliceOf($e))"
    case (Prim(p), GoType.Basic(b)) if p.isPrimitive && Hijacked.primitiveGoType(p) == normalizeBasic(b) => s"rt.SliceOf($e)"
    case _ =>
      val x = fresh("x")
      val elemType = ctx.goArrayElemType(erased(se))
      val value = if elemType == "any" then ctx.cast(x, Type.Object, erased(se)) else x
      s"rt.ConvertSlice($e, func($x $elemType) ${typeString(ge)} { return ${toGo(value, se, ge)} })"

  private def seqToGo(e: String, se: ScalaType, ge: GoType): String =
    val (s, out, i) = (fresh("s"), fresh("out"), fresh("i"))
    val elem = ctx.cast(s"${ctx.dispatcher(SeqApply)}($s, int32($i))", Type.Object, erased(se))
    val goT = typeString(ge)
    s"func($s any) []$goT { $out := make([]$goT, ${ctx.dispatcher(SeqLength)}($s)); " +
      s"for $i := range $out { $out[$i] = ${toGo(elem, se, ge)} }; return $out }($e)"

  /** A Scala function as a Go func: calls `apply` on it, converting arguments and result. */
  private def funcToGo(e: String, args: List[ScalaType], sig: GoSignature): String =
    if sig.variadic || sig.generic then fail(s"variadic or generic Go func types are not supported")
    if sig.params.size != args.size - 1 then
      fail(s"the Go func takes ${sig.params.size} parameters, the Scala function ${args.size - 1}")
    val f = fresh("f")
    val names = sig.params.indices.map(_ => fresh("p")).toList
    val apply = ctx.dispatcher(MethodName("apply", List.fill(names.size)(Type.Object), Type.Object))
    val scalaArgs = names.zip(sig.params).zip(args.init).map { case ((p, g), s) => boxed(fromGo(p, g, s), s) }
    val call = s"$apply(${(f :: scalaArgs).mkString(", ")})"
    val body = sig.results match
      case Nil       => call
      case List(res) => s"return ${toGo(ctx.cast(call, Type.Object, erased(args.last)), args.last, res)}"
      case _         => fail("Go func types with several results are not supported")
    val funcType = s"func${signatureString(sig, None)}"
    s"func($f any) $funcType { if $f == nil { return nil }; return func${signatureString(sig, Some(names))} " +
      s"{ defer rt.GoCallback(); $body } }($e)"

  private def boxed(e: String, s: ScalaType): String = s match
    case Prim(p) if p.isPrimitive => s"any($e)"
    case _                        => e

  // ---- Go to Scala ------------------------------------------------------------------------------

  /** Converts `e`, a Go expression of Go type `g`, to a Go expression for a Scala value of type `s`. */
  def fromGo(e: String, g: GoType, s: ScalaType): String = (g, s) match
    case (GoType.Basic(b), _) => basicFromGo(e, b, s)
    case (GoType.Named(pkg, n), Prim(p)) if p.isNumeric && namedDecl(pkg, n).kind == GoTypeKind.Other =>
      s"${Hijacked.primitiveGoType(p)}($e)"
    case (GoType.Named(pkg, n), Ref(cls, _)) =>
      namedDecl(pkg, n).kind match
        case GoTypeKind.Interface => ctx.cast(s"any($e)", Type.Object, erased(s))
        case GoTypeKind.Struct if standsFor(cls, pkg, n) => s"rt.Addr($e)"
        case GoTypeKind.Other if standsFor(cls, pkg, n) => s"any($e)"
        case GoTypeKind.Other if cls == ClassName.String => s"rt.Str(string($e))"
        case _ => fail(s"cannot convert Go type $g to Scala type $s")
    case (GoType.Ptr(GoType.Named(pkg, n)), Ref(cls, _)) if standsFor(cls, pkg, n) => s"rt.FromPtr($e)"
    case (GoType.Slice(ge), Arr(se))                => sliceFromGo(e, ge, se)
    case (GoType.EmptyInterface, Prim(p)) if p.isPrimitive => s"rt.Cast[${Hijacked.primitiveGoType(p)}]($e)"
    case (GoType.EmptyInterface, _: Ref)            => ctx.cast(e, Type.Object, erased(s))
    case _ => fail(s"cannot convert Go type $g to Scala type $s")

  private def basicFromGo(e: String, b: String, s: ScalaType): String = (normalizeBasic(b), s) match
    case ("string", Ref(ClassName.String, _))           => s"rt.Str($e)"
    case ("string", Ref(GoStringType | ClassName.Object, _)) => s"any($e)"
    case ("bool", Prim(Type.Boolean))                   => e
    case (goB, Prim(p)) if p.isNumeric && (numericBasics(goB) || goB == "int" || goB == "float" || goB == "rune") =>
      if Hijacked.primitiveGoType(p) == goB then e else s"${Hijacked.primitiveGoType(p)}($e)"
    case _ => fail(s"cannot convert Go type ${b.replace('-', ' ')} to Scala type $s")

  private def sliceFromGo(e: String, ge: GoType, se: ScalaType): String = (ge, se) match
    case (GoType.Basic(b), Prim(Type.Byte)) if normalizeBasic(b) == "uint8" => s"rt.ArrayOfSlice(rt.Reinterpret[uint8, int8]($e))"
    case (GoType.Basic(b), Prim(p)) if p.isPrimitive && Hijacked.primitiveGoType(p) == normalizeBasic(b) => s"rt.ArrayOfSlice($e)"
    case _ =>
      val x = fresh("x")
      s"rt.ConvertToArray($e, func($x ${typeString(ge)}) ${ctx.goArrayElemType(erased(se))} { return ${fromGo(x, ge, se)} })"

  // ---- Calls --------------------------------------------------------------------------------------

  /** Go code for a use of facade member `member` of `owner`.
   *
   *  @param receiver the receiver (for members of Go types)
   *  @param args     Go expressions for the arguments, of the Scala types `argTypes`
   *  @param emit     emits statements (assignments to Go fields and variables)
   *  @return a Go expression of the member's (erased) Scala result type; empty for none
   */
  def use(owner: ClassDef, member: MethodDef, receiver: Option[String], args: List[String], argTypes: List[ScalaType],
      pos: Position, emit: String => Unit): String =
    val g = member.goMember.get
    val facade = owner.goFacade.get
    reporting(pos, s"Go facade member ${scalaName(owner.name)}.${member.name.simple.stripSuffix("_$eq")}") {
      val isSetter = member.name.simple.endsWith("_$eq") && args.size == 1
      def assign(lhs: String, t: GoType): String =
        emit(s"$lhs = ${toGo(args.head, argTypes.head, t)}")
        ""
      if facade.isPackage then
        val pkg = api.packages.getOrElse(facade.pkg, fail(s"Go package ${facade.pkg} could not be loaded"))
        val q = qualified(facade.pkg, g.goName)
        if isSetter then assign(q, pkg.vars.getOrElse(g.goName, fail(s"Go package ${facade.pkg} has no variable ${g.goName}")))
        else
          pkg.funcs.get(g.goName) match
            case Some(sig) if !(g.isProperty && sig.params.nonEmpty) => call(q, sig, args, argTypes, g.result)
            case _ if args.isEmpty && pkg.vars.contains(g.goName)   => fromGo(q, pkg.vars(g.goName), g.result)
            case _ if args.isEmpty && pkg.consts.contains(g.goName) => fromGo(q, pkg.consts(g.goName), g.result)
            case _ => fail(s"Go package ${facade.pkg} has no exported function, variable or constant ${g.goName}")
      else
        val t = facadeType(owner.name)
        val recv = s"rt.Cast[${t.stored}](${receiver.get})"
        if isSetter then
          assign(s"$recv.${g.goName}", t.decl.fields.getOrElse(g.goName, fail(s"Go type ${t.facade.pkg}.${t.facade.name} has no field ${g.goName}")))
        else
          t.decl.methods.get(g.goName) match
            case Some(sig) if !(g.isProperty && sig.params.nonEmpty) => call(s"$recv.${g.goName}", sig, args, argTypes, g.result)
            case _ if args.isEmpty && t.decl.fields.contains(g.goName) => fromGo(s"$recv.${g.goName}", t.decl.fields(g.goName), g.result)
            case _ => fail(s"Go type ${t.facade.pkg}.${t.facade.name} has no exported method or field ${g.goName}")
    }

  private def call(fn: String, sig: GoSignature, args: List[String], argTypes: List[ScalaType], result: ScalaType): String =
    if sig.generic then fail("generic Go functions are not supported")
    val typedArgs = args.zip(argTypes)
    val goArgs =
      if !sig.variadic then
        if args.size != sig.params.size then fail(s"the Go declaration has ${sig.params.size} parameters: $sig")
        typedArgs.zip(sig.params).map { case ((a, s), t) => toGo(a, s, t) }
      else
        val fixed = sig.params.init
        val elem = sig.params.last.asInstanceOf[GoType.Slice].elem
        if args.size < fixed.size then fail(s"the Go declaration has at least ${fixed.size} parameters: $sig")
        val fixedArgs = typedArgs.take(fixed.size).zip(fixed).map { case ((a, s), t) => toGo(a, s, t) }
        typedArgs.drop(fixed.size) match
          case List((a, s @ (_: Arr | Ref(SeqClass, _)))) => fixedArgs :+ (toGo(a, s, sig.params.last) + "...")
          case rest                                       => fixedArgs ++ rest.map((a, s) => toGo(a, s, elem))
    results(s"$fn(${goArgs.mkString(", ")})", sig.results, result)

  /** Converts the results of Go call `call` to the facade's result type `s`. A trailing `error`
   *  that `s` leaves out is checked: non-nil errors are thrown as `GoException`s.
   */
  private def results(call: String, rs: List[GoType], s: ScalaType): String =
    val hasError = rs.lastOption.contains(GoType.Error)
    val names = rs.indices.map(_ => fresh("r")).toList
    def closure(resultType: String, body: String): String =
      val params = names.zip(rs).map((n, t) => s"$n ${typeString(t)}").mkString(", ")
      s"func($params)${if resultType.isEmpty then "" else " " + resultType} { $body }($call)"
    def tuple(n: Int, targs: List[ScalaType]): String =
      val ctor = MethodName(MethodName.ConstructorSimpleName, List.fill(n)(Type.Object), Type.Void)
      val elems = names.zip(rs).zip(targs).map { case ((r, t), st) => boxed(fromGo(r, t, st), st) }
      s"${Mangling.newInstance(s"scala.Tuple$n", ctor)}(${elems.mkString(", ")})"
    s match
      case Prim(Type.Void) =>
        rs match
          case Nil                 => call
          case List(GoType.Error)  => s"rt.Check($call)"
          case _                   => closure("", if hasError then s"rt.Check(${names.last})" else names.map(n => s"_ = $n").mkString("; "))
      case Ref(Arity("scala.Tuple", n), targs) if n == rs.size && rs.size > 1 =>
        closure(scalaGoType(s), s"return ${tuple(n, targs)}")
      case Ref(Arity("scala.Tuple", n), targs) if hasError && n == rs.size - 1 && n > 1 =>
        closure(scalaGoType(s), s"rt.Check(${names.last}); return ${tuple(n, targs)}")
      case _ if rs.size == 1 => fromGo(call, rs.head, s)
      case _ if rs.size == 2 && hasError =>
        closure(scalaGoType(s), s"rt.Check(${names.last}); return ${fromGo(names.head, rs.head, s)}")
      case _ => fail(s"the Go results ${rs.mkString("(", ", ", ")")} do not match the Scala result type $s")

  // ---- Go types in Scala code -----------------------------------------------------------------

  /** `new C` for a facade class: the Go zero value. */
  def zeroValue(cls: ClassName, pos: Position): String =
    reporting(pos, s"new ${scalaName(cls)}") {
      val t = facadeType(cls)
      t.decl.kind match
        case GoTypeKind.Struct    => s"new(${t.name})"
        case GoTypeKind.Other     => s"any(*new(${t.name}))"
        case GoTypeKind.Interface => fail("Go interface types have no zero value to create")
    }

  def typeTest(e: String, cls: ClassName): String =
    reporting(Position.NoPosition, s"type test against ${scalaName(cls)}")(s"rt.Is[${facadeType(cls).stored}]($e)")

  def cast(e: String, cls: ClassName): String =
    reporting(Position.NoPosition, s"cast to ${scalaName(cls)}")(s"rt.CastForeign[${facadeType(cls).stored}]($e)")

  // ---- Go interfaces implemented in Scala -------------------------------------------------------

  /** Go methods of instantiated class `cls` for the Go interfaces it implements: they convert
   *  their arguments, call the Scala implementation and convert its result.
   */
  def exportedMethods(cls: ClassDef, struct: String): List[String] =
    val done = mutable.HashSet.empty[String]
    for
      f <- program.facadeAncestors(cls.name)
      facade <- program.facade(f).toList
      if facade.kind == ClassKind.Interface
      m <- facade.methods
      g <- m.goMember
      if done.add(g.goName)
    yield reporting(cls.pos, s"Go method ${g.goName} of ${scalaName(cls.name)} (implementing ${scalaName(facade.name)})") {
      val t = facadeType(f)
      val sig = t.decl.methods.getOrElse(g.goName, fail(s"Go interface ${t.name} has no method ${g.goName}"))
      if sig.variadic || sig.generic then fail("variadic or generic Go methods cannot be implemented in Scala yet")
      if sig.params.size != g.params.size then fail(s"the Go method has ${sig.params.size} parameters: $sig")
      if !program.vtable(cls.name).contains(m.name) then fail(s"${cls.name} does not implement ${m.name.simple}")
      val names = sig.params.indices.map(_ => fresh("p")).toList
      val scalaArgs = names.zip(sig.params).zip(g.params).map { case ((p, gt), st) => fromGo(p, gt, st) }
      val callScala = s"this.${Mangling.virtualMethod(m.name)}(${scalaArgs.mkString(", ")})"
      val rs = sig.results
      val hasError = rs.lastOption.contains(GoType.Error)
      val scalaReturnsError = g.result == Ref(GoErrorClass, Nil)
      val params = names.zip(sig.params).map((n, gt) => s"$n ${typeString(gt)}").mkString(", ")
      if hasError && !scalaReturnsError then
        // Scala exceptions become the error result.
        val values = rs.init
        val resultNames = values.indices.map(_ => fresh("r")).toList
        val resultDecl = (resultNames.zip(values).map((n, gt) => s"$n ${typeString(gt)}") :+ "err error").mkString(", ")
        val body = values match
          case Nil => s"$callScala; return"
          case List(v) => s"${resultNames.head} = ${toGo(callScala, g.result, v)}; return"
          case _ => fail("Go methods with several results cannot be implemented in Scala yet")
        s"func (this *$struct) ${g.goName}($params) ($resultDecl) { defer rt.GoCallback(); defer rt.RecoverAsError(&err); $body }"
      else
        val (resultDecl, body) = rs match
          case Nil       => ("", callScala)
          case List(res) => (s" ${typeString(res)}", s"return ${toGo(callScala, g.result, res)}")
          case _         => fail("Go methods with several results cannot be implemented in Scala yet")
        s"func (this *$struct) ${g.goName}($params)$resultDecl { defer rt.GoCallback(); $body }"
    }
end GoInterop
