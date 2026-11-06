package scalago.linker

import scala.collection.mutable

import scalago.ir.*
import scalago.ir.Tree.*

/** Emits the Go statements of one method body.
 *
 *  Scala is expression-oriented and Go is not, so trees are emitted against a destination
 *  ([[Dest]]): control structures push the destination into their branches, and any statements
 *  an expression needs are emitted before it. When a later operand emits statements, earlier
 *  operands with possible side effects are first saved in temporaries, preserving Scala's
 *  left-to-right evaluation order.
 *
 *  `try` blocks run inside Go closures (`rt.Try`) so that `recover` can catch panics. A `return`
 *  or a jump to a label outside the closure sets the variable `ctl` to a jump code and returns
 *  from the closure; after the call, the enclosing code performs the jump.
 */
final class FunctionEmitter(ctx: LinkContext, owner: ClassDef, method: MethodDef):
  import FunctionEmitter.*

  private val program = ctx.program
  private var out = mutable.ArrayBuffer.empty[String]
  private var tempCounter = 0
  private val mutableLocals = mutable.HashSet.empty[LocalName]

  private final case class LabelInfo(dest: Dest, depth: Int, code: Int)
  private val labels = mutable.HashMap.empty[String, LabelInfo]
  private val usedLabels = mutable.HashSet.empty[String]

  /** Nesting depth of `rt.Try` closures, and the jump codes requested inside each open closure. */
  private var depth = 0
  private val pendingJumps = mutable.Stack.empty[mutable.LinkedHashSet[Int]]
  private val ReturnCode = 1
  private var nextJumpCode = 2
  private var usesCtl = false
  private var usesRet = false

  private val isVoidMethod = isVoidLike(method.name.result)

  /** Lines of the function body, without the enclosing braces. */
  def emitBody(body: Tree): List[String] =
    genTree(body, if isVoidMethod then Dest.Discard else Dest.Return)
    // Go requires a terminating statement at the end of a function with results.
    if !isVoidMethod && !alwaysReturns(body) then emit("panic(\"unreachable\")")
    val prelude = List.newBuilder[String]
    if usesCtl then prelude += "var ctl int"
    if usesRet then prelude += s"var ret ${ctx.goType(method.name.result)}"
    prelude.result() ++ out.toList

  // ---------------------------------------------------------------------------------------------
  // Output helpers
  // ---------------------------------------------------------------------------------------------

  private def emit(line: String): Unit = out += line

  /** Runs `body` against a fresh buffer and returns the lines it emitted. */
  private def nested[A](body: => A): (List[String], A) =
    val saved = out
    out = mutable.ArrayBuffer.empty
    try
      val result = body
      (out.toList, result)
    finally out = saved

  private def emitIndented(lines: List[String]): Unit = lines.foreach(l => emit("\t" + l))

  private def freshTemp(): String =
    tempCounter += 1
    s"t$tempCounter"

  private def error(tree: Tree, msg: String): String =
    ctx.errors.add(tree.pos, s"${owner.name}.${method.name.simple}: $msg")
    "nil"

  // ---------------------------------------------------------------------------------------------
  // Statements: trees whose value goes to a destination
  // ---------------------------------------------------------------------------------------------

  private def genTree(tree: Tree, dest: Dest): Unit = tree match
    case VarDef(name, tpe, mutable, rhs) =>
      if mutable then mutableLocals += name
      val v = Mangling.local(name)
      if isVoidLike(tpe) then
        genTree(rhs, Dest.Discard)
        emit(s"var $v any = rt.UnitValue")
      else
        val goType = ctx.goType(tpe)
        if isControlStructure(rhs) then
          emit(s"var $v $goType")
          genTree(rhs, Dest.AssignTo(v, tpe))
        else emit(s"var $v $goType = ${adapt(genExpr(rhs), rhs.tpe, tpe)}")
      emit(s"_ = $v")
      deliver(dest, "", Type.Void)

    case Block(stats, expr) =>
      // Code after a statement that always jumps is dead; Go would flag it.
      val (live, dead) = stats.span(!endsAbruptly(_))
      live.foreach(genTree(_, Dest.Discard))
      dead.headOption match
        case Some(jump) => genTree(jump, Dest.Discard)
        case None       => genTree(expr, dest)

    case Skip() =>
      deliver(dest, "", Type.Void)

    case If(cond, thenp, elsep, _) =>
      val c = genExpr(cond)
      val (thenLines, _) = nested(genTree(thenp, dest))
      val (elseLines, _) = nested(genTree(elsep, dest))
      emit(s"if ${unparen(c)} {")
      emitIndented(thenLines)
      if elseLines.nonEmpty then
        emit("} else {")
        emitIndented(elseLines)
      emit("}")

    case While(cond, body) =>
      val (condLines, c) = nested(genExpr(cond))
      val (bodyLines, _) = nested(genTree(body, Dest.Discard))
      if condLines.isEmpty then emit(if c == "true" then "for {" else s"for ${unparen(c)} {")
      else
        emit("for {")
        emitIndented(condLines)
        emit(s"\tif !$c {")
        emit("\t\tbreak")
        emit("\t}")
      emitIndented(bodyLines)
      emit("}")
      // `while (true)` never completes normally (Scala has no `break` statement).
      if !isInfinite(cond) then deliver(dest, "", Type.Void)

    case Labeled(label, _, body) =>
      labels(label) = LabelInfo(dest, depth, nextJumpCode)
      nextJumpCode += 1
      val (bodyLines, _) = nested(genTree(body, dest))
      if usedLabels.contains(label) then
        val goLabel = Mangling.label(label)
        emit(s"$goLabel:")
        emit("for {")
        emitIndented(bodyLines)
        if !endsAbruptly(body) then emit(s"\tbreak $goLabel")
        emit("}")
      else bodyLines.foreach(emit)

    case Return(None, expr) =>
      genTree(expr, Dest.Return)

    case Return(Some(label), expr) =>
      val info = labels(label)
      genTree(expr, info.dest)
      if info.dest != Dest.Return && !endsAbruptly(expr) then
        if info.depth == depth then
          usedLabels += label
          emit(s"break ${Mangling.label(label)}")
        else jumpOutOfClosure(info.code)

    case Assign(lhs, rhs) =>
      genAssign(lhs, rhs)
      deliver(dest, "", Type.Void)

    case Throw(expr) =>
      emit(s"panic(rt.Exc(${genExpr(expr)}))")

    case Match(selector, cases, default, _) =>
      val sel = genExpr(selector)
      val isString = selector.tpe == Type.String
      emit(if isString then s"switch $sel.GoString() {" else s"switch $sel {")
      for (alts, body) <- cases do
        val values = alts.map {
          case StringLiteral(s) => goStringLiteral(s)
          case lit              => genExpr(lit)
        }
        emit(s"case ${values.mkString(", ")}:")
        emitIndented(nested(genTree(body, dest))._1)
      emit("default:")
      emitIndented(nested(genTree(default, dest))._1)
      emit("}")

    case TryCatch(block, cases, tpe) =>
      withValueDest(dest, tpe) { bodyDest =>
        val exc = runInTryClosure(block, bodyDest)
        val branches = cases.map { c =>
          val test = if c.tpe == Type.Class("java.lang.Throwable") then "true" else ctx.typeTest(exc, c.tpe)
          val (lines, _) = nested {
            c.binder.foreach { b =>
              val v = Mangling.local(b)
              emit(s"var $v ${ctx.goType(c.tpe)} = ${ctx.cast(exc, Type.Object, c.tpe)}")
              emit(s"_ = $v")
            }
            genTree(c.body, bodyDest)
          }
          (test, lines)
        }
        emit(s"if $exc != nil {")
        branches.zipWithIndex.foreach { case ((test, lines), i) =>
          emit(if i == 0 then s"\tif ${unparen(test)} {" else s"\t} else if ${unparen(test)} {")
          lines.foreach(l => emit("\t\t" + l))
        }
        emit("\t} else {")
        emit(s"\t\tpanic(rt.Exc($exc))")
        emit("\t}")
        emit("}")
      }

    case TryFinally(block, finalizer, tpe) =>
      withValueDest(dest, tpe) { bodyDest =>
        val (exc, jumps) = runInTryClosure(block, bodyDest, dispatch = false)
        genTree(finalizer, Dest.Discard)
        emit(s"if $exc != nil {")
        emit(s"\tpanic(rt.Exc($exc))")
        emit("}")
        dispatchJumps(jumps)
      }

    case _ =>
      deliver(dest, genExpr(tree), tree.tpe)

  /** Runs `body` with a destination that can receive a value, then delivers it to `dest`. */
  private def withValueDest(dest: Dest, tpe: Type)(body: Dest => Unit): Unit = dest match
    case Dest.AssignTo(_, _) | Dest.Discard => body(dest)
    case Dest.Return if isVoidLike(tpe) =>
      body(Dest.Discard)
      deliver(dest, "", Type.Void)
    case Dest.Return =>
      val temp = freshTemp()
      emit(s"var $temp ${ctx.goType(tpe)}")
      body(Dest.AssignTo(temp, tpe))
      deliver(dest, temp, tpe)

  private def runInTryClosure(block: Tree, dest: Dest): String =
    runInTryClosure(block, dest, dispatch = true)._1

  /** Emits `excN := rt.Try(func() { block })`; returns `excN` and the jumps requested inside. */
  private def runInTryClosure(block: Tree, dest: Dest, dispatch: Boolean): (String, Set[Int]) =
    val exc = s"exc${freshTemp()}"
    pendingJumps.push(mutable.LinkedHashSet.empty)
    depth += 1
    val (lines, _) = nested(genTree(block, dest))
    depth -= 1
    val jumps = pendingJumps.pop().toSet
    emit(s"$exc := rt.Try(func() {")
    emitIndented(lines)
    emit("})")
    if dispatch then dispatchJumps(jumps)
    (exc, jumps)

  /** After a try closure returns: performs the jumps its body requested via `ctl`. */
  private def dispatchJumps(jumps: Set[Int]): Unit =
    var propagate = false
    for code <- jumps.toList.sorted do
      if code == ReturnCode && depth == 0 then
        emit("if ctl == 1 {")
        emit(if isVoidMethod then "\treturn" else "\treturn ret")
        emit("}")
      else
        labels.collectFirst { case (label, info) if info.code == code => (label, info) } match
          case Some((label, info)) if info.depth == depth =>
            usedLabels += label
            emit(s"if ctl == $code {")
            emit("\tctl = 0")
            emit(s"\tbreak ${Mangling.label(label)}")
            emit("}")
          case _ =>
            propagate = true
            pendingJumps.top += code
    if propagate then
      emit("if ctl != 0 {")
      emit("\treturn")
      emit("}")

  /** Inside a try closure: requests a jump to be performed by the enclosing code. */
  private def jumpOutOfClosure(code: Int): Unit =
    usesCtl = true
    pendingJumps.top += code
    emit(s"ctl = $code")
    emit("return")

  /** Sends the value of an already-evaluated expression to `dest`. `expr` is empty for no value. */
  private def deliver(dest: Dest, expr: String, tpe: Type): Unit = dest match
    case Dest.Discard =>
      if expr.nonEmpty then
        if isVoidLike(tpe) then emit(expr)
        else if !isSimple(expr) then emit(s"_ = $expr")
    case Dest.Return if depth > 0 =>
      if !isVoidMethod && !isVoidLike(tpe) then
        usesRet = true
        emit(s"ret = ${adapt(expr, tpe, method.name.result)}")
      else if expr.nonEmpty then deliver(Dest.Discard, expr, tpe)
      jumpOutOfClosure(ReturnCode)
    case Dest.Return =>
      if isVoidMethod then
        if expr.nonEmpty then deliver(Dest.Discard, expr, tpe)
        emit("return")
      else if isVoidLike(tpe) then
        // Only reachable in ill-typed trees; keep the Go code well-formed.
        if expr.nonEmpty then deliver(Dest.Discard, expr, tpe)
        emit("panic(\"unreachable\")")
      else emit(s"return ${adapt(expr, tpe, method.name.result)}")
    case Dest.AssignTo(v, target) =>
      if expr.nonEmpty then emit(s"$v = ${adapt(expr, tpe, target)}")

  private def genAssign(lhs: Tree, rhs: Tree): Unit = lhs match
    case VarRef(name, tpe) =>
      emit(s"${Mangling.local(name)} = ${adapt(genExpr(rhs), rhs.tpe, tpe)}")
    case Select(qual, cls, field, tpe) =>
      val List(q, r) = genExprs(List(qual, rhs), List(qual.tpe, tpe)): @unchecked
      emit(store(s"${fieldAccess(q, qual.tpe, cls)}.${Mangling.field(field)}", cls, field, r))
    case SelectStatic(cls, field, tpe) =>
      val r = adapt(genExpr(rhs), rhs.tpe, tpe)
      initStatics(cls)
      emit(store(Mangling.staticField(cls, field), cls, field, r))
    case ArraySelect(array, index, _) =>
      val List(a, i, r) = genExprs(List(array, index, rhs)): @unchecked
      emit(s"$a.Data[$i] = $r")
    case _ =>
      error(lhs, s"cannot assign to ${lhs.productPrefix}")

  private def store(fieldRef: String, cls: ClassName, field: String, value: String): String =
    if ctx.isVolatile(cls, field) then s"$fieldRef.Store($value)" else s"$fieldRef = $value"

  private def load(fieldRef: String, cls: ClassName, field: String): String =
    if ctx.isVolatile(cls, field) then s"$fieldRef.Load()" else fieldRef

  /** The storage of the volatile field `field` refers to, for `CompareAndSet`. */
  private def volatileRef(tree: Tree, field: Tree): Option[String] = field match
    case Select(qual, cls, f, _) if ctx.isVolatile(cls, f) =>
      Some(s"${fieldAccess(genExpr(qual), qual.tpe, cls)}.${Mangling.field(f)}")
    case SelectStatic(cls, f, _) if ctx.isVolatile(cls, f) =>
      initStatics(cls)
      Some(Mangling.staticField(cls, f))
    case _ =>
      error(tree, "compareAndSet needs a @volatile field")
      None

  /** Accesses the struct of `cls` holding a field, from a reference to an instance. */
  private def fieldAccess(q: String, qualType: Type, cls: ClassName): String =
    if ctx.goType(qualType) != Mangling.iface(cls) && ctx.goType(qualType) == "any" then
      s"rt.Cast[${Mangling.iface(cls)}]($q).${Mangling.accessor(cls)}()"
    else s"$q.${Mangling.accessor(cls)}()"

  /** Triggers the static initializer of `cls` (unless we are in `cls` itself). */
  private def initStatics(cls: ClassName): Unit =
    if cls != owner.name && ctx.analyzer.staticInit.contains(cls) then emit(s"${Mangling.clinit(cls)}()")

  // ---------------------------------------------------------------------------------------------
  // Expressions
  // ---------------------------------------------------------------------------------------------

  /** Go expression for `tree`, emitting any statements it needs first. Empty for void trees. */
  private def genExpr(tree: Tree): String = tree match
    case Block(stats, expr) =>
      val (live, dead) = stats.span(!endsAbruptly(_))
      live.foreach(genTree(_, Dest.Discard))
      dead.headOption match
        case Some(jump) =>
          genTree(jump, Dest.Discard)
          zeroValue(expr.tpe)
        case None => genExpr(expr)

    case If(cond, thenp, BooleanLiteral(false), Type.Boolean) => shortCircuit(cond, "&&", thenp)
    case If(cond, BooleanLiteral(true), elsep, Type.Boolean)  => shortCircuit(cond, "||", elsep)

    case _ if isVoidLike(tree.tpe) && isControlStructure(tree) =>
      genTree(tree, Dest.Discard)
      ""

    case _ if isControlStructure(tree) =>
      viaTemp(tree)

    case BooleanLiteral(v) => v.toString
    case CharLiteral(v)    => s"uint16(${v.toInt})"
    case ByteLiteral(v)    => s"int8($v)"
    case ShortLiteral(v)   => s"int16($v)"
    case IntLiteral(v)     => s"int32($v)"
    case LongLiteral(v)    => s"int64($v)"
    case FloatLiteral(v)   => s"float32(${floatingLiteral(v.toDouble, v.toString)})"
    case DoubleLiteral(v)  => s"float64(${floatingLiteral(v, v.toString)})"
    case StringLiteral(v)  => ctx.stringLiteral(v)
    case Null()            => "nil"
    case ClassOf(t)        => s"""rt.ClassOf("${jvmClassName(t)}")"""

    case VarRef(name, _) => Mangling.local(name)
    case This(_)         => "this"

    case LoadModule(cls) =>
      if program.contains(cls) then s"${Mangling.module(cls)}()"
      else error(tree, s"module $cls is not available (no IR)")

    case Select(qual, cls, field, _) =>
      load(s"${fieldAccess(genExpr(qual), qual.tpe, cls)}.${Mangling.field(field)}", cls, field)

    case SelectStatic(cls, field, _) =>
      Natives.staticField(cls, field) match
        case Some(native) => native
        case None =>
          initStatics(cls)
          load(Mangling.staticField(cls, field), cls, field)

    case CompareAndSet(field, expected, update) =>
      volatileRef(tree, field) match
        case Some(ref) =>
          // The field reference is evaluated first, then the operands (left to right).
          val refVar = freshTemp()
          emit(s"$refVar := &$ref")
          val List(e, u) = genExprs(List(expected, update), List(field.tpe, field.tpe)): @unchecked
          s"$refVar.CompareAndSet($e, $u)"
        case None => "false"

    case New(cls, _, _) if program.isFacade(cls) =>
      ctx.interop.zeroValue(cls, tree.pos)

    case New(cls, ctor, args) =>
      Natives.lookup(cls, ctor) match
        case Some(native) => genNative(native, None, args, ctor.params)
        case None         => s"${Mangling.newInstance(cls, ctor)}(${genExprs(args, ctor.params).mkString(", ")})"

    case tree: Apply           => genApply(tree)
    case tree: ApplyStatically => genApplyStatically(tree)

    case ApplyStatic(cls, m, args, _) =>
      Natives.lookup(cls, m) match
        case Some(native) => genNative(native, None, args, m.params)
        case None =>
          val as = genExprs(args, m.params)
          initStatics(cls)
          s"${Mangling.impl(cls, m)}(${as.mkString(", ")})"

    case Unary(op, arg) =>
      val a = genExpr(arg)
      op match
        case UnaryOp.Neg    => s"(-$a)"
        case UnaryOp.BitNot => s"(^$a)"
        case UnaryOp.Not    => s"(!$a)"

    case tree: Binary => genBinary(tree)

    case Convert(arg, to) =>
      // Go rejects constant conversions that overflow (`int8(300)`), so fold literals here.
      foldConversion(arg, to) match
        case Some(folded) => genExpr(folded)
        case None         => genConvert(genExpr(arg), arg.tpe, to)

    case Box(arg)        => s"any(${genExpr(arg)})"
    case Unbox(arg, tpe) => s"rt.Cast[${ctx.goType(tpe)}](${genExpr(arg)})"

    case AsInstanceOf(arg, tpe) => ctx.cast(genExpr(arg), arg.tpe, tpe)
    case IsInstanceOf(arg, tpe) => ctx.typeTest(genExpr(arg), tpe)

    case NewArray(elem, length) => s"rt.NewArray[${ctx.goArrayElemType(elem)}](${genExpr(length)})"
    case ArrayValue(elem, elems) =>
      s"rt.ArrayOf[${ctx.goArrayElemType(elem)}](${genExprs(elems).mkString(", ")})"
    case ArrayLength(array) => s"int32(len(${genExpr(array)}.Data))"
    case ArraySelect(array, index, elemType) =>
      val List(a, i) = genExprs(List(array, index)): @unchecked
      ctx.cast(s"$a.Data[$i]", Type.Object, elemType)

    case Unsupported(msg, _) =>
      error(tree, msg)

    case _ =>
      error(tree, s"unexpected ${tree.productPrefix} in expression position")

  /** A placeholder value after code that never completes (keeps the Go code well-typed). */
  private def zeroValue(t: Type): String =
    val goType = ctx.goType(t)
    if goType.isEmpty then "" else s"*new($goType)"

  private def viaTemp(tree: Tree): String =
    val temp = freshTemp()
    val goType = ctx.goType(tree.tpe)
    emit(s"var $temp ${if goType.isEmpty then "any" else goType}")
    genTree(tree, Dest.AssignTo(temp, tree.tpe))
    temp

  /** `cond && rhs` / `cond || rhs` when `rhs` needs no statements; a conditional otherwise. */
  private def shortCircuit(cond: Tree, op: String, rhs: Tree): String =
    val c = genExpr(cond)
    val (rhsLines, r) = nested(genExpr(rhs))
    if rhsLines.isEmpty then s"($c $op $r)"
    else
      val temp = freshTemp()
      emit(s"var $temp bool = $c")
      emit(if op == "&&" then s"if $temp {" else s"if !$temp {")
      emitIndented(rhsLines)
      emit(s"\t$temp = $r")
      emit("}")
      temp

  /** Evaluates `trees` left to right, spilling earlier results that later statements could affect.
   *  With `expected` types, the results are adapted to them (see [[adapt]]).
   */
  private def genExprs(trees: List[Tree], expected: List[Type] = Nil): List[String] =
    val exprs = mutable.ArrayBuffer.empty[String]
    val marks = mutable.ArrayBuffer.empty[Int]
    for (t, i) <- trees.zipWithIndex do
      val e = genExpr(t)
      exprs += (if i < expected.size then adapt(e, t.tpe, expected(i)) else e)
      marks += out.size
    val end = out.size
    for i <- trees.indices.reverse if marks(i) < end && !isPure(trees(i)) do
      val temp = freshTemp()
      val goType = ctx.goType(trees(i).tpe)
      out.insert(marks(i), s"var $temp ${if goType.isEmpty then "any" else goType} = ${exprs(i)}")
      exprs(i) = temp
    exprs.toList

  /** Converts a Go value of IR type `from` for a location of IR type `to`. Scala upcasts need no
   *  conversion in the IR, but a class that is never instantiated has Go type `any`, which Go does
   *  not implicitly convert to the interface type of its (instantiated) supertypes.
   */
  private def adapt(expr: String, from: Type, to: Type): String =
    val target = ctx.goType(to)
    if expr.isEmpty || target.isEmpty || target == "any" || to.isPrimitive || ctx.goType(from) != "any" then expr
    else s"rt.Cast[$target]($expr)"

  private def isPure(tree: Tree): Boolean = tree match
    case _: BooleanLiteral | _: CharLiteral | _: ByteLiteral | _: ShortLiteral | _: IntLiteral | _: LongLiteral |
        _: FloatLiteral | _: DoubleLiteral | _: StringLiteral | _: Null | _: This | _: ClassOf =>
      true
    case VarRef(name, _) => !mutableLocals.contains(name)
    case _               => false

  // ---------------------------------------------------------------------------------------------
  // Calls
  // ---------------------------------------------------------------------------------------------

  private def genNative(native: Natives.Native, receiver: Option[Tree], args: List[Tree], params: List[Type]): String =
    native match
      case Natives.Native.Static(gen) => gen(genExprs(args, params))
      case Natives.Native.Instance(gen) =>
        val r :: as = genExprs(receiver.toList ++ args, receiver.map(_.tpe).toList ++ params): @unchecked
        gen(r, as)

  private def genApply(tree: Apply): String =
    val Apply(receiver, declaringClass, m, args, _) = tree
    val receiverGoType = ctx.goType(receiver.tpe)
    def isVirtual = receiver.tpe match
      case Type.Class(cls) => receiverGoType == Mangling.iface(cls) && program.vtable(cls).contains(m)
      case _               => false
    def hijackedNative = Hijacked.instanceGoType.collectFirst {
      case (h, goType) if goType == receiverGoType && Natives.lookup(h, m).isDefined => Natives.lookup(h, m).get
    }
    program.facadeMember(declaringClass, m) match
      case Some((facade, member)) => return genFacadeUse(tree, facade, member)
      case None                   => ()
    Natives.lookup(declaringClass, m) match
      case Some(native) => genNative(native, Some(receiver), args, m.params)
      case None if isVirtual =>
        val r :: as = genExprs(receiver :: args, receiver.tpe :: m.params): @unchecked
        s"$r.${Mangling.virtualMethod(m)}(${as.mkString(", ")})"
      case None =>
        Natives.objectMethod(m).orElse(hijackedNative) match
          case Some(native) => genNative(native, Some(receiver), args, m.params)
          case None =>
            val r :: as = genExprs(receiver :: args, receiver.tpe :: m.params): @unchecked
            s"${ctx.dispatcher(m)}(${(r :: as).mkString(", ")})"

  /** A use of a Go facade member. Arguments are evaluated into variables first, then buffered
   *  Scala output is flushed, so that output stays ordered with what Go code writes.
   */
  private def genFacadeUse(tree: Apply, facade: ClassDef, member: MethodDef): String =
    val (argTrees, argTypes) = GoInterop.facadeArgs(tree.args, member.goMember.get.params).unzip
    val isPackage = facade.goFacade.exists(_.isPackage)
    val trees = if isPackage then argTrees else tree.receiver :: argTrees
    val types = if isPackage then argTypes.map(GoInterop.erased) else tree.receiver.tpe :: argTypes.map(GoInterop.erased)
    val values = genExprs(trees, types).zip(types).map { (e, t) =>
      if isSimple(e) && e != "nil" then e
      else
        val temp = freshTemp()
        emit(s"var $temp ${ctx.goType(t)} = $e")
        temp
    }
    emit("rt.FlushStdout()")
    val (receiver, args) = if isPackage then (None, values) else (Some(values.head), values.tail)
    ctx.interop.use(facade, member, receiver, args, argTypes, tree.pos, emit)

  private def genApplyStatically(tree: ApplyStatically): String =
    val ApplyStatically(receiver, cls, m, args, _) = tree
    if Natives.isNoOpConstructor(cls, m) || program.isFacade(cls) then ""
    else Natives.lookup(cls, m) match
      case Some(native) => genNative(native, Some(receiver), args, m.params)
      case None =>
        program.resolveImplementation(cls, m) match
          case Some(implClass) =>
            val r :: as = genExprs(receiver :: args, Type.Class(implClass) :: m.params): @unchecked
            s"${Mangling.impl(implClass, m)}(${(r :: as).mkString(", ")})"
          case None =>
            error(tree, s"method $cls.$m is not available")

  // ---------------------------------------------------------------------------------------------
  // Primitive operations
  // ---------------------------------------------------------------------------------------------

  private def genBinary(tree: Binary): String =
    import BinaryOp.*
    val Binary(op, opType, lhs, rhs) = tree
    val List(a, b) = genExprs(List(lhs, rhs)): @unchecked
    def infix(goOp: String) = s"($a $goOp $b)"
    val mask = if opType == Type.Long then 63 else 31
    // Go rejects constant conversions that overflow (`uint32(int32(-1))`), so literal operands of
    // unsigned conversions are converted here.
    def shiftCount = rhs match
      case IntLiteral(v)  => s"uint64(${v & mask})"
      case LongLiteral(v) => s"uint64(${v & mask})"
      case _              => s"(uint64($b) & $mask)"
    def unsigned(goType: String) = lhs match
      case IntLiteral(v)  => s"$goType(${v.toLong & 0xffffffffL})"
      case LongLiteral(v) => s"$goType(${java.lang.Long.toUnsignedString(v)})"
      case _              => s"$goType($a)"
    op match
      case Add | Sub | Mul | Div =>
        infix(op match { case Add => "+"; case Sub => "-"; case Mul => "*"; case _ => "/" })
      case Rem =>
        opType match
          case Type.Double => s"rt.FRem($a, $b)"
          case Type.Float  => s"float32(rt.FRem(float64($a), float64($b)))"
          case _           => infix("%")
      case Or  => if opType == Type.Boolean then s"rt.Or($a, $b)" else infix("|")
      case And => if opType == Type.Boolean then s"rt.And($a, $b)" else infix("&")
      case Xor => if opType == Type.Boolean then infix("!=") else infix("^")
      case Shl => s"($a << $shiftCount)"
      case Shr => s"($a >> $shiftCount)"
      case UShr =>
        if opType == Type.Long then s"int64(${unsigned("uint64")} >> $shiftCount)"
        else s"int32(${unsigned("uint32")} >> $shiftCount)"
      case Eq     => infix("==")
      case Ne     => infix("!=")
      case Lt     => infix("<")
      case Le     => infix("<=")
      case Gt     => infix(">")
      case Ge     => infix(">=")
      case RefEq  => s"(any($a) == any($b))"
      case RefNe  => s"(any($a) != any($b))"
      case UnivEq => s"rt.Equals($a, $b)"
      case UnivNe => s"(!rt.Equals($a, $b))"
      case StringConcat => s"rt.Concat($a, $b)"

  /** Converts a numeric literal with JVM semantics (Scala's own conversions are the JVM's). */
  private def foldConversion(lit: Tree, to: Type): Option[Tree] =
    val pos = lit.pos
    val integral: Option[Long] = lit match
      case CharLiteral(v)  => Some(v.toLong)
      case ByteLiteral(v)  => Some(v.toLong)
      case ShortLiteral(v) => Some(v.toLong)
      case IntLiteral(v)   => Some(v.toLong)
      case LongLiteral(v)  => Some(v)
      case _               => None
    val floating: Option[Double] = lit match
      case FloatLiteral(v)  => Some(v.toDouble)
      case DoubleLiteral(v) => Some(v)
      case _                => None
    def fromInt(i: Int): Option[Tree] = to match
      case Type.Char  => Some(CharLiteral(i.toChar)(pos))
      case Type.Byte  => Some(ByteLiteral(i.toByte)(pos))
      case Type.Short => Some(ShortLiteral(i.toShort)(pos))
      case Type.Int   => Some(IntLiteral(i)(pos))
      case _          => None
    (integral, floating) match
      case (Some(l), _) =>
        to match
          case Type.Long   => Some(LongLiteral(l)(pos))
          case Type.Float  => Some(FloatLiteral(l.toFloat)(pos))
          case Type.Double => Some(DoubleLiteral(l.toDouble)(pos))
          case _           => fromInt(l.toInt)
      case (_, Some(d)) =>
        to match
          case Type.Long   => Some(LongLiteral(d.toLong)(pos))
          case Type.Float  => Some(FloatLiteral(d.toFloat)(pos))
          case Type.Double => Some(DoubleLiteral(d)(pos))
          case _           => fromInt(d.toInt)
      case _ => None

  private def genConvert(a: String, from: Type, to: Type): String =
    val fromFloating = from == Type.Float || from == Type.Double
    to match
      case Type.Int if fromFloating                           => s"rt.D2I(float64($a))"
      case Type.Long if fromFloating                          => s"rt.D2L(float64($a))"
      case Type.Char | Type.Byte | Type.Short if fromFloating => s"${ctx.goType(to)}(rt.D2I(float64($a)))"
      case _                                                  => s"${ctx.goType(to)}($a)"
