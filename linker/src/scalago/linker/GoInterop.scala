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
  /** The "package" of `GoType.Named` types that are exported Scala classes (their X_ interfaces). */
  val ScalaExportPkg = "<scala>"
  val GoSliceType = "scalago.go.GoSlice"
  val GoMapType = "scalago.go.GoMap"
  val GoChanType = "scalago.go.GoChan"
  val GoPtrType = "scalago.go.GoPtr"
  /** Opaque types whose values are Go values (in an `any`). */
  val GoValueTypes: Set[String] = Set(GoStringType, GoSliceType, GoMapType, GoChanType, GoPtrType)
  /** Go numeric types (opaque over Scala numbers), with the Go type and the Scala type they stand for. */
  val GoNumberTypes: Map[String, (String, Type)] = Map(
    "scalago.go.GoInt" -> ("int", Type.Long), "scalago.go.GoUint" -> ("uint", Type.Long),
    "scalago.go.GoUint64" -> ("uint64", Type.Long), "scalago.go.GoUintptr" -> ("uintptr", Type.Long),
    "scalago.go.GoUint32" -> ("uint32", Type.Int), "scalago.go.GoByte" -> ("uint8", Type.Byte)
  )

  /** `s` with the Go numeric types replaced by the Scala types they are opaque over. */
  def normalize(s: ScalaType): ScalaType = s match
    case ScalaType.Ref(n, _) if GoNumberTypes.contains(n) => ScalaType.Prim(GoNumberTypes(n)._2)
    case ScalaType.Ref(n, args)                           => ScalaType.Ref(n, args.map(normalize))
    case ScalaType.Arr(e)                                 => ScalaType.Arr(normalize(e))
    case prim                                             => prim

  /** The accessor of a tuple's `i`-th element (`_1`). */
  def TupleElement(i: Int): MethodName = MethodName(s"_$i", Nil, Type.Object)

  /** `scalago.go.GoFunctionN`: Go funcs returned to Scala. */
  def goFunctionClass(arity: Int): ClassName = s"scalago.go.GoFunction$arity"
  val GoFunctionConstructor: MethodName = MethodName(MethodName.ConstructorSimpleName, List(Type.Object), Type.Void)
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
    case ScalaType.Ref(n, _) if GoNumberTypes.contains(n) => GoNumberTypes(n)._2
    case ScalaType.Ref(t, _) if GoValueTypes(t) => Type.Object
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

  /** The canonical Scala type of Go basic types inside Go data (see runtime/rt/gotypes.go). */
  private val canonicalPrimitive: Map[String, Type] = Map(
    "int" -> Type.Long, "int64" -> Type.Long, "uint" -> Type.Long, "uint64" -> Type.Long, "uintptr" -> Type.Long,
    "int32" -> Type.Int, "uint32" -> Type.Int, "int16" -> Type.Short, "uint16" -> Type.Char,
    "int8" -> Type.Byte, "uint8" -> Type.Byte, "float32" -> Type.Float, "float64" -> Type.Double, "bool" -> Type.Boolean
  )
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
  def reporting(pos: Position, what: => String)(body: => String): String =
    try body
    catch
      case e: Unconvertible =>
        ctx.errors.add(pos, s"$what: ${e.getMessage}")
        "nil"

  /** The Go spelling of `t`, with package aliases. */
  def typeString(t: GoType): String = t match
    case GoType.Basic(b) if !b.startsWith("untyped") && (numericBasics(b) || b == "string" || b == "bool") => b
    case GoType.Named(ScalaExportPkg, cls) => ctx.exports.ifaceName(cls)
    case GoType.Named(p, n)    => qualified(p, n)
    case GoType.Ptr(e)         => s"*${typeString(e)}"
    case GoType.Slice(e)       => s"[]${typeString(e)}"
    case GoType.ArrayOf(n, e)  => s"[$n]${typeString(e)}"
    case GoType.MapOf(k, v)    => s"map[${typeString(k)}]${typeString(v)}"
    case GoType.Chan(e, "recv") => s"<-chan ${typeString(e)}"
    case GoType.Chan(e, "send") => s"chan<- ${typeString(e)}"
    case GoType.Chan(e, _)     => s"chan ${typeString(e)}"
    case GoType.Func(sig)      => s"func${signatureString(sig, None)}"
    case GoType.EmptyInterface => "any"
    case other                 => fail(s"Go type $other is not supported")

  /** `typeString` for the public root package of a library, where exported classes are spelled
   *  with their aliases.
   */
  def rootTypeString(t: GoType, alias: ClassName => String): String = t match
    case GoType.Named(ScalaExportPkg, cls) => alias(cls)
    case GoType.Ptr(e)                     => s"*${rootTypeString(e, alias)}"
    case GoType.Slice(e)                   => s"[]${rootTypeString(e, alias)}"
    case GoType.MapOf(k, v)                => s"map[${rootTypeString(k, alias)}]${rootTypeString(v, alias)}"
    case GoType.Chan(e, "recv")            => s"<-chan ${rootTypeString(e, alias)}"
    case GoType.Chan(e, "send")            => s"chan<- ${rootTypeString(e, alias)}"
    case GoType.Chan(e, _)                 => s"chan ${rootTypeString(e, alias)}"
    case GoType.Func(sig) =>
      val ps = sig.params.map(rootTypeString(_, alias))
      val rs = sig.results.map(rootTypeString(_, alias)) match
        case Nil       => ""
        case List(one) => s" $one"
        case several   => several.mkString(" (", ", ", ")")
      s"func(${ps.mkString(", ")})$rs"
    case other => typeString(other)

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

  /** A named interface without methods (`type Msg interface{}`): converted as `any`. */
  private object EmptyInterfaceNamed:
    def unapply(g: GoType): Boolean = g match
      case GoType.Named(pkg, n) if pkg != ScalaExportPkg => api.typeDecl(pkg, n).exists(_.isEmptyInterface)
      case _                                           => false

  /** The signature of a named func type (`type Cmd func() Msg`), which converts as its func type. */
  private object NamedFunc:
    def unapply(g: GoType): Option[GoSignature] = g match
      case GoType.Named(pkg, n) if pkg != ScalaExportPkg => api.typeDecl(pkg, n).flatMap(_.funcSignature)
      case _                                           => None

  private def scalaGoType(s: ScalaType): String =
    val t = ctx.goType(erased(s))
    if t.isEmpty then "any" else t

  /** Whether values of Scala type `cls` can be Go values (and Go structs, which go to Go
   *  interfaces by value or by pointer, see `rt.ToGoInterface`): facades and `Any`.
   */
  private def holdsGoValues(cls: ClassName): Boolean = cls == ClassName.Object || program.isFacade(cls)

  // ---- Scala to Go ------------------------------------------------------------------------------

  private var counter = 0
  private def fresh(prefix: String): String =
    counter += 1
    s"$prefix$counter"

  /** Converts `e`, a Go expression for a Scala value of type `s`, to Go type `g`. */
  def toGo(e: String, s: ScalaType, g: GoType): String = toGoNormalized(e, normalize(s), g)

  private def toGoNormalized(e: String, s: ScalaType, g: GoType): String = (s, g) match
    case (_: Ref, GoType.Named(ScalaExportPkg, cls)) => s"rt.Cast[${ctx.exports.ifaceName(cls)}](any($e))"
    case (_, GoType.Basic(b)) => basicToGo(e, s, b)
    case (_, EmptyInterfaceNamed()) => toGoNormalized(e, s, GoType.EmptyInterface)
    case (Ref(Arity("scala.Function", _), args), NamedFunc(sig)) => funcToGo(e, args, sig)
    case (Prim(p), GoType.Named(pkg, n)) if p.isNumeric && namedDecl(pkg, n).kind == GoTypeKind.Other =>
      s"${qualified(pkg, n)}($e)"
    case (Ref(cls, _), GoType.Named(pkg, n)) =>
      val decl = namedDecl(pkg, n)
      val goT = qualified(pkg, n)
      decl.kind match
        case GoTypeKind.Interface if holdsGoValues(cls) => s"rt.ToGoInterface[$goT]($e)"
        case GoTypeKind.Interface => s"rt.Cast[$goT]($e)"
        case GoTypeKind.Struct if standsFor(cls, pkg, n) => s"rt.Deref[$goT]($e)"
        case GoTypeKind.Other if standsFor(cls, pkg, n)  => s"rt.Cast[$goT]($e)"
        case GoTypeKind.Other if cls == ClassName.String  => s"$goT($e.GoString())"
        case _ => fail(s"cannot pass Scala type $s as Go type $g")
    case (Ref(cls, _), GoType.Ptr(GoType.Named(pkg, n))) if standsFor(cls, pkg, n) =>
      s"rt.Cast[*${qualified(pkg, n)}]($e)"
    case (Ref(t, _), _) if GoValueTypes(t) && t != GoStringType =>
      checkCanonical(s, g)
      s"rt.ConvertGo[${typeString(g)}]($e)"
    case (Arr(se), GoType.Slice(ge))                 => sliceToGo(e, se, ge)
    case (Ref(SeqClass, List(se)), GoType.Slice(ge)) => seqToGo(e, se, ge)
    case (Ref(Arity("scala.Function", _), args), GoType.Func(sig)) => funcToGo(e, args, sig)
    case (Prim(p), GoType.EmptyInterface) if p.isPrimitive => s"any($e)"
    case (Ref(cls, _), GoType.EmptyInterface) if cls == ClassName.String || holdsGoValues(cls) => s"rt.ToGoAny($e)"
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
  def fromGo(e: String, g: GoType, s: ScalaType): String = fromGoNormalized(e, g, normalize(s))

  private def fromGoNormalized(e: String, g: GoType, s: ScalaType): String = (g, s) match
    case (GoType.Named(ScalaExportPkg, _), _: Ref) => ctx.cast(s"any($e)", Type.Object, erased(s))
    case (GoType.Basic(b), _) => basicFromGo(e, b, s)
    case (EmptyInterfaceNamed(), _) => fromGoNormalized(e, GoType.EmptyInterface, s)
    case (NamedFunc(sig), Ref(Arity("scala.Function", n), args)) => funcFromGo(e, sig, n, args)
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
    case (_, Ref(t, _)) if GoValueTypes(t) && t != GoStringType =>
      checkCanonical(s, g)
      s"any($e)"
    case (GoType.Func(sig), Ref(Arity("scala.Function", n), args)) => funcFromGo(e, sig, n, args)
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

  /** A Go func as a Scala function (a `scalago.go.GoFunctionN`): a Go closure over it converts
   *  the arguments and the results with the facade's types.
   */
  private def funcFromGo(e: String, sig: GoSignature, arity: Int, args: List[ScalaType]): String =
    if sig.variadic || sig.generic then fail("variadic or generic Go func types are not supported")
    if arity > 3 then fail("Go funcs with more than 3 parameters cannot be returned to Scala yet")
    if sig.params.size != arity then fail(s"the Go func takes ${sig.params.size} parameters, the Scala function $arity")
    val f = fresh("f")
    val names = sig.params.indices.map(_ => fresh("a")).toList
    val goArgs = names.zip(args.init).zip(sig.params).map { case ((a, st), gt) => toGo(ctx.cast(a, Type.Object, erased(st)), st, gt) }
    val result = args.last match
      case Ref("scala.runtime.BoxedUnit", _) => Prim(Type.Void)
      case other                             => other
    val converted = results(s"$f(${goArgs.mkString(", ")})", sig.results, result)
    val body =
      if ctx.goType(erased(result)).isEmpty then s"$converted; return rt.UnitValue"
      else s"return ${boxed(converted, result)}"
    val closure = s"func(${names.map(_ + " any").mkString(", ")}) any { rt.FlushStdout(); $body }"
    val wrapper = s"${Mangling.newInstance(goFunctionClass(arity), GoFunctionConstructor)}($closure)"
    ctx.cast(s"func($f ${typeString(GoType.Func(sig))}) any { if $f == nil { return nil }; return $wrapper }($e)",
      Type.Object, erased(Ref(s"scala.Function$arity", Nil)))

  /** Checks that Scala type `s` is the canonical view of Go type `g` (in Go data, values have one
   *  Scala representation: Go `int` is `Long`, `string` is `GoString`, ...).
   */
  private def checkCanonical(s: ScalaType, g: GoType): Unit =
    if !isCanonical(normalize(s), g) then fail(s"Go type $g is ${canonicalSpelling(g)} in Scala, not $s")

  private def isCanonical(s: ScalaType, g: GoType): Boolean = (s, g) match
    case (Ref(ClassName.Object, _), _)                          => true
    case (Prim(p), GoType.Basic(b))                             => canonicalPrimitive.get(normalizeBasic(b)).contains(p)
    case (Ref(GoStringType, _), GoType.Basic(b))                => normalizeBasic(b) == "string"
    case (Ref(GoSliceType, List(e)), GoType.Slice(ge))          => isCanonical(e, ge)
    case (Ref(GoMapType, List(k, v)), GoType.MapOf(gk, gv))     => isCanonical(k, gk) && isCanonical(v, gv)
    case (Ref(GoChanType, List(e)), GoType.Chan(ge, _))         => isCanonical(e, ge)
    case (Ref(GoPtrType, List(e)), GoType.Ptr(ge))              => isCanonical(e, ge)
    case (Ref(cls, _), GoType.Named(ScalaExportPkg, c))         => cls == c
    case (Ref(cls, _), GoType.Named(pkg, n))                    =>
      standsFor(cls, pkg, n) || (namedDecl(pkg, n).kind == GoTypeKind.Interface && program.isFacade(cls))
    case (Ref(cls, _), GoType.Ptr(GoType.Named(pkg, n)))        => standsFor(cls, pkg, n)
    case _                                                      => false

  private def canonicalSpelling(g: GoType): String = g match
    case GoType.Basic(b) =>
      canonicalPrimitive.get(normalizeBasic(b)).map(_.toString).getOrElse(if normalizeBasic(b) == "string" then "GoString" else b)
    case GoType.Slice(e)          => s"GoSlice[${canonicalSpelling(e)}]"
    case GoType.MapOf(k, v)       => s"GoMap[${canonicalSpelling(k)}, ${canonicalSpelling(v)}]"
    case GoType.Chan(e, _)        => s"GoChan[${canonicalSpelling(e)}]"
    case GoType.Ptr(n: GoType.Named) => s"a facade of $n"
    case GoType.Ptr(e)            => s"GoPtr[${canonicalSpelling(e)}]"
    case GoType.Named(p, n)       => s"a facade of ${if p.isEmpty then n else s"$p.$n"}"
    case GoType.EmptyInterface    => "Any"
    case other                    => s"not representable ($other)"

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
            case _ if args.size == 1 && pkg.types.get(g.goName).exists(d => d.kind == GoTypeKind.Other && d.underlying.isDefined) =>
              // A conversion to a named type (`lipgloss.Color("205")`, `time.Duration(n)`).
              val underlying = pkg.types(g.goName).underlying.get
              fromGo(s"$q(${toGo(args.head, argTypes.head, underlying)})", GoType.Named(facade.pkg, g.goName), g.result)
            case _ => fail(s"Go package ${facade.pkg} has no exported function, variable, constant or type ${g.goName}")
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

  private def call(fn: String, genericSig: GoSignature, args: List[String], argTypes: List[ScalaType], result: ScalaType): String =
    val sig = if genericSig.generic then instantiate(genericSig, argTypes, result) else genericSig
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

  /** A generic Go function's signature as instantiated by a (monomorphic) facade: the Go types
   *  that mention type parameters are those of the facade's Scala types. The call passes values
   *  of those types, so Go infers the type arguments.
   */
  private def instantiate(sig: GoSignature, argTypes: List[ScalaType], result: ScalaType): GoSignature =
    def mentionsTypeParam(g: GoType): Boolean = g match
      case GoType.TypeParam(_)                => true
      case GoType.Ptr(e)                      => mentionsTypeParam(e)
      case GoType.Slice(e)                    => mentionsTypeParam(e)
      case GoType.ArrayOf(_, e)               => mentionsTypeParam(e)
      case GoType.MapOf(k, v)                 => mentionsTypeParam(k) || mentionsTypeParam(v)
      case GoType.Chan(e, _)                  => mentionsTypeParam(e)
      case GoType.Func(s)                     => (s.params ++ s.results).exists(mentionsTypeParam)
      case _                                  => false
    def fixed(g: GoType, s: => ScalaType): GoType = if mentionsTypeParam(g) then goTypeOf(s) else g
    if sig.params.size != argTypes.size && !sig.variadic then
      fail(s"the Go declaration has ${sig.params.size} parameters: $sig")
    val params = sig.params.zipWithIndex.map { (g, i) =>
      if sig.variadic && i == sig.params.size - 1 then
        argTypes.drop(i) match
          case List(Ref(SeqClass, List(e))) => fixed(g, Arr(e))
          case List(a: Arr)                 => fixed(g, a)
          case rest                         => fixed(g, Arr(rest.headOption.getOrElse(Ref(ClassName.Object, Nil))))
      else fixed(g, argTypes(i))
    }
    val values = if sig.results.lastOption.contains(GoType.Error) then sig.results.init else sig.results
    val resultTypes = result match
      case Ref(Arity("scala.Tuple", n), targs) if n == values.size && n > 1 => targs
      case Ref(Arity("scala.Tuple", n), targs) if n == sig.results.size => targs
      case other                                                          => List(other)
    val results = sig.results.zipWithIndex.map((g, i) => fixed(g, resultTypes.lift(i).getOrElse(fail(s"cannot instantiate $g"))))
    GoSignature(params, results, sig.variadic, generic = false)

  /** The Go type for a Scala type: for instantiating generic Go functions, and in exports. */
  def goTypeOf(s: ScalaType): GoType = s match
    case Prim(Type.Boolean)                         => GoType.Basic("bool")
    case Prim(p) if p.isNumeric                     => GoType.Basic(Hijacked.primitiveGoType(p))
    case Ref(n, _) if GoNumberTypes.contains(n)     => GoType.Basic(GoNumberTypes(n)._1)
    case Ref(ClassName.String | GoStringType, _)    => GoType.Basic("string")
    case Ref(ClassName.Object, _)                   => GoType.EmptyInterface
    case Ref(GoSliceType, List(e))                  => GoType.Slice(goTypeOf(e))
    case Ref(GoMapType, List(k, v))                 => GoType.MapOf(goTypeOf(k), goTypeOf(v))
    case Ref(GoChanType, List(e))                   => GoType.Chan(goTypeOf(e))
    case Ref(GoPtrType, List(e))                    => GoType.Ptr(goTypeOf(e))
    case Arr(e)                                     => GoType.Slice(goTypeOf(e))
    case Ref(Arity("scala.Function", _), targs)     =>
      val res = targs.last match
        case Prim(Type.Void) | Ref("scala.runtime.BoxedUnit", _) => Nil
        case r                                                   => List(goTypeOf(r))
      GoType.Func(GoSignature(targs.init.map(goTypeOf), res, variadic = false, generic = false))
    case Ref(cls, _) if ctx.exports.isExportedClass(cls) => GoType.Named(ScalaExportPkg, cls)
    case Ref(GoErrorClass, _)                       => GoType.Error
    case Ref(cls, _) if program.isFacade(cls)       =>
      val t = facadeType(cls)
      val named = GoType.Named(t.facade.pkg, t.facade.name)
      if t.isStruct then GoType.Ptr(named) else named
    case other                                      => fail(s"cannot instantiate a generic Go function with Scala type $other")

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

  /** Assigns the elements of `e`, a Scala tuple of type `s`, to the Go results `targets`. */
  private def tupleToGo(e: String, s: ScalaType, targets: List[(String, GoType)]): String = s match
    case Ref(Arity("scala.Tuple", n), targs) if n == targets.size =>
      val t = fresh("t")
      val elems = targets.zip(targs).zipWithIndex.map { case (((name, gt), st), i) =>
        val elem = ctx.cast(s"${ctx.dispatcher(TupleElement(i + 1))}($t)", Type.Object, erased(st))
        s"$name = ${toGo(elem, st, gt)}"
      }
      s"""$t := $e; if $t == nil { rt.ThrowKind(rt.NullPointer, "") }; ${elems.mkString("; ")}"""
    case _ => fail(s"the Go results ${targets.map(_._2).mkString("(", ", ", ")")} need a Scala tuple of ${targets.size} elements, not $s")

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

  /** A type test against a facade. Facade references hold Go structs as pointers, but Go interfaces
   *  (and so `Any`) can hold struct values (`tea.KeyMsg` in a `tea.Msg`): both pass.
   */
  def typeTest(e: String, cls: ClassName): String =
    reporting(Position.NoPosition, s"type test against ${scalaName(cls)}") {
      val t = facadeType(cls)
      if t.isStruct then s"rt.IsStruct[${t.name}]($e)" else s"rt.Is[${t.stored}]($e)"
    }

  /** A cast to a facade; a struct value is copied to a new pointer (see `typeTest`). */
  def cast(e: String, cls: ClassName): String =
    reporting(Position.NoPosition, s"cast to ${scalaName(cls)}") {
      val t = facadeType(cls)
      if t.isStruct then s"rt.CastStruct[${t.name}]($e)" else s"rt.CastForeign[${t.stored}]($e)"
    }

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
      // Scala exceptions become the error result, unless the Scala method returns the error.
      val errorResult = hasError && !scalaReturnsError
      val values = if errorResult then rs.init else rs
      val resultNames = values.indices.map(_ => fresh("r")).toList
      val resultDecl = resultNames.zip(values).map((n, gt) => s"$n ${typeString(gt)}") ++ (if errorResult then List("err error") else Nil)
      val assignments = values match
        case Nil       => callScala
        case List(v)   => s"${resultNames.head} = ${toGo(callScala, g.result, v)}"
        case several   => tupleToGo(callScala, g.result, resultNames.zip(several))
      val recover = if errorResult then "defer rt.RecoverAsError(&err); " else ""
      val resultList = if resultDecl.isEmpty then "" else resultDecl.mkString(" (", ", ", ")")
      s"func (this *$struct) ${g.goName}($params)$resultList { defer rt.GoCallback(); $recover$assignments; return }"
    }
end GoInterop
