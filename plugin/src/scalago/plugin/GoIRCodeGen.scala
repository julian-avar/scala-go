package scalago.plugin

import scala.collection.mutable

import dotty.tools.backend.ScalaPrimitives
import dotty.tools.backend.ScalaPrimitivesOps.*
import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Constants.*
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Flags.*
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

  // Per-class / per-method state
  private var currentClass: ClassSymbol = uninitialized
  private var locals: LocalNames = uninitialized

  private def uninitialized[A]: A = null.asInstanceOf[A]

  def genCompilationUnit(unit: CompilationUnit): Unit =
    def collectClasses(tree: Tree): List[TypeDef] = tree match
      case PackageDef(_, stats)           => stats.flatMap(collectClasses)
      case td: TypeDef if td.isClassDef => List(td)
      case _                              => Nil

    for td <- collectClasses(unit.tpdTree) do
      val errorsBefore = ctx.reporter.errorCount
      val classDef = genClass(td)
      if ctx.reporter.errorCount == errorsBefore then writeIR(classDef)

  private def writeIR(classDef: ir.ClassDef): Unit =
    val parts = classDef.name.split('.')
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
    val impl = td.rhs.asInstanceOf[Template]

    val kind =
      if sym.is(Trait) then ir.ClassKind.Interface
      else if sym.is(ModuleClass) && sym.isStatic then ir.ClassKind.ModuleClass
      else ir.ClassKind.Class

    val superClass =
      if sym.is(Trait) then None
      else Option(sym.superClass).filter(_.exists).map(className)
    val interfaces = sym.info.parents.map(_.classSymbol).filter(_.is(Trait)).map(className)

    val members = (impl.constr :: impl.body).filterNot(t => isIgnoredMember(t.symbol))
    val fields = members.collect { case vd: ValDef if !vd.symbol.is(Method) => genField(vd) }
    val methods = members.collect { case dd: DefDef => genMethod(dd) }

    ir.ClassDef(className(sym), kind, sym.is(Abstract), superClass, interfaces, fields, methods, position(td))

  /** Members that only exist for the JVM (Java serialization of modules). */
  private def isIgnoredMember(sym: Symbol): Boolean =
    (sym.name == nme.writeReplace && sym.owner.is(ModuleClass)) || isLazyValHandle(sym)

  /** Lazy vals use a static `VarHandle` per field; the plugin replaces its uses with plain field accesses. */
  private def isLazyValHandle(sym: Symbol): Boolean =
    sym.exists && sym.isTerm && !sym.is(Method) && sym.name.toString.endsWith(LazyHandleSuffix)

  private val LazyHandleSuffix = "$lzyHandle"

  private def genField(vd: ValDef): ir.FieldDef =
    val sym = vd.symbol
    ir.FieldDef(fieldName(sym), toIRType(sym.info), isStaticMember(sym), position(vd))

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
        G.Assign(genLhs, genExpr(rhs))(pos)

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

      case Select(Super(_, _), _) =>
        G.ApplyStatically(G.This(toIRType(currentClass.typeRef))(pos), className(staticOwner(sym)), methodName(sym),
          args.map(genExpr), methodName(sym).result)(pos)

      case Select(New(tpt), nme.CONSTRUCTOR) if tpt.tpe.widenDealias.isInstanceOf[JavaArrayType] =>
        args match
          case List(length) => G.NewArray(toIRType(tpt.tpe).asInstanceOf[ir.Type.Array].elem, genExpr(length))(pos)
          case _            => unsupported(tree, "multi-dimensional array creation")

      case Select(New(tpt), nme.CONSTRUCTOR) =>
        G.New(className(tpt.tpe.classSymbol), methodName(sym), args.map(genExpr))(pos)

      case _ if primitives.isPrimitive(tree.fun) =>
        genPrimitiveOp(tree)

      case _ if Erasure.Boxing.isBox(sym) =>
        G.Box(genExpr(args.head))(pos)

      case _ if Erasure.Boxing.isUnbox(sym) =>
        G.Unbox(genExpr(args.head), toIRType(tree.tpe))(pos)

      case _ =>
        val name = methodName(sym)
        val genArgs = args.map(genExpr)
        if isStaticMember(sym) then G.ApplyStatic(className(staticOwner(sym)), name, genArgs, name.result)(pos)
        else
          val receiver = genExpr(receiverOf(tree.fun))
          if sym.is(Private) || sym.isConstructor then
            G.ApplyStatically(receiver, className(staticOwner(sym)), name, genArgs, name.result)(pos)
          else G.Apply(receiver, className(staticOwner(sym)), name, genArgs, name.result)(pos)

  /** `handle.compareAndSet(obj, expected, update)` on a lazy val's `VarHandle`, as a plain
   *  (non-atomic) compare-and-set of the field. Programs are single-threaded for now.
   */
  private def genLazyValCompareAndSet(tree: Apply, handle: Symbol): G =
    val pos = position(tree)
    val List(obj, expected, update) = tree.args.map(genExpr): @unchecked
    val owner = className(handle.owner)
    val field = handle.name.toString.stripSuffix(LazyHandleSuffix)
    val objVar = locals.fresh("casObj")
    val updateVar = locals.fresh("casUpdate")
    val expectedVar = locals.fresh("casExpected")
    def fieldRef = G.Select(G.VarRef(objVar, obj.tpe)(pos), owner, field, ir.Type.Object)(pos)
    G.Block(
      List(
        G.VarDef(objVar, obj.tpe, mutable = false, obj)(pos),
        G.VarDef(expectedVar, ir.Type.Object, mutable = false, expected)(pos),
        G.VarDef(updateVar, ir.Type.Object, mutable = false, update)(pos)
      ),
      G.If(
        G.Binary(ir.BinaryOp.RefEq, ir.Type.Object, fieldRef, G.VarRef(expectedVar, ir.Type.Object)(pos))(pos),
        G.Block(List(G.Assign(fieldRef, G.VarRef(updateVar, ir.Type.Object)(pos))(pos)), G.BooleanLiteral(true)(pos))(pos),
        G.BooleanLiteral(false)(pos),
        ir.Type.Boolean
      )(pos)
    )(pos)

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

    if isArithmeticOp(code) || isLogicalOp(code) || isComparisonOp(code) then
      args match
        case Nil        => genUnaryOp(code, genExpr(receiver), toIRType(tree.tpe), pos)
        case arg :: Nil => genBinaryOp(code, genExpr(receiver), genExpr(arg), toIRType(tree.tpe), pos)
        case _          => unsupported(tree, s"primitive operation with ${args.size} arguments")
    else if code == CONCAT then
      G.Binary(ir.BinaryOp.StringConcat, ir.Type.String, genExpr(receiver), genExpr(args.head))(pos)
    else if isCoercion(code) then convert(genExpr(receiver), toIRType(tree.tpe))
    else if isArrayOp(code) then genArrayOp(tree, receiver, code)
    else if code == SYNCHRONIZED then
      // Single-threaded for now: evaluate the lock for its effects, then the body.
      G.Block(List(genStat(receiver)), genExpr(args.head))(pos)
    else if code == HASH then
      val value = genExpr(receiver)
      val boxed = if value.tpe.isPrimitive then G.Box(value)(pos) else value
      G.ApplyStatic("scala.runtime.Statics", ir.MethodName("anyHash", List(ir.Type.Object), ir.Type.Int), List(boxed),
        ir.Type.Int)(pos)
    else unsupported(tree, s"primitive operation `${tree.fun.symbol.showFullName}`")

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
          className(sym) match
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
   */
  final case class Options(lenient: Boolean, renames: List[(String, String)])