end FunctionEmitter

object FunctionEmitter:
  /** Where the value of a tree goes. */
  enum Dest:
    /** Evaluated for effects only. */
    case Discard
    /** Returned from the function (for void functions: the function returns afterwards). */
    case Return
    /** Assigned to a variable of the given IR type. */
    case AssignTo(variable: String, tpe: Type)

  private def isVoidLike(t: Type): Boolean = t == Type.Void || t == Type.Nothing

  private def isInfinite(whileCond: Tree): Boolean = whileCond == BooleanLiteral(true)(whileCond.pos)

  /** Whether control never falls through the end of `tree` (it returns, throws, breaks or loops forever). */
  private def endsAbruptly(tree: Tree): Boolean = tree match
    case _: Return | _: Throw   => true
    case Block(stats, expr)     => stats.exists(endsAbruptly) || endsAbruptly(expr)
    case If(_, thenp, elsep, _) => endsAbruptly(thenp) && endsAbruptly(elsep)
    case While(cond, _)         => isInfinite(cond)
    case Match(_, cases, d, _)  => cases.forall((_, body) => endsAbruptly(body)) && endsAbruptly(d)
    case _                      => false

  /** Whether emitting `tree` against `Dest.Return` ends in a Go terminating statement.
   *
   *  A labeled block in tail position also has `Dest.Return`, so jumps to it become `return`s:
   *  every `Return` reachable through tail positions returns from the function.
   */
  private def alwaysReturns(tree: Tree): Boolean = tree match
    case Block(stats, expr)                => stats.exists(endsAbruptly) || alwaysReturns(expr)
    case If(_, thenp, elsep, _)            => alwaysReturns(thenp) && alwaysReturns(elsep)
    case Match(_, cases, d, _)             => cases.forall((_, b) => alwaysReturns(b)) && alwaysReturns(d)
    case Labeled(_, _, body)               => alwaysReturns(body)
    case _: Return | _: Throw              => true
    case While(cond, _)                    => isInfinite(cond)
    case _: Skip | _: VarDef | _: Assign   => false
    case _                                 => true

  private def isControlStructure(tree: Tree): Boolean = tree match
    case _: If | _: Labeled | _: Block | _: While | _: Return | _: VarDef | _: Assign | _: Skip | _: Throw | _: Match |
        _: TryCatch | _: TryFinally =>
      true
    case _ => false

  private def isSimple(expr: String): Boolean = expr.forall(c => c.isLetterOrDigit || c == '_')

  /** Drops redundant outer parentheses (`(a < b)` becomes `a < b`) for `if` and `for` headers. */
  private def unparen(expr: String): String =
    def closesAtEnd: Boolean =
      var depth = 0
      expr.indices.forall { i =>
        expr(i) match
          case '(' => depth += 1
          case ')' => depth -= 1
          case _   =>
        depth > 0 || i == expr.length - 1
      }
    if expr.startsWith("(") && expr.endsWith(")") && !expr.contains('"') && closesAtEnd then
      expr.substring(1, expr.length - 1)
    else expr

  private def floatingLiteral(d: Double, javaString: String): String =
    if d.isNaN then "rt.NaN()"
    else if d.isPosInfinity then "rt.Inf(1)"
    else if d.isNegInfinity then "rt.Inf(-1)"
    else if d == 0 && 1 / d < 0 then "rt.NegZero()"
    else javaString

  /** The JVM name of a class, as returned by `Class.getName`. */
  def jvmClassName(t: Type): String = t match
    case Type.Class(name) => name
    case Type.Array(elem) =>
      elem match
        case Type.Class(name) => s"[L$name;"
        case _: Type.Array    => s"[${jvmClassName(elem)}"
        case prim             => s"[${prim.descriptor}"
    case Type.Boolean => "boolean"
    case Type.Char    => "char"
    case Type.Byte    => "byte"
    case Type.Short   => "short"
    case Type.Int     => "int"
    case Type.Long    => "long"
    case Type.Float   => "float"
    case Type.Double  => "double"
    case _            => "void"

  /** A Go interpreted string literal (lone surrogates must be handled by the caller). */
  def goStringLiteral(s: String): String =
    val sb = StringBuilder("\"")
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      c match
        case '"'  => sb ++= "\\\""
        case '\\' => sb ++= "\\\\"
        case '\n' => sb ++= "\\n"
        case '\t' => sb ++= "\\t"
        case '\r' => sb ++= "\\r"
        case _ if c >= 0x20 && c < 0x7f => sb += c
        case _ if Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s.charAt(i + 1)) =>
          sb ++= f"\\U${Character.toCodePoint(c, s.charAt(i + 1))}%08x"
          i += 1
        case _ => sb ++= f"\\u${c.toInt}%04x"
      i += 1
    sb += '"'
    sb.toString

  /** Whether `s` contains a surrogate that is not part of a pair (not representable in UTF-8). */
  def hasLoneSurrogate(s: String): Boolean =
    var i = 0
    var lone = false
    while i < s.length && !lone do
      val c = s.charAt(i)
      if Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s.charAt(i + 1)) then i += 2
      else
        lone = Character.isSurrogate(c)
        i += 1
    lone
