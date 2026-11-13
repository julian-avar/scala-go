package scalago.plugin

import scala.collection.mutable

import dotty.tools.backend.ScalaPrimitives
import dotty.tools.backend.ScalaPrimitivesOps.*
import dotty.tools.backend.jvm.BCodeUtils.isAnonymousOrLocalClass
import dotty.tools.backend.jvm.SymbolUtils.given
import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Constants.*
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.NameKinds.ExpandedName
import dotty.tools.dotc.core.NameOps.*
import dotty.tools.dotc.core.Phases.erasurePhase
import dotty.tools.dotc.core.StdNames.nme
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.report
import dotty.tools.dotc.transform.Erasure

import scalago.ir
import scalago.ir.Tree as G

/** Translates the (erased, lowered) trees of one compilation unit into scala-go IR, one `.goir`
 *  file per class, written next to the `.class` files.
 *
 *  Anything not yet supported is reported as a compile error rather than silently miscompiled.
 *  In lenient mode (used to compile libraries from TASTy) it becomes an `Unsupported` node
 *  instead, and the linker reports it only if that code is reachable.
 */
final class GoIRCodeGen(options: GoIRCodeGen.Options)(using genCtx: Context):
  private val primitives = ScalaPrimitives(genCtx)
  private val lenient = options.lenient
  private val enumerationNames = EnumerationNames()

  // Per-class / per-method state
  private var currentClass: ClassSymbol = uninitialized
  private var inEnumeration = false // currentClass extends scala.Enumeration
  private var locals: LocalNames = uninitialized

  private def uninitialized[A]: A = null.asInstanceOf[A]

  def genCompilationUnit(unit: CompilationUnit): Unit =
    def collectClasses(tree: Tree): List[TypeDef] = tree match
      case PackageDef(_, stats)           => stats.flatMap(collectClasses)
      case td: TypeDef if td.isClassDef => List(td)
      case _                              => Nil

    val generated = mutable.LinkedHashMap.empty[ClassSymbol, ir.ClassDef]
    for td <- collectClasses(unit.tpdTree) do
      val errorsBefore = ctx.reporter.errorCount
      val classDef = genClass(td)
      if ctx.reporter.errorCount == errorsBefore then generated(td.symbol.asClass) = classDef
    val mirrors = if options.staticForwarders then addStaticForwarders(generated) else Nil
    for (sym, classDef) <- generated do writeIR(sym.javaClassName, classDef)
    for (fileName, classDef) <- mirrors do writeIR(fileName, classDef)

  /** Writes `classDef` next to the class file named `binaryName` (before renames: the IR of
   *  `java.lang.String`, compiled as `scalago.javalib.lang.String`, sits next to the latter's class
   *  file). The linker ignores IR files without their class file (see `IRLoader`): incremental
   *  compilation deletes the class files of removed classes, but not their IR.
   */
  private def writeIR(binaryName: String, classDef: ir.ClassDef): Unit =
    val parts = binaryName.split('.')
    val dir = parts.init.foldLeft(ctx.settings.outputDir.value)(_.subdirectoryNamed(_))
    val out = dir.fileNamed(parts.last + ir.Serialization.FileExtension).bufferedOutput
    try out.write(ir.Serialization.serialize(classDef))
    finally out.close()

  // -------------------------------------------------------------------------------------------
  // Classes and members
  // -------------------------------------------------------------------------------------------

  private def genClass(td: TypeDef): ir.ClassDef =
    val sym = td.symbol.asClass
    currentClass = sym
    inEnumeration = enumerationNames.isEnumeration(sym)
    val impl = td.rhs.asInstanceOf[Template]
    goFacadeOf(sym) match
      case Some(facade) => genFacadeClass(td, impl, facade)
      case None         => genOrdinaryClass(td, impl)

  private def genOrdinaryClass(td: TypeDef, impl: Template): ir.ClassDef =
    val sym = td.symbol.asClass
    val kind = classKind(sym)
    val superClass =
      if sym.is(Trait) then None
      else Option(sym.superClass).filter(_.exists).map(className)
    val interfaces = sym.info.parents.map(_.classSymbol).filter(_.is(Trait)).map(className)

    val members = (impl.constr :: impl.body).filterNot(t => isIgnoredMember(t.symbol))
    val fields = members.collect { case vd: ValDef if !vd.symbol.is(Method) =>
      if isExported(vd.symbol) then
        report.error("scala-go: vals and fields cannot be exported to Go; export a method that returns them (`@GoExport def`)", vd.srcPos)
      genField(vd)
    }
    val exportedType = goExportedType(td)
    val methods = members.collect { case dd: DefDef => genMethod(dd).copy(goExport = goExport(dd, exportedType)) }

    ir.ClassDef(className(sym), kind, sym.is(Abstract), superClass, interfaces, fields, methods, position(td),
      goExport = exportedType, innerClass = innerClass(sym))

  /** The class's entry in the JVM's `InnerClasses` attribute, made as the JVM backend makes it
   *  (`BTypeLoader.buildNestedInfo`); `None` for a top-level class.
   */
  private def innerClass(sym: ClassSymbol): Option[ir.InnerClass] =
    val enclosing = sym.originalOwner.originalLexicallyEnclosingClass
    if enclosing.is(PackageClass) then None
    else
      val outer =
        if isAnonymousOrLocalClass(sym) then None
        // A member of a top-level object is one of its mirror class, as in Java.
        else if enclosing.isTopLevelModuleClass then Some(className(enclosing).stripSuffix("$"))
        else Some(className(enclosing))
      val name =
        if sym.isAnonymousClass || sym.isAnonymousFunction then ""
        else atPhase(sym.initial.validFor.lastPhaseId)(sym.targetName).mangledString
      Some(ir.InnerClass(outer, name))

  // -------------------------------------------------------------------------------------------
  // Static forwarders
  // -------------------------------------------------------------------------------------------

  /** Adds static forwarders to the methods of static objects, as the JVM backend does for
   *  top-level ones: to the companion class, or to a "mirror class" with the object's name if it
   *  has none (returned). Java callers, here the JDK signatures that programs are compiled
   *  against, call them as static methods, so javalib implements JDK classes' static methods
   *  (`Arrays`, `Collections`, static methods of interfaces such as `Map.Entry`) in Scala objects.
   */
  /** Static forwarders, added to companion classes; mirror classes for objects without one, with
   *  the binary names of their class files.
   */
  private def addStaticForwarders(generated: mutable.LinkedHashMap[ClassSymbol, ir.ClassDef]): List[(String, ir.ClassDef)] =
    for
      (moduleClass, moduleDef) <- generated.toList
      // Classes are flattened by now: look at the owners before `flatten`.
      if moduleClass.is(ModuleClass) && atPhase(erasurePhase)(moduleClass.isStatic)
      companion = atPhase(erasurePhase)(moduleClass.companionClass)
      forwarders = staticForwarders(moduleClass, companion)
      if forwarders.nonEmpty
      mirror <-
        if companion.exists then
          val cls = companion.asClass
          generated.get(cls).foreach(c => generated(cls) = c.copy(methods = c.methods ++ forwarders))
          None
        else
          Some(moduleClass.javaClassName.stripSuffix("$") -> ir.ClassDef(moduleDef.name.stripSuffix("$"),
            ir.ClassKind.Class, isAbstract = false, Some(ir.ClassName.Object), Nil, Nil, forwarders, moduleDef.pos))
    yield mirror

  /** The JVM backend's rules (`BCodeHelpers.addForwarders`): public concrete methods, except
   *  `Object`'s and those that a method of the companion class has. The JVM backend also leaves
   *  out methods whose *name* a member of the companion class has, but JDK classes overload
   *  static and instance methods (`Year.isLeap(long)` and `isLeap()`), so here only methods with
   *  the same signature conflict.
   */
  private def staticForwarders(moduleClass: ClassSymbol, companion: Symbol): List[ir.MethodDef] =
    val conflicting: Set[ir.MethodName] =
      if !companion.exists then Set.empty
      else companion.info.allMembers.iterator.map(_.symbol).filter(_.is(Method)).map(methodName).toSet
    val module = className(moduleClass)
    val pos = moduleClass.defTree match
      case EmptyTree => ir.Position.NoPosition
      case tree      => position(tree)
    val excluded = Lifted | Protected | JavaStatic | Private | Macro
    val members = moduleClass.info.membersBasedOnFlags(required = Method, excluded = excluded).map(_.symbol)
    members.toList.sortBy(_.name.toString).flatMap { m0 =>
      val m = if m0.is(Bridge) then m0.nextOverriddenSymbol else m0
      val skip = !m.exists || m.isType || m.is(Deferred) || m.owner == defn.ObjectClass || m.owner == defn.AnyClass ||
        m.isConstructor || m.name.is(ExpandedName) || conflicting(methodName(m)) ||
        (m.accessBoundary(defn.RootClass) ne defn.RootClass)
      if skip then None
      else
        val name = methodName(m)
        val params = name.params.zipWithIndex.map((t, i) => ir.ParamDef(s"x$$$i", t))
        val args = params.map(p => G.VarRef(p.name, p.tpe)(pos))
        val call = G.Apply(G.LoadModule(module)(pos), className(m.owner), name, args, name.result)(pos)
        Some(ir.MethodDef(name, params, Some(call), isStatic = true, isPrivate = false, pos))
    }.distinctBy(_.name)

  private def classKind(sym: ClassSymbol): ir.ClassKind =
    if sym.is(Trait) then ir.ClassKind.Interface
    else if sym.is(ModuleClass) && sym.isStatic then ir.ClassKind.ModuleClass
    else ir.ClassKind.Class

  // -------------------------------------------------------------------------------------------
  // Exports to Go
  // -------------------------------------------------------------------------------------------

  private lazy val GoExportAnnot: Symbol = getClassIfDefined("scalago.go.GoExport")

  private def isExported(sym: Symbol): Boolean = GoExportAnnot.exists && sym.hasAnnotation(GoExportAnnot)

  /** The Go name of an exported definition: the annotation's, or the Scala name capitalized. */
  private def exportName(sym: Symbol, scalaName: String, pos: dotty.tools.dotc.util.SrcPos): String =
    val name = annotationArg(sym, GoExportAnnot, 0).filter(_.nonEmpty).getOrElse(scalaName.capitalize)
    if !name.matches("[A-Z][A-Za-z0-9_]*") then
      report.error(s"scala-go: `$name` is not an exported Go identifier (use @GoExport(\"Name\"))", pos)
    name

  /** For an exported class (`@GoExport class C`): its Go type name. */
  private def goExportedType(td: TypeDef): Option[String] =
    val sym = td.symbol
    if !isExported(sym) then None
    else if sym.is(Trait) then
      report.error("scala-go: traits cannot be exported to Go yet; export the classes implementing them", td.srcPos)
      None
    else if sym.is(ModuleClass) then
      report.error("scala-go: export the methods of an object (`@GoExport def`), not the object", td.srcPos)
      None
    else Some(exportName(sym, atPhase(erasurePhase)(sym.name).toString, td.srcPos))

  /** The export of a method (`@GoExport def`) or of an exported class's primary constructor. */
  private def goExport(dd: DefDef, exportedType: Option[String]): Option[ir.GoExport] =
    val sym = dd.symbol
    val owner = sym.owner
    val isPrimaryConstructor = sym.isPrimaryConstructor && exportedType.isDefined && !owner.is(Abstract)
    if !isExported(sym) && !isPrimaryConstructor then None
    else
      val inTopLevelObject = owner.is(ModuleClass) && owner.isStatic
      if !isPrimaryConstructor && !inTopLevelObject && exportedType.isEmpty then
        report.error("scala-go: only methods of top-level objects and of exported classes (@GoExport class) can be exported", dd.srcPos)
      if sym.is(Private) then report.error("scala-go: private methods cannot be exported", dd.srcPos)
      val (params, result) = atPhase(erasurePhase)(sym.info) match
        case ExprType(res)  => (Nil, res)
        case mt: MethodType =>
          if mt.resultType.isInstanceOf[MethodType] then
            report.error("scala-go: exported methods can have at most one parameter list", dd.srcPos)
          (mt.paramInfos, mt.resultType)
        case pt: PolyType =>
          report.error("scala-go: exported methods cannot have type parameters", dd.srcPos)
          (Nil, pt.resultType)
        case other => (Nil, other)
      val goName =
        if isPrimaryConstructor then s"New${exportedType.get}"
        else exportName(sym, sym.name.toString, dd.srcPos)
      val resultType = if isPrimaryConstructor then ir.ScalaType.Prim(ir.Type.Void) else scalaType(result)
      Some(ir.GoExport(goName, params.map(scalaType), resultType, returnsError = sym.hasAnnotation(defn.ThrowsAnnot)))

  // -------------------------------------------------------------------------------------------
  // Go facades
  // -------------------------------------------------------------------------------------------

  private lazy val GoImportAnnot: Symbol = getClassIfDefined("scalago.go.GoImport")
  private lazy val GoNameAnnot: Symbol = getClassIfDefined("scalago.go.GoName")

  private def annotationArg(sym: Symbol, annot: Symbol, index: Int): Option[String] =
    if !annot.exists then None
    else sym.getAnnotation(annot).flatMap(_.argumentConstantString(index))

  private def goNameOf(sym: Symbol): Option[String] = annotationArg(sym, GoNameAnnot, 0)

  /** `@GoImport` on the class (or its module), or membership in a `@GoImport` object. */
  private def goFacadeOf(sym: ClassSymbol): Option[ir.GoFacade] =
    if !GoImportAnnot.exists then None
    else
      val annotated = if sym.is(ModuleClass) then List(sym, sym.sourceModule) else List(sym)
      annotated.find(_.hasAnnotation(GoImportAnnot)) match
        case Some(holder) =>
          val path = annotationArg(holder, GoImportAnnot, 0).getOrElse("")
          if sym.is(ModuleClass) then Some(ir.GoFacade(path, ""))
          else
            val name = annotationArg(holder, GoImportAnnot, 1).filter(_.nonEmpty)
              .orElse(goNameOf(sym)).getOrElse(sym.name.stripModuleClassSuffix.toString)
            Some(ir.GoFacade(path, name))
        case None =>
          // Classes are flattened by now: look at the owner before `flatten`.
          val owner = atPhase(erasurePhase)(sym.owner)
          if owner.is(ModuleClass) && !sym.is(ModuleClass) then
            goFacadeOf(owner.asClass).filter(_.isPackage).map { pkg =>
              ir.GoFacade(pkg.pkg, goNameOf(sym).getOrElse(atPhase(erasurePhase)(sym.name).toString))
            }
          else None

  private def genFacadeClass(td: TypeDef, impl: Template, facade: ir.GoFacade): ir.ClassDef =
    val sym = td.symbol.asClass
    val parent = sym.superClass
    val superClass = if sym.is(Trait) || !parent.exists then None else Some(className(parent))
    if parent.exists && parent != defn.ObjectClass && goFacadeOf(parent.asClass).isEmpty then
      report.error("scala-go: a Go facade class can only extend other Go facades", td.srcPos)
    val interfaces = sym.info.parents.map(_.classSymbol).filter(_.is(Trait)).map(className)
    val methods = (impl.constr :: impl.body).flatMap {
      case dd: DefDef if isIgnoredMember(dd.symbol) => Nil
      case dd: DefDef if dd.symbol.isConstructor =>
        if dd.termParamss.flatten.nonEmpty then
          report.error("scala-go: Go facade classes cannot have constructor parameters (`new` makes a Go zero value)", dd.srcPos)
        List(ir.MethodDef(methodName(dd.symbol), Nil, None, isStatic = false, isPrivate = false, position(dd)))
      case dd: DefDef if dd.symbol.is(Synthetic) || dd.symbol.name.toString == "$init$" => Nil
      case dd: DefDef => genFacadeMember(dd).toList
      case vd: ValDef if vd.symbol.is(Synthetic) || isModuleField(vd.symbol) => Nil
      case vd: ValDef =>
        report.error("scala-go: Go facade members must be methods (`def`), not fields", vd.srcPos)
        Nil
      case EmptyTree | _: TypeDef => Nil
      case other =>
        report.error("scala-go: unexpected definition in a Go facade", other.srcPos)
        Nil
    }
    val withAliases =
      if facade.isPackage then facade.copy(typeAliases = typeAliases(sym))
      else facade.copy(typeParams = atPhase(erasurePhase)(sym.typeParams).map(_.name.toString))
    ir.ClassDef(className(sym), classKind(sym), sym.is(Abstract), superClass, interfaces, Nil, methods, position(td),
      Some(withAliases))

  /** The public type aliases of a package facade (`type Cmd = () => Msg`), which stand for the Go
   *  types of the package named as them (or by `@GoName`). Erasure drops them, so they are taken
   *  from the class's declarations as seen before.
   */
  private def typeAliases(sym: ClassSymbol): List[(String, String)] =
    atPhase(erasurePhase) {
      sym.info.decls.toList.filter(d => d.isAliasType && !d.is(Private) && !d.is(Synthetic)).map { d =>
        val name = d.name.toString
        goNameOf(d).getOrElse(name) -> name
      }.sortBy(_._2)
    }

  private def isModuleField(sym: Symbol): Boolean = sym.name.toString == "MODULE$"

  private def genFacadeMember(dd: DefDef): Option[ir.MethodDef] =
    val sym = dd.symbol
    if !dd.rhs.isEmpty && !isNativeCall(dd.rhs) then
      report.error("scala-go: Go facade members must be implemented as `= native`", dd.rhs.srcPos)
    if sym.is(Private) then report.error("scala-go: Go facade members cannot be private", dd.srcPos)
    locals = LocalNames()
    val params = dd.termParamss.flatten.map(p => ir.ParamDef(locals.define(p.symbol), toIRType(p.symbol.info)))
    // The signature before erasure: it keeps type arguments and opaque types such as GoString.
    val (paramTypes, resultType, isProperty, typeParams) = atPhase(erasurePhase)(sym.info) match
      case ExprType(res)   => (Nil, res, true, Nil)
      case mt: MethodType  => (mt.paramInfos, mt.resultType, false, Nil)
      case pt: PolyType    =>
        val names = pt.paramNames.map(_.toString)
        pt.resultType match
          case mt: MethodType => (mt.paramInfos, mt.resultType, false, names)
          case other =>
            report.error("scala-go: generic Go facade members need a parameter list", dd.srcPos)
            (Nil, other, true, names)
      case other           => (Nil, other, true, Nil)
    resultType match
      case _: MethodType => report.error("scala-go: Go facade members can have at most one parameter list", dd.srcPos)
      case _             => ()
    val goName = goNameOf(sym).getOrElse(sym.name.toString.stripSuffix("_="))
    val member = ir.GoMember(goName, paramTypes.map(scalaType), scalaType(resultType), isProperty, typeParams)
    Some(ir.MethodDef(methodName(sym), params, None, isStatic = false, isPrivate = false, position(dd), Some(member)))

  private def isNativeCall(tree: Tree): Boolean = tree match
    case Apply(fun, Nil)  => isNativeCall(fun)
    case Typed(expr, _)   => isNativeCall(expr)
    case Block(Nil, expr) => isNativeCall(expr)
    case _                => tree.symbol.exists && tree.symbol.name.toString == "native" &&
                               tree.symbol.owner.showFullName.startsWith("scalago.go.")

  /** The Scala type `tpe` (as seen before erasure), for Go interop. Type aliases are followed as
   *  seen before erasure too: afterwards, `type Cmd = () => Msg` is an alias of the erased
   *  `Function0`, without its type arguments.
   */
  private def scalaType(tpe: Type): ir.ScalaType = atPhase(erasurePhase)(scalaTypeBeforeErasure(tpe))

  private def scalaTypeBeforeErasure(tpe: Type)(using Context): ir.ScalaType =
    tpe match
      // Type parameters of generic facade members (only their signatures have unbound ones) and
      // classes, which uses instantiate.
      case tp: TypeParamRef => ir.ScalaType.Param(tp.paramName.toString)
      case tr: TypeRef if tr.symbol.is(TypeParam) && GoTypeArgs.isFacade(tr.symbol.owner) =>
        ir.ScalaType.Param(tr.symbol.name.toString)
      case tr: TypeRef if tr.symbol.isAliasType || tr.symbol.isOpaqueAlias =>
        goOpaqueType(tr.symbol) match
          case Some(name) => ir.ScalaType.Ref(name, Nil)
          case None if !(tr.translucentSuperType eq tr) => scalaType(tr.translucentSuperType)
          case None => erasedScalaType(tr)
      case ExprType(res) => scalaType(res)
      case AnnotatedType(underlying, _) => scalaType(underlying)
      case AppliedType(tycon, args) =>
        val cls = tycon.typeSymbol
        if cls == defn.ArrayClass then ir.ScalaType.Arr(scalaType(args.head))
        else if cls == defn.RepeatedParamClass then ir.ScalaType.Ref("scala.collection.immutable.Seq", args.map(scalaArg))
        else if goOpaqueType(cls).isDefined then ir.ScalaType.Ref(goOpaqueType(cls).get, args.map(scalaArg))
        else if (cls.isAliasType || cls.isOpaqueAlias) && !(tpe.dealias eq tpe) then scalaType(tpe.dealias)
        else if cls.isClass then ir.ScalaType.Ref(className(cls), args.map(scalaArg))
        else erasedScalaType(tpe)
      case _ => erasedScalaType(tpe)

  /** A use of a generic facade must have its type arguments (a tree transformation that dropped
   *  them would otherwise give Go code instantiated with the wrong types).
   */
  private def checkTypeArgs(tree: Apply): Unit =
    val sym = tree.fun.symbol
    if sym.exists && atPhase(erasurePhase)(GoTypeArgs.isFacade(sym.owner) && GoTypeArgs.isGeneric(sym)) &&
        attachedTypeArgs(tree).isEmpty then
      report.error("scala-go: the type arguments of this use of a generic Go facade were lost (a scala-go bug)", tree.srcPos)

  /** The type arguments of a use of a generic facade (see [[GoTypeArgs]]), as Scala types. */
  private def goTypeArgs(tree: Apply): List[(String, ir.ScalaType)] =
    attachedTypeArgs(tree).fold(Nil)(_.map { (n, t) =>
      // Nothing and Null have no Go type (`slices.Collect(null)` infers Nothing).
      val arg = t.stripTypeVar.widenDealias
      if arg.isExactlyNothing || arg.isNullType then
        report.error(s"scala-go: the type argument of $n of this generic Go facade is ${arg.show}, which no Go type stands for", tree.srcPos)
      n -> scalaType(t)
    })

  /** On the call, or on the selection that erasure made a call (`p.Value`). */
  private def attachedTypeArgs(tree: Apply): Option[List[(String, Type)]] =
    tree.getAttachment(GoTypeArgs.Key).orElse(tree.fun.getAttachment(GoTypeArgs.Key))

  /** The opaque types of `scalago.go` that stand for Go types (`GoString`, `GoSlice`, ...), by
   *  their full name. They are top-level definitions, so owned by package objects.
   */
  private def goOpaqueType(sym: Symbol): Option[String] =
    val name = sym.name.toString
    Option.when(GoOpaqueTypes(name) && sym.owner.is(ModuleClass) &&
      sym.owner.owner.showFullName == "scalago.go")(s"scalago.go.$name")

  private val GoOpaqueTypes = Set("GoString", "GoSlice", "GoMap", "GoChan", "GoPtr", "GoComplex",
    "GoInt", "GoUint", "GoUint64", "GoUintptr", "GoUint32", "GoByte")

  private def scalaArg(arg: Type): ir.ScalaType = arg match
    case bounds: TypeBounds => scalaType(bounds.hi)
    case t                  => scalaType(t)

  private def erasedScalaType(tpe: Type): ir.ScalaType =
    toIRTypeOrObject(tpe) match
      case ir.Type.Class(name)   => ir.ScalaType.Ref(name, Nil)
      case ir.Type.Array(elem)   => ir.ScalaType.Arr(fromErased(elem))
      case ir.Type.Nothing       => ir.ScalaType.Prim(ir.Type.Nothing)
      case ir.Type.Null          => ir.ScalaType.Ref(ir.ClassName.Object, Nil)
      case prim                  => ir.ScalaType.Prim(prim)

  private def fromErased(t: ir.Type): ir.ScalaType = t match
    case ir.Type.Class(name) => ir.ScalaType.Ref(name, Nil)
    case ir.Type.Array(elem) => ir.ScalaType.Arr(fromErased(elem))
    case prim                => ir.ScalaType.Prim(prim)

  /** Members that only exist for the JVM (Java serialization of modules). */
  private def isIgnoredMember(sym: Symbol): Boolean =
    (sym.name == nme.writeReplace && sym.owner.is(ModuleClass)) || isLazyValHandle(sym)

  /** Lazy vals use a static `VarHandle` per field; the plugin replaces its uses with plain field accesses. */
  private def isLazyValHandle(sym: Symbol): Boolean =
    sym.exists && sym.isTerm && !sym.is(Method) && sym.name.toString.endsWith(LazyHandleSuffix)

  private val LazyHandleSuffix = "$lzyHandle"

  private def genField(vd: ValDef): ir.FieldDef =
    val sym = vd.symbol
    ir.FieldDef(fieldName(sym), toIRType(sym.info), isStaticMember(sym), position(vd),
      isVolatile = sym.hasAnnotation(defn.VolatileAnnot))

  private def genMethod(dd: DefDef): ir.MethodDef =
    val sym = dd.symbol
    locals = LocalNames()
    val name = methodName(sym)
    val params = dd.termParamss.flatten.map(p => ir.ParamDef(locals.define(p.symbol), toIRType(p.symbol.info)))
    def genBody(): G = if name.result == ir.Type.Void then genStat(dd.rhs) else genExpr(dd.rhs)
    val body =
      if sym.is(Deferred) || dd.rhs.isEmpty then None
      else if !lenient then Some(genBody())
      else
        try Some(genBody())
        catch case e: Exception => Some(G.Unsupported(s"plugin crashed: $e", name.result)(position(dd)))
    ir.MethodDef(name, params, body, isStaticMember(sym), sym.is(Private), position(dd))

  // -------------------------------------------------------------------------------------------
  // Statements and expressions
  // -------------------------------------------------------------------------------------------

  private def genStat(tree: Tree): G = genTree(tree, isStat = true)
  private def genExpr(tree: Tree): G = genTree(tree, isStat = false)

  private def resultType(tree: Tree, isStat: Boolean): ir.Type =
    if isStat then ir.Type.Void else toIRType(tree.tpe)

  private def genTree(tree: Tree, isStat: Boolean): G =
    val pos = position(tree)
    tree match
      case vd: ValDef =>
        val sym = vd.symbol
        val tpe = toIRType(sym.info)
        val rhs = if tpe == ir.Type.Void then genStat(vd.rhs) else genExpr(vd.rhs)
        G.VarDef(locals.define(sym), tpe, sym.is(Mutable), rhs)(pos)

      case If(cond, thenp, elsep) =>
        G.If(genExpr(cond), genTree(thenp, isStat), genTree(elsep, isStat), resultType(tree, isStat))(pos)

      case Labeled(bind, expr) =>
        G.Labeled(locals.define(bind.symbol), resultType(tree, isStat), genTree(expr, isStat))(pos)

      case Return(expr, from) =>
        val label = if from.symbol.is(Label) then Some(locals(from.symbol)) else None
        val value = if expr.isEmpty || toIRType(expr.tpe) == ir.Type.Void then genStat(expr) else genExpr(expr)
        G.Return(label, value)(pos)

      case WhileDo(cond, body) =>
        val genCond = if cond.isEmpty then G.BooleanLiteral(true)(pos) else genExpr(cond)
        G.While(genCond, genStat(body))(pos)

      case Block(stats, expr) =>
        G.Block(stats.map(genStat), genTree(expr, isStat))(pos)

      case Typed(Super(qual, _), _) =>
        genTree(qual, isStat)

      case Typed(expr, _) =>
        genTree(expr, isStat)

      case Literal(constant) =>
        genLiteral(constant, tree)

      case tree: This =>
        val cls = tree.symbol
        if cls.is(ModuleClass) && cls != currentClass then G.LoadModule(className(cls))(pos)
        else G.This(toIRType(currentClass.typeRef))(pos)

      case tree: Ident =>
        val sym = tree.symbol
        if isStaticModule(sym) then G.LoadModule(className(sym.moduleClass))(pos)
        else if locals.contains(sym) then G.VarRef(locals(sym), toIRType(sym.info))(pos)
        else
          desugarIdent(tree) match
            case sel: Select => genTree(sel, isStat)
            case _           => unsupported(tree, s"reference to ${sym.showFullName}")

      case Select(qualifier, _) =>
        val sym = tree.symbol
        if isStaticModule(sym) then G.LoadModule(className(sym.moduleClass))(pos)
        else if isStaticMember(sym) then G.SelectStatic(className(staticOwner(sym)), fieldName(sym), toIRType(sym.info))(pos)
        else G.Select(genExpr(qualifier), className(staticOwner(sym)), fieldName(sym), toIRType(sym.info))(pos)

      case Assign(lhs, _) if isLazyValHandle(lhs.symbol) =>
        G.Skip()(pos)

      case Assign(lhs, rhs) =>
        val genLhs = lhs match
          case id: Ident if locals.contains(id.symbol) => genExpr(id)
          case id: Ident                               => genExpr(desugarIdent(id))
          case _                                       => genExpr(lhs)
        val assign = G.Assign(genLhs, genExpr(rhs))(pos)
        // A `val` of an Enumeration: register the name the JVM would find by reflection.
        val valName = if inEnumeration then enumerationNames.valName(currentClass, lhs.symbol) else None
        valName match
          case Some(name) =>
            val register = G.ApplyStatically(G.This(toIRType(currentClass.typeRef))(pos),
              enumerationNames.enumerationClassName, enumerationNames.registerName,
              List(genLhs, G.StringLiteral(name)(pos)), ir.Type.Void)(pos)
            G.Block(List(assign), register)(pos)
          case None => assign

      case app: Apply =>
        genApply(app)

      case TypeApply(fun @ Select(qual, _), List(targ)) if fun.symbol == defn.Any_asInstanceOf =>
        val target = toIRType(targ.tpe)
        val arg = genExpr(qual)
        if target.isPrimitive && arg.tpe.isPrimitive then convert(arg, target)
        else G.AsInstanceOf(arg, target)(pos)

      case TypeApply(fun @ Select(qual, _), List(targ)) if fun.symbol == defn.Any_isInstanceOf =>
        G.IsInstanceOf(genExpr(qual), toIRType(targ.tpe))(pos)

      case Try(block, cases, finalizer) =>
        val tpe = resultType(tree, isStat)
        val body = genTree(block, isStat)
        val withCatch =
          if cases.isEmpty then body
          else G.TryCatch(body, cases.map(genCatchCase(_, isStat)), tpe)(pos)
        if finalizer.isEmpty then withCatch else G.TryFinally(withCatch, genStat(finalizer), tpe)(pos)

      case Match(selector, cases) =>
        val tpe = resultType(tree, isStat)
        val (defaults, valueCases) = cases.partition(c => isWildcard(c.pat))
        val genCases = valueCases.map { c =>
          val alts = c.pat match
            case Alternative(pats) => pats
            case pat               => List(pat)
          (alts.map(genExpr), genTree(c.body, isStat))
        }
        val default = defaults.headOption.fold(G.Skip()(pos))(c => genTree(c.body, isStat))
        G.Match(genExpr(selector), genCases, default, tpe)(pos)

      case Closure(env, meth, tpt) =>
        genClosure(tree, env, meth, tpt)

      case lit: JavaSeqLiteral =>
        G.ArrayValue(toIRType(lit.elemtpt.tpe), lit.elems.map(genExpr))(pos)

      case EmptyTree =>
        G.Skip()(pos)

      case _ =>
        unsupported(tree, s"tree `${tree.getClass.getSimpleName}`")

  private def genLiteral(constant: Constant, tree: Tree): G =
    val pos = position(tree)
    constant.tag match
      case UnitTag    => G.Skip()(pos)
      case BooleanTag => G.BooleanLiteral(constant.booleanValue)(pos)
      case ByteTag    => G.ByteLiteral(constant.byteValue)(pos)
      case ShortTag   => G.ShortLiteral(constant.shortValue)(pos)
      case CharTag    => G.CharLiteral(constant.charValue)(pos)
      case IntTag     => G.IntLiteral(constant.intValue)(pos)
      case LongTag    => G.LongLiteral(constant.longValue)(pos)
      case FloatTag   => G.FloatLiteral(constant.floatValue)(pos)
      case DoubleTag  => G.DoubleLiteral(constant.doubleValue)(pos)
      case StringTag  => G.StringLiteral(constant.stringValue)(pos)
      case NullTag    => G.Null()(pos)
      case ClazzTag   => G.ClassOf(toIRType(constant.typeValue))(pos)
      case _          => unsupported(tree, s"literal $constant")

  private def isWildcard(pat: Tree): Boolean = pat match
    case Ident(nme.WILDCARD) => true
    case Bind(_, inner)      => isWildcard(inner)
    case _                   => false

  private def genCatchCase(c: CaseDef, isStat: Boolean): ir.Tree.CatchCase =
    if !c.guard.isEmpty then unsupported(c, "guard in catch clause")
    def caught(pat: Tree): ir.Type = pat match
      case Typed(_, tpt)       => toIRType(tpt.tpe)
      case Ident(nme.WILDCARD) => ir.Type.Class("java.lang.Throwable")
      case _ =>
        unsupported(pat, "catch pattern")
        ir.Type.Object
    c.pat match
      case b @ Bind(_, inner) => ir.Tree.CatchCase(Some(locals.define(b.symbol)), caught(inner), genTree(c.body, isStat))
      case pat                => ir.Tree.CatchCase(None, caught(pat), genTree(c.body, isStat))

  private def genClosure(tree: Tree, env: List[Tree], meth: Tree, tpt: Tree): G =
    val pos = position(tree)
    val target = meth.symbol
    val isStatic = isStaticMember(target)
    val interface = (if tpt.isEmpty then tree.tpe else tpt.tpe).widenDealias.classSymbol
    interface.info.possibleSamMethods match
      case Seq(sam) =>
        val receiver = if isStatic then Nil else List(genExpr(receiverOf(meth)))
        G.Closure(className(interface), methodName(sam.symbol), className(target.owner), methodName(target), isStatic,
          receiver ++ env.map(genExpr))(pos)
      case _ =>
        unsupported(tree, s"closure for ${interface.showFullName}, which has no single abstract method")

  // -------------------------------------------------------------------------------------------
  // Applications
  // -------------------------------------------------------------------------------------------

  private def genApply(tree: Apply): G =
    val pos = position(tree)
    val sym = tree.fun.symbol
    val args = tree.args
    tree.fun match
      case _ if sym == defn.throwMethod =>
        G.Throw(genExpr(args.head))(pos)

      case Select(handle, name) if name.toString == "compareAndSet" && isLazyValHandle(handle.symbol) =>
        genLazyValCompareAndSet(tree, handle.symbol)

      case _ if isAtomicIntrinsic(sym) =>
        val name = sym.name.toString
        def stripCasts(t: G): G = t match
          case G.AsInstanceOf(inner, _) => stripCasts(inner)
          case other                    => other
        stripCasts(genAtomicField(args.head)) match
          case f @ (_: G.Select | _: G.SelectStatic) =>
            val operands = args.tail.map(genExpr)
            name match
              case "compareAndSet" => G.CompareAndSet(f, operands(0), operands(1))(pos)
              case "getAndSet"     => G.GetAndSet(f, operands(0))(pos)
              case _               => G.GetAndAdd(f, operands(0))(pos)
          case _ => unsupported(args.head, s"$name on something other than a field")

      case Select(Super(_, _), _) =>
        G.ApplyStatically(G.This(toIRType(currentClass.typeRef))(pos), className(staticOwner(sym)), methodName(sym),
          args.map(genExpr), methodName(sym).result)(pos)

      case Select(New(tpt), nme.CONSTRUCTOR) if tpt.tpe.widenDealias.isInstanceOf[JavaArrayType] =>
        args match
          case List(length) => G.NewArray(toIRType(tpt.tpe).asInstanceOf[ir.Type.Array].elem, genExpr(length))(pos)
          case _            => unsupported(tree, "multi-dimensional array creation")

      case Select(New(tpt), nme.CONSTRUCTOR) =>
        val cls = className(tpt.tpe.classSymbol)
        SpecializedTuples.generic(cls) match
          case Some(tuple) =>
            val elems = args.map { a =>
              val e = genExpr(a)
              if e.tpe.isPrimitive then G.Box(e)(pos) else e
            }
            G.New(tuple, SpecializedTuples.constructor(args.size), elems)(pos)
          case None =>
            checkTypeArgs(tree)
            G.New(cls, methodName(sym), args.map(genExpr))(pos, goTypeArgs(tree))

      case _ if primitives.isPrimitive(tree.fun) =>
        genPrimitiveOp(tree)

      case _ if Erasure.Boxing.isBox(sym) =>
        G.Box(genExpr(args.head))(pos)

      case _ if Erasure.Boxing.isUnbox(sym) =>
        G.Unbox(genExpr(args.head), toIRType(tree.tpe))(pos)

      case _ if args.isEmpty && SpecializedTuples.isTuple(genericClassName(sym.owner)) &&
          SpecializedTuples.accessor(sym.name.mangledString).isDefined =>
        val Some((accessor, prim)) = SpecializedTuples.accessor(sym.name.mangledString): @unchecked
        val tuple = genericClassName(sym.owner)
        G.Unbox(G.Apply(genExpr(receiverOf(tree.fun)), tuple, accessor, Nil, ir.Type.Object)(pos), prim)(pos)

      case _ =>
        val name = methodName(sym)
        val genArgs = args.map(genExpr)
        if isStaticMember(sym) then G.ApplyStatic(className(staticOwner(sym)), name, genArgs, name.result)(pos)
        else
          val receiver = genExpr(receiverOf(tree.fun))
          if sym.is(Private) || sym.isConstructor then
            G.ApplyStatically(receiver, className(staticOwner(sym)), name, genArgs, name.result)(pos)
          else
            checkTypeArgs(tree)
            G.Apply(receiver, className(staticOwner(sym)), name, genArgs, name.result)(pos, goTypeArgs(tree))

  /** `handle.compareAndSet(obj, expected, update)` on a lazy val's `VarHandle`: an atomic
   *  compare-and-set of the (volatile) lazy val field.
   */
  private def genLazyValCompareAndSet(tree: Apply, handle: Symbol): G =
    val pos = position(tree)
    val List(obj, expected, update) = tree.args.map(genExpr): @unchecked
    val owner = className(handle.owner)
    val field = handle.name.toString.stripSuffix(LazyHandleSuffix)
    G.CompareAndSet(G.Select(obj, owner, field, ir.Type.Object)(pos), expected, update)(pos)

  /** The field operand of an atomic intrinsic. A field that is not private is read through its
   *  getter (`this.result` is `this.result()`): the intrinsic applies to the field itself.
   */
  private def genAtomicField(tree: Tree): G =
    def strip(t: Tree): Tree = t match
      case Typed(inner, _)                                 => strip(inner)
      case TypeApply(fun, _) if fun.symbol == defn.Any_asInstanceOf => strip(receiverOf(fun))
      case Apply(fun, Nil) if fun.symbol.is(Accessor)      => fun
      case other                                           => other
    strip(tree) match
      case getter @ (_: Select | _: Ident) if getter.symbol.is(Accessor) && !getter.symbol.isSetter =>
        val field = getter.symbol.field
        if !field.exists then genExpr(tree)
        else
          val pos = position(tree)
          if isStaticMember(field) then G.SelectStatic(className(staticOwner(field)), fieldName(field), toIRType(field.info))(pos)
          else G.Select(genExpr(receiverOf(getter)), className(staticOwner(field)), fieldName(field), toIRType(field.info))(pos)
      case _ => genExpr(tree)

  /** `scalago.runtime.Intrinsics.compareAndSet(field, expected, update)`, `getAndSet(field, value)`
   *  and `getAndAdd(field, delta)`, used by javalib's atomics.
   */
  private def isAtomicIntrinsic(sym: Symbol): Boolean =
    AtomicIntrinsics(sym.name.toString) && sym.owner.is(ModuleClass) &&
      sym.owner.fullName.toString == "scalago.runtime.Intrinsics$"

  private val AtomicIntrinsics = Set("compareAndSet", "getAndSet", "getAndAdd")

  /** The receiver of a method selection, recovering implicit prefixes of bare identifiers. */
  private def receiverOf(fun: Tree): Tree = fun match
    case Select(qual, _)     => qual
    case id: Ident           => desugarIdentPrefix(id)
    case TypeApply(inner, _) => receiverOf(inner)
    case _                   => EmptyTree

  private def genPrimitiveOp(tree: Apply): G =
    val pos = position(tree)
    val receiver = receiverOf(tree.fun)
    val code = primitives.getPrimitive(tree, receiver.tpe)
    val args = tree.args

    if (code == EQ || code == NE) && args.size == 1 && (isJavaEnum(receiver.tpe) || isJavaEnum(args.head.tpe)) then
      // `==` on a Java enum (Scala 3 enums extending java.lang.Enum, such as java.time's) is
      // identity: Enum.equals is final.
      G.Binary(if code == EQ then ir.BinaryOp.RefEq else ir.BinaryOp.RefNe, ir.Type.Object, genExpr(receiver),
        genExpr(args.head))(pos)
    else if isArithmeticOp(code) || isLogicalOp(code) || isComparisonOp(code) then
      args match
        case Nil        => genUnaryOp(code, genExpr(receiver), toIRType(tree.tpe), pos)
        case arg :: Nil => genBinaryOp(code, genExpr(receiver), genExpr(arg), toIRType(tree.tpe), pos)
        case _          => unsupported(tree, s"primitive operation with ${args.size} arguments")
    else if code == CONCAT then
      G.Binary(ir.BinaryOp.StringConcat, ir.Type.String, genExpr(receiver), genExpr(args.head))(pos)
    else if isCoercion(code) then convert(genExpr(receiver), toIRType(tree.tpe))
    else if isArrayOp(code) then genArrayOp(tree, receiver, code)
    else if code == SYNCHRONIZED then genSynchronized(tree, receiver, args.head)
    else if code == HASH then
      val value = genExpr(receiver)
      val boxed = if value.tpe.isPrimitive then G.Box(value)(pos) else value
      G.ApplyStatic("scala.runtime.Statics", ir.MethodName("anyHash", List(ir.Type.Object), ir.Type.Int), List(boxed),
        ir.Type.Int)(pos)
    else unsupported(tree, s"primitive operation `${tree.fun.symbol.showFullName}`")

  private def isJavaEnum(tpe: Type): Boolean =
    val cls = tpe.widenDealias.classSymbol
    cls.exists && cls.derivesFrom(defn.JavaEnumClass)

  /** `lock.synchronized(body)`: the lock's monitor is held while the body runs, and released
   *  however the body completes.
   */
  private def genSynchronized(tree: Apply, receiver: Tree, body: Tree): G =
    val pos = position(tree)
    val lockVar = locals.fresh("lock")
    def lock = G.VarRef(lockVar, ir.Type.Object)(pos)
    def monitor(op: String, arg: G) =
      G.ApplyStatic(MonitorClass, ir.MethodName(op, List(ir.Type.Object), ir.Type.Void), List(arg), ir.Type.Void)(pos)
    val tpe = toIRType(tree.tpe)
    val genBody = if tpe == ir.Type.Void then genStat(body) else genExpr(body)
    val lockExpr = genExpr(receiver)
    G.Block(
      List(
        G.VarDef(lockVar, ir.Type.Object, mutable = false, if lockExpr.tpe.isPrimitive then G.Box(lockExpr)(pos) else lockExpr)(pos),
        monitor("enter", lock)
      ),
      G.TryFinally(genBody, monitor("exit", lock), tpe)(pos)
    )(pos)

  /** Monitor operations, implemented natively by the linker. */
  private val MonitorClass = "scalago.runtime.Monitor"

  private def genUnaryOp(code: Int, arg: G, tpe: ir.Type, pos: ir.Position): G =
    code match
      case POS  => convert(arg, tpe)
      case NEG  => G.Unary(ir.UnaryOp.Neg, convert(arg, tpe))(pos)
      case NOT  => G.Unary(ir.UnaryOp.BitNot, convert(arg, tpe))(pos)
      case ZNOT => G.Unary(ir.UnaryOp.Not, arg)(pos)

  private def genBinaryOp(code: Int, lhs: G, rhs: G, tpe: ir.Type, pos: ir.Position): G =
    import ir.BinaryOp as Op
    def arith(op: Op) = G.Binary(op, tpe, convert(lhs, tpe), convert(rhs, tpe))(pos)
    def shift(op: Op) =
      val count = if rhs.tpe == ir.Type.Long then rhs else convert(rhs, ir.Type.Int)
      G.Binary(op, tpe, convert(lhs, tpe), count)(pos)
    def compare(op: Op) =
      val common = numericPromotion(lhs.tpe, rhs.tpe)
      G.Binary(op, common, convert(lhs, common), convert(rhs, common))(pos)
    def equality(primOp: Op, refOp: Op, univOp: Op) =
      if lhs.tpe.isPrimitive && rhs.tpe.isPrimitive then compare(primOp)
      else if lhs.tpe == ir.Type.Null || rhs.tpe == ir.Type.Null then G.Binary(refOp, ir.Type.Object, lhs, rhs)(pos)
      else G.Binary(univOp, ir.Type.Object, lhs, rhs)(pos)

    code match
      case ADD => arith(Op.Add)
      case SUB => arith(Op.Sub)
      case MUL => arith(Op.Mul)
      case DIV => arith(Op.Div)
      case MOD => arith(Op.Rem)
      case OR  => arith(Op.Or)
      case AND => arith(Op.And)
      case XOR => arith(Op.Xor)
      case LSL => shift(Op.Shl)
      case ASR => shift(Op.Shr)
      case LSR => shift(Op.UShr)
      case LT  => compare(Op.Lt)
      case LE  => compare(Op.Le)
      case GT  => compare(Op.Gt)
      case GE  => compare(Op.Ge)
      case EQ  => equality(Op.Eq, Op.RefEq, Op.UnivEq)
      case NE  => equality(Op.Ne, Op.RefNe, Op.UnivNe)
      case ID  => G.Binary(Op.RefEq, ir.Type.Object, lhs, rhs)(pos)
      case NI  => G.Binary(Op.RefNe, ir.Type.Object, lhs, rhs)(pos)
      // Short-circuiting boolean operators become conditionals.
      case ZAND => G.If(lhs, rhs, G.BooleanLiteral(false)(pos), ir.Type.Boolean)(pos)
      case ZOR  => G.If(lhs, G.BooleanLiteral(true)(pos), rhs, ir.Type.Boolean)(pos)

  /** Binary numeric promotion (JLS 5.6.2); booleans compare as booleans. */
  private def numericPromotion(a: ir.Type, b: ir.Type): ir.Type =
    import ir.Type.*
    if a == Boolean && b == Boolean then Boolean
    else if a == Double || b == Double then Double
    else if a == Float || b == Float then Float
    else if a == Long || b == Long then Long
    else Int

  private def genArrayOp(tree: Apply, receiver: Tree, code: Int): G =
    val pos = position(tree)
    val array = genExpr(receiver)
    val elemType = array.tpe match
      case ir.Type.Array(elem) => elem
      case other               => return unsupported(tree, s"array operation on non-array type $other")
    if isArrayLength(code) then G.ArrayLength(array)(pos)
    else if isArrayGet(code) then G.ArraySelect(array, genExpr(tree.args.head), elemType)(pos)
    else if isArraySet(code) then
      val List(index, value) = tree.args: @unchecked
      G.Assign(G.ArraySelect(array, genExpr(index), elemType)(pos), genExpr(value))(pos)
    else unsupported(tree, "array operation")

  private def convert(tree: G, to: ir.Type): G =
    if tree.tpe == to || !tree.tpe.isNumeric || !to.isNumeric then tree
    else G.Convert(tree, to)(tree.pos)

  // -------------------------------------------------------------------------------------------
  // Names and types
  // -------------------------------------------------------------------------------------------

  /** The class owning a member. Java static members belong to the compiler's synthetic module
   *  class for the Java class; on the JVM (and in the IR) they belong to the class itself.
   */
  private def staticOwner(sym: Symbol): Symbol =
    val owner = sym.owner
    if owner.is(ModuleClass) && owner.is(JavaDefined) && owner.linkedClass.exists then owner.linkedClass else owner

  private def isStaticModule(sym: Symbol): Boolean =
    sym.is(Module) && !sym.isClass && sym.isStatic

  private def isStaticMember(sym: Symbol): Boolean =
    sym.is(JavaStatic) || sym.isScalaStatic

  private def className(sym: Symbol): ir.ClassName =
    val name = sym.javaClassName
    options.renames.collectFirst { case (from, to) if name.startsWith(from) => to + name.stripPrefix(from) }.getOrElse(name)

  /** The class name, with specialized tuple classes replaced by the generic ones (see
   *  [[SpecializedTuples]]).
   */
  private def genericClassName(sym: Symbol): ir.ClassName =
    val name = className(sym)
    SpecializedTuples.generic(name).getOrElse(name)

  private def fieldName(sym: Symbol): String = sym.name.mangledString

  private def methodName(sym: Symbol): ir.MethodName =
    val info = sym.info
    // Constructors are typed as returning their class; in the IR (as on the JVM) they return void.
    val result = if sym.isConstructor then ir.Type.Void else toIRType(info.finalResultType)
    ir.MethodName(sym.name.mangledString, info.firstParamTypes.map(toIRType), result)

  private def toIRType(tpe: Type): ir.Type =
    tpe.widenDealias match
      case JavaArrayType(elem) => ir.Type.Array(toIRType(elem))
      case t =>
        val sym = t.classSymbol
        if sym == defn.UnitClass then ir.Type.Void
        else if sym == defn.BooleanClass then ir.Type.Boolean
        else if sym == defn.CharClass then ir.Type.Char
        else if sym == defn.ByteClass then ir.Type.Byte
        else if sym == defn.ShortClass then ir.Type.Short
        else if sym == defn.IntClass then ir.Type.Int
        else if sym == defn.LongClass then ir.Type.Long
        else if sym == defn.FloatClass then ir.Type.Float
        else if sym == defn.DoubleClass then ir.Type.Double
        else if sym == defn.NothingClass || sym == defn.NothingClass.moduleClass then ir.Type.Nothing
        else if sym == defn.NullClass then ir.Type.Null
        else if !sym.exists || sym == defn.AnyClass || sym == defn.AnyRefAlias then ir.Type.Object
        else
          genericClassName(sym) match
            case "scala.runtime.Nothing$" => ir.Type.Nothing
            case "scala.runtime.Null$"    => ir.Type.Null
            case name                     => ir.Type.Class(name)

  private def position(tree: Tree): ir.Position =
    if !tree.span.exists then ir.Position.NoPosition
    else
      val sp = tree.sourcePos
      ir.Position(sp.source.path, sp.line + 1, sp.column + 1)

  private def unsupported(tree: Tree, what: String): G =
    if lenient then G.Unsupported(s"$what is not supported yet", toIRTypeOrObject(tree.tpe))(position(tree))
    else
      report.error(s"scala-go: $what is not supported yet", tree.srcPos)
      G.Skip()(position(tree))

  private def toIRTypeOrObject(tpe: Type): ir.Type =
    try toIRType(tpe)
    catch case _: Exception => ir.Type.Object

  /** Assigns each local symbol a name that is unique within the current method. */
  private final class LocalNames:
    private val names = mutable.HashMap.empty[Symbol, String]
    private val used = mutable.HashSet.empty[String]

    def define(sym: Symbol): String =
      val base = sym.name.mangledString
      val name = Iterator.from(0).map(i => if i == 0 then base else s"$base$$$i").find(!used.contains(_)).get
      used += name
      names(sym) = name
      name

    /** A name for a compiler-introduced local, distinct from all others in the method. */
    def fresh(base: String): String =
      val name = Iterator.from(1).map(i => s"$base$$$i").find(!used.contains(_)).get
      used += name
      name

    def contains(sym: Symbol): Boolean = names.contains(sym)
    def apply(sym: Symbol): String = names(sym)
end GoIRCodeGen

object GoIRCodeGen:
  /** @param renames package prefixes (with trailing dot) to rewrite in class names: this lets the
   *    javalib define `java.lang` classes that the Scala compiler does not allow redefining.
   *  @param staticForwarders add static forwarders for the methods of static objects (javalib)
   */
  final case class Options(lenient: Boolean, renames: List[(String, String)], staticForwarders: Boolean)
