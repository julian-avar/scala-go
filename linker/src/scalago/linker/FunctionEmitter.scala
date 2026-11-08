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
 *
 *  Each emitted line carries the position of the tree being emitted ([[GoLine]]), from which
 *  [[LineDirectives]] makes Go report Scala positions. Lines that cannot fail or call anything
 *  (braces, jumps, declarations) are emitted without one (`emitPlain`).
 */
final class FunctionEmitter(ctx: LinkContext, owner: ClassDef, method: MethodDef):
  import FunctionEmitter.*

  private val program = ctx.program
  private var out = mutable.ArrayBuffer.empty[GoLine]
  /** The position of the innermost tree being emitted that has one. */
  private var pos: Position = method.pos
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

  /** Classes whose struct field accesses on `this` reach. `this` is an interface, and getting a
   *  struct from it (`this.A_C()`) is a call Go cannot inline, so each body does it once, in its
   *  prelude, instead of at every field access.
   */
  private val thisStructs = mutable.LinkedHashSet.empty[ClassName]

  /** Lines of the function body, without the enclosing braces. */
  def emitBody(body: Tree): List[GoLine] =
    genTree(body, if isVoidMethod then Dest.Discard else Dest.Return)
    // Go requires a terminating statement at the end of a function with results.
    if !isVoidMethod && !alwaysReturns(body) then emitPlain("panic(\"unreachable\")")
    val prelude = List.newBuilder[GoLine]
    for cls <- thisStructs do
      prelude += GoLine(s"${Mangling.thisStruct(cls)} := this.${Mangling.accessor(cls)}()", Position.NoPosition)
    if usesCtl then prelude += GoLine("var ctl int", Position.NoPosition)
    if usesRet then prelude += GoLine(s"var ret ${ctx.goType(method.name.result)}", Position.NoPosition)
    prelude.result() ++ out.toList

  // ---------------------------------------------------------------------------------------------
  // Output helpers
  // ---------------------------------------------------------------------------------------------

  private def emit(line: String): Unit = out += GoLine(line, pos)

  /** Emits a line that needs no source position: it cannot fail or call anything. */
  private def emitPlain(line: String): Unit = out += GoLine(line, Position.NoPosition)

  /** Runs `body` with `p` as the current position (unless `p` is unknown). */
  private inline def atPos[A](p: Position)(inline body: A): A =
    if p.line <= 0 then body
    else
      val saved = pos
      pos = p
      val result = body
      pos = saved
      result

  /** Runs `body` against a fresh buffer and returns the lines it emitted. */
  private def nested[A](body: => A): (List[GoLine], A) =
    val saved = out
    out = mutable.ArrayBuffer.empty
    try
      val result = body
      (out.toList, result)
    finally out = saved

  private def emitIndented(lines: List[GoLine]): Unit = lines.foreach(l => out += l.indented)

  private def freshTemp(): String =
    tempCounter += 1
    s"t$tempCounter"

  private def error(tree: Tree, msg: String): String =
    ctx.errors.add(tree.pos, s"${owner.name}.${method.name.simple}: $msg")
    "nil"

  // ---------------------------------------------------------------------------------------------
  // Statements: trees whose value goes to a destination
  // ---------------------------------------------------------------------------------------------

  private def genTree(tree: Tree, dest: Dest): Unit = atPos(tree.pos) {
    ctx.analyzer.missingUse(tree) match
      case Some(use) =>
        throwMissing(use)
        if dest != Dest.Discard then deliver(dest, zeroValue(tree.tpe), tree.tpe)
      case None => genTreeHere(tree, dest)
  }

  /** A use of an API without an implementation, linked with `--missing-apis throw`: throws the
   *  JVM's linkage error. (A call, not a `panic`, so that the code after it stays reachable for Go.)
   */
  private def throwMissing(use: Analyzer.MissingUse): Unit =
    emit(s"rt.ThrowKind(rt.${use.kind.goKind}, ${goStringLiteral(s"scala-go: ${use.message}")})")

  private def genTreeHere(tree: Tree, dest: Dest): Unit = tree match
    case VarDef(name, tpe, mutable, rhs) =>
      if mutable then mutableLocals += name
      val v = Mangling.local(name)
      if isVoidLike(tpe) then
        genTree(rhs, Dest.Discard)
        emitPlain(s"var $v any = rt.UnitValue")
      else
        val goType = ctx.goType(tpe)
        if isControlStructure(rhs) then
          emitPlain(s"var $v $goType")
          genTree(rhs, Dest.AssignTo(v, tpe))
        else emit(s"var $v $goType = ${adapt(genExpr(rhs), rhs.tpe, tpe)}")
      emitPlain(s"_ = $v")
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
        emitPlain("} else {")
        emitIndented(elseLines)
      emitPlain("}")

    case While(cond, body) =>
      val (condLines, c) = nested(genExpr(cond))
      val (bodyLines, _) = nested(genTree(body, Dest.Discard))
      if condLines.isEmpty then
        if c == "true" then emitPlain("for {") else emit(s"for ${unparen(c)} {")
      else
        emitPlain("for {")
        emitIndented(condLines)
        emit(s"\tif !$c {")
        emitPlain("\t\tbreak")
        emitPlain("\t}")
      emitIndented(bodyLines)
      emitPlain("}")
      // `while (true)` never completes normally (Scala has no `break` statement).
      if !isInfinite(cond) then deliver(dest, "", Type.Void)

    case Labeled(label, _, body) =>
      labels(label) = LabelInfo(dest, depth, nextJumpCode)
      nextJumpCode += 1
      val (bodyLines, _) = nested(genTree(body, dest))
      if usedLabels.contains(label) then
        val goLabel = Mangling.label(label)
        emitPlain(s"$goLabel:")
        emitPlain("for {")
        emitIndented(bodyLines)
        if !endsAbruptly(body) then emitPlain(s"\tbreak $goLabel")
        emitPlain("}")
      else out ++= bodyLines

    case Return(None, expr) =>
      genTree(expr, Dest.Return)

    case Return(Some(label), expr) =>
      val info = labels(label)
      genTree(expr, info.dest)
      if info.dest != Dest.Return && !endsAbruptly(expr) then
        if info.depth == depth then
          usedLabels += label
          emitPlain(s"break ${Mangling.label(label)}")
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
        emitPlain(s"case ${values.mkString(", ")}:")
        emitIndented(nested(genTree(body, dest))._1)
      emitPlain("default:")
      emitIndented(nested(genTree(default, dest))._1)
      emitPlain("}")

    case TryCatch(block, cases, tpe) =>
      withValueDest(dest, tpe) { bodyDest =>
        val exc = runInTryClosure(block, bodyDest)
        val branches = cases.map { c =>
          val test = if c.tpe == Type.Class("java.lang.Throwable") then "true" else ctx.typeTest(exc, c.tpe)
          val (lines, _) = nested {
            c.binder.foreach { b =>
              val v = Mangling.local(b)
              // The cast cannot fail: the exception's type was tested.
              emitPlain(s"var $v ${ctx.goType(c.tpe)} = ${ctx.cast(exc, Type.Object, c.tpe)}")
              emitPlain(s"_ = $v")
            }
            genTree(c.body, bodyDest)
          }
          (test, lines)
        }
        // The exception's stack trace was captured when it was created: rethrowing needs no position.
        emitPlain(s"if $exc != nil {")
        branches.zipWithIndex.foreach { case ((test, lines), i) =>
          emitPlain(if i == 0 then s"\tif ${unparen(test)} {" else s"\t} else if ${unparen(test)} {")
          lines.foreach(l => out += l.indented.indented)
        }
        emitPlain("\t} else {")
        emitPlain(s"\t\tpanic(rt.Exc($exc))")
        emitPlain("\t}")
        emitPlain("}")
      }

    case TryFinally(block, finalizer, tpe) =>
      withValueDest(dest, tpe) { bodyDest =>
        val (exc, jumps) = runInTryClosure(block, bodyDest, dispatch = false)
        // A finalizer that throws or returns replaces the block's outcome.
        val abrupt = endsAbruptly(finalizer)
        if abrupt then emit(s"_ = $exc")
        genTree(finalizer, Dest.Discard)
        if !abrupt then
          emitPlain(s"if $exc != nil {")
          emitPlain(s"\tpanic(rt.Exc($exc))")
          emitPlain("}")
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
      emitPlain(s"var $temp ${ctx.goType(tpe)}")
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
    emitPlain("})")
    if dispatch then dispatchJumps(jumps)
    (exc, jumps)

  /** After a try closure returns: performs the jumps its body requested via `ctl`. */
  private def dispatchJumps(jumps: Set[Int]): Unit =
    var propagate = false
    for code <- jumps.toList.sorted do
      if code == ReturnCode && depth == 0 then
        emitPlain("if ctl == 1 {")
        emitPlain(if isVoidMethod then "\treturn" else "\treturn ret")
        emitPlain("}")
      else
        labels.collectFirst { case (label, info) if info.code == code => (label, info) } match
          case Some((label, info)) if info.depth == depth =>
            usedLabels += label
            emitPlain(s"if ctl == $code {")
            emitPlain("\tctl = 0")
            emitPlain(s"\tbreak ${Mangling.label(label)}")
            emitPlain("}")
          case _ =>
            propagate = true
            pendingJumps.top += code
    if propagate then
      emitPlain("if ctl != 0 {")
      emitPlain("\treturn")
      emitPlain("}")

  /** Inside a try closure: requests a jump to be performed by the enclosing code. */
  private def jumpOutOfClosure(code: Int): Unit =
    usesCtl = true
    pendingJumps.top += code
    emitPlain(s"ctl = $code")
    emitPlain("return")

  /** Sends the value of an already-evaluated expression to `dest`. `expr` is empty for no value. */
  private def deliver(dest: Dest, expr: String, tpe: Type): Unit = dest match
    case Dest.Discard =>
      if expr.nonEmpty then
        if isVoidLike(tpe) then emit(expr)
        else if !isSimple(expr) then emit(s"_ = $expr")
    case Dest.Return if depth > 0 =>
      if !isVoidMethod && !isVoidLike(tpe) then
        usesRet = true
        emitUsing(adapt(expr, tpe, method.name.result))(e => s"ret = $e")
      else if expr.nonEmpty then deliver(Dest.Discard, expr, tpe)
      jumpOutOfClosure(ReturnCode)
    case Dest.Return =>
      if isVoidMethod then
        if expr.nonEmpty then deliver(Dest.Discard, expr, tpe)
        emitPlain("return")
      else if isVoidLike(tpe) then
        // Only reachable in ill-typed trees; keep the Go code well-formed.
        if expr.nonEmpty then deliver(Dest.Discard, expr, tpe)
        emitPlain("panic(\"unreachable\")")
      else emitUsing(adapt(expr, tpe, method.name.result))(e => s"return $e")
    case Dest.AssignTo(v, target) =>
      // A call of a method returning Nothing has no Go result: it never completes normally.
      if expr.nonEmpty then
        if tpe == Type.Nothing then emit(expr) else emitUsing(adapt(expr, tpe, target))(e => s"$v = $e")

  /** Emits a line that uses the value `expr`: without a position if `expr` is a variable or a
   *  literal, which cannot fail.
   */
  private def emitUsing(expr: String)(line: String => String): Unit =
    if isSimple(expr) then emitPlain(line(expr)) else emit(line(expr))

  private def genAssign(lhs: Tree, rhs: Tree): Unit = lhs match
    case _ if ctx.analyzer.missingUse(lhs).isDefined =>
      genTree(rhs, Dest.Discard)
      genTree(lhs, Dest.Discard)
    case VarRef(name, tpe) =>
      emit(s"${Mangling.local(name)} = ${adapt(genExpr(rhs), rhs.tpe, tpe)}")
    case Select(qual, cls, field, tpe) =>
      val List(q, r) = genExprs(List(qual, rhs), List(qual.tpe, tpe)): @unchecked
      emit(store(s"${fieldAccess(qual, q, cls)}.${Mangling.field(field)}", cls, field, r))
    case SelectStatic(cls, field, tpe) =>
      val r = adapt(genExpr(rhs), rhs.tpe, tpe)
      initStaticFields(cls)
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

  /** The storage of the volatile field `field` refers to, for atomic operations. */
  private def volatileRef(tree: Tree, field: Tree): Option[String] = field match
    case Select(qual, cls, f, _) if ctx.isVolatile(cls, f) =>
      Some(s"${fieldAccess(qual, genExpr(qual), cls)}.${Mangling.field(f)}")
    case SelectStatic(cls, f, _) if ctx.isVolatile(cls, f) =>
      initStaticFields(cls)
      Some(Mangling.staticField(cls, f))
    case _ =>
      error(tree, s"${tree.productPrefix} needs a @volatile field")
      None

  /** `GetAndSet` or `GetAndAdd`: the field reference is evaluated first, then the operand. */
  private def atomicUpdate(tree: Tree, field: Tree, operand: Tree, method: String): String =
    volatileRef(tree, field) match
      case Some(ref) =>
        val refVar = freshTemp()
        emit(s"$refVar := &$ref")
        val List(v) = genExprs(List(operand), List(field.tpe)): @unchecked
        s"$refVar.$method($v)"
      case None => zeroValue(field.tpe)

  /** Accesses the struct of `cls` holding a field, from `qual` (whose Go expression is `q`). */
  private def fieldAccess(qual: Tree, q: String, cls: ClassName): String =
    val qualGoType = ctx.goType(qual.tpe)
    qual match
      case This(_) if qualGoType != "any" =>
        thisStructs += cls
        Mangling.thisStruct(cls)
      case _ if qualGoType != Mangling.iface(cls) && qualGoType == "any" =>
        s"${ctx.ifaceCast(cls, q)}.${Mangling.accessor(cls)}()"
      case _ => s"$q.${Mangling.accessor(cls)}()"

  /** Triggers the static initializer of `cls` (unless we are in `cls` itself). */
  private def initStatics(cls: ClassName): Unit =
    if cls != owner.name && ctx.analyzer.staticInit.contains(cls) then emit(s"${Mangling.clinit(cls)}()")

  /** Before an access to a static field of `cls`: the static fields of an object (Scala 3's enum
   *  cases) are set by its constructor, which the JVM runs as the static initializer, so those
   *  need the object.
   */
  private def initStaticFields(cls: ClassName): Unit =
    initStatics(cls)
    if cls != owner.name && program.isModuleClass(cls) then emit(s"${Mangling.module(cls)}()")

  // ---------------------------------------------------------------------------------------------
  // Expressions
  // ---------------------------------------------------------------------------------------------

  /** Go expression for `tree`, emitting any statements it needs first. Empty for void trees. */
  private def genExpr(tree: Tree): String = atPos(tree.pos) {
    ctx.analyzer.missingUse(tree) match
      case Some(use) =>
        throwMissing(use)
        zeroValue(tree.tpe)
      case None => genExprHere(tree)
  }

  private def genExprHere(tree: Tree): String = tree match
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
    case ClassOf(t)        => ctx.classLiteral(jvmClassName(t))

    case VarRef(name, _) => Mangling.local(name)
    case This(_)         => "this"

    case LoadModule(cls) =>
      if program.contains(cls) then s"${Mangling.module(cls)}()"
      else error(tree, s"module $cls is not available (no IR)")

    case Select(qual, cls, field, _) =>
      load(s"${fieldAccess(qual, genExpr(qual), cls)}.${Mangling.field(field)}", cls, field)

    case sel @ SelectStatic(cls, field, _) =>
      Natives.staticField(cls, field) match
        case Some(native) => native
        case None =>
          program.staticFieldViaCompanion(sel) match
            case Some(read) => genExpr(read)
            case None =>
              initStaticFields(cls)
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

    case GetAndSet(field, value) => atomicUpdate(tree, field, value, "GetAndSet")
    case GetAndAdd(field, delta) => atomicUpdate(tree, field, delta, "GetAndAdd")

    case New(cls, _, _) if program.isFacade(cls) =>
      ctx.interop.zeroValue(cls, tree.pos)

    case New(cls, ctor, args) =>
      Natives.lookup(cls, ctor) match
        case Some(native) => genNative(native, None, args, ctor)
        case None         => s"${Mangling.newInstance(cls, ctor)}(${genExprs(args, ctor.params).mkString(", ")})"

    case tree: Apply           => genApply(tree)
    case tree: ApplyStatically => genApplyStatically(tree)

    case app @ ApplyStatic(cls, m, args, _) =>
      Natives.lookup(cls, m) match
        case Some(native) => genNative(native, None, args, m)
        case None =>
          program.staticMethodViaCompanion(app) match
            case Some(call) => genExpr(call)
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
    emitPlain(s"var $temp ${if goType.isEmpty then "any" else goType}")
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
      emitPlain(if op == "&&" then s"if $temp {" else s"if !$temp {")
      emitIndented(rhsLines)
      emit(s"\t$temp = $r")
      emitPlain("}")
      temp

  /** Evaluates `trees` left to right, spilling earlier results that later statements could affect.
   *  With `expected` types, the results are adapted to them (see [[adapt]]).
   */
  private def genExprs(trees: List[Tree], expected: List[Type] = Nil): List[String] =
    // Spilled values keep their own type; they are adapted where used, like the others.
    val exprs = genInOrder(trees, t => ctx.goType(t.tpe))(genExpr)
    exprs.zipWithIndex.map((e, i) => if i < expected.size then adapt(e, trees(i).tpe, expected(i)) else e)

  /** Go expressions `gen(t)` for `trees`, evaluated in order: when generating one emits
   *  statements, the impure expressions before it are first evaluated into variables (of Go type
   *  `goType(t)`).
   */
  private def genInOrder(trees: List[Tree], goType: Tree => String)(gen: Tree => String): List[String] =
    val exprs = mutable.ArrayBuffer.empty[String]
    val marks = mutable.ArrayBuffer.empty[Int]
    for t <- trees do
      exprs += gen(t)
      marks += out.size
    val end = out.size
    for i <- trees.indices.reverse if marks(i) < end && !isPure(trees(i)) do
      val temp = freshTemp()
      val t = goType(trees(i))
      val p = if trees(i).pos.line > 0 then trees(i).pos else pos
      out.insert(marks(i), GoLine(s"var $temp ${if t.isEmpty then "any" else t} = ${exprs(i)}", p))
      exprs(i) = temp
    exprs.toList

  /** Converts a Go value of IR type `from` for a location of IR type `to`. Scala upcasts need no
   *  conversion in the IR, but a class that is never instantiated has Go type `any`, which Go does
   *  not implicitly convert to the interface type of its (instantiated) supertypes. And a null
   *  reference of a pointer type (`*rt.String`, arrays, ...) must become a nil `any`, not an `any`
   *  holding a nil pointer.
   */
  private def adapt(expr: String, from: Type, to: Type): String =
    val target = ctx.goType(to)
    val source = ctx.goType(from)
    if expr.isEmpty || target.isEmpty || to.isPrimitive then expr
    else if target == "any" then if source.startsWith("*") && expr != "nil" then s"rt.AnyRef($expr)" else expr
    else if source != "any" then expr
    else to match
      case Type.Class(c) if target == Mangling.iface(c) => ctx.ifaceCast(c, expr)
      case _                                            => s"rt.Cast[$target]($expr)"

  private def isPure(tree: Tree): Boolean = tree match
    case _: BooleanLiteral | _: CharLiteral | _: ByteLiteral | _: ShortLiteral | _: IntLiteral | _: LongLiteral |
        _: FloatLiteral | _: DoubleLiteral | _: StringLiteral | _: Null | _: This | _: ClassOf =>
      true
    case VarRef(name, _) => !mutableLocals.contains(name)
    case _               => false

  // ---------------------------------------------------------------------------------------------
  // Calls
  // ---------------------------------------------------------------------------------------------

  private def genNative(native: Natives.Native, receiver: Option[Tree], args: List[Tree], m: MethodName): String =
    native match
      case Natives.Native.Static(gen) => gen(genExprs(args, m.params))
      case Natives.Native.Instance(gen) =>
        val r :: as = genExprs(receiver.toList ++ args, receiver.map(_.tpe).toList ++ m.params): @unchecked
        gen(r, as)
      case Natives.Native.NullResult =>
        val values = genExprs(receiver.toList ++ args, receiver.map(_.tpe).toList ++ m.params)
        receiver.foreach(_ => emit(s"rt.CheckNotNull(${values.head})"))
        values.drop(receiver.size).filterNot(isSimple).foreach(v => emit(s"_ = $v"))
        zeroValue(m.result)

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
    if declaringClass == Natives.GoString && Natives.goStringMakers.contains(m) then return s"any(${genGoString(tree)})"
    Natives.lookup(declaringClass, m) match
      case Some(native) => genNative(native, Some(receiver), args, m)
      case None if isVirtual =>
        val r :: as = genExprs(receiver :: args, receiver.tpe :: m.params): @unchecked
        s"$r.${Mangling.virtualMethod(m)}(${as.mkString(", ")})"
      case None =>
        Natives.objectMethod(m).orElse(hijackedNative) match
          case Some(native) => genNative(native, Some(receiver), args, m)
          case None =>
            val r :: as = genExprs(receiver :: args, receiver.tpe :: m.params): @unchecked
            s"${ctx.dispatcher(m)}(${(r :: as).mkString(", ")})"

  /** A `GoString` made by `GoString(s)`, `GoString.valueOf(x)` or a chain of `concat`s (what
   *  `go"..."` expands to), as a Go `string` expression: one `+` over all the operands, literals
   *  as Go constants, and no intermediate string boxed or built.
   */
  private def genGoString(tree: Apply): String =
    def maker(t: Tree): Option[(MethodName, List[Tree])] = t match
      case Apply(_, Natives.GoString, m, args, _) if Natives.goStringMakers.contains(m) => Some((m, args))
      case _                                                                          => None
    def operands(t: Tree): List[Tree] = maker(t) match
      case Some((Natives.GoStringConcat, List(a, b))) => operands(a) ++ operands(b)
      case _                                          => List(t)
    def constant(t: Tree): Option[String] = maker(t) match
      case Some((Natives.GoStringApply, List(StringLiteral(s)))) if !hasLoneSurrogate(s) => Some(goStringLiteral(s))
      case _                                                                            => None
    def value(t: Tree): String = maker(t) match
      case Some((m, args)) => Natives.goStringMakers(m)(genExprs(args, m.params))
      case None            => Natives.goStr(genExpr(t))
    // Constants need no evaluation; the other operands are evaluated (and converted) in order.
    val ops = operands(tree)
    val values = genInOrder(ops.filter(constant(_).isEmpty), _ => "string")(value).iterator
    ops.map(t => constant(t).getOrElse(values.next())).mkString(" + ")

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
    else if isObjectDefault(cls, m) then
      // `super.toString()` (etc.) reaching java.lang.Object: its own implementation, not the
      // dynamically dispatching native, which would call the overriding method again.
      val r :: as = genExprs(receiver :: args): @unchecked
      s"${ClassEmitter.objectDefaults(m)}(${(r :: as).mkString(", ")})"
    else Natives.lookup(cls, m) match
      case Some(native) => genNative(native, Some(receiver), args, m)
      case None =>
        program.resolveImplementation(cls, m) match
          case Some(implClass) =>
            val r :: as = genExprs(receiver :: args, Type.Class(implClass) :: m.params): @unchecked
            val exact = ctx.exactReceiver(implClass)
            val recv = receiver match
              // `this` already has that type (see LinkContext.exactReceiver).
              case _: This if exact.isDefined && ctx.exactReceiver(owner.name) == exact => r
              case _ => exact.fold(r)(ctx.toExactReceiver(_, r))
            s"${Mangling.impl(implClass, m)}(${(recv :: as).mkString(", ")})"
          case None =>
            error(tree, s"method $cls.$m is not available")

  private def isObjectDefault(cls: ClassName, m: MethodName): Boolean =
    ClassEmitter.objectDefaults.contains(m) && !Hijacked.isHijacked(cls) &&
      program.resolveImplementation(cls, m).forall(_ == ClassName.Object)

  // ---------------------------------------------------------------------------------------------
  // Primitive operations
  // ---------------------------------------------------------------------------------------------

  private def genBinary(tree: Binary): String =
    if tree.op == BinaryOp.StringConcat then genStringConcat(tree) else genArithmetic(tree)

  /** A chain of string concatenations, as one: like the JVM's (`StringConcatFactory`), it evaluates
   *  all the operands before converting any to a string, which matters when evaluating an operand
   *  changes an earlier one (`s"$list ${list.remove(0)}"`).
   */
  private def genStringConcat(tree: Binary): String =
    def operands(t: Tree): List[Tree] = t match
      case Binary(BinaryOp.StringConcat, _, l, r) => operands(l) ++ operands(r)
      case other                                  => List(other)
    genExprs(operands(tree)) match
      case List(a, b) => s"rt.Concat($a, $b)"
      case parts      => s"rt.ConcatAll(${parts.mkString(", ")})"

  private def genArithmetic(tree: Binary): String =
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
    // Go rejects a constant integer division by zero, which the JVM throws at run time.
    def zeroDivisor = rhs match
      case IntLiteral(0) | LongLiteral(0L) => opType == Type.Int || opType == Type.Long
      case _                               => false
    op match
      case Div | Rem if zeroDivisor => s"rt.DivByZero($a)"
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
      case RefEq  => refEq(lhs, a, rhs, b)
      case RefNe  => s"(!${refEq(lhs, a, rhs, b)})"
      case UnivEq => s"rt.Equals($a, $b)"
      case UnivNe => s"(!rt.Equals($a, $b))"
      case StringConcat => s"rt.Concat($a, $b)"

  /** Reference equality. Null tests compare with Go's nil directly, except on `any` values, which
   *  may hold a nil pointer (see `rt.IsNull`).
   */
  private def refEq(lhs: Tree, a: String, rhs: Tree, b: String): String =
    def nullTest(t: Tree, e: String) = if ctx.goType(t.tpe) == "any" then s"rt.IsNull($e)" else s"($e == nil)"
    (lhs, rhs) match
      case (_: Null, _: Null) => "true"
      case (_, _: Null)       => nullTest(lhs, a)
      case (_: Null, _)       => nullTest(rhs, b)
      case _                  => s"(any($a) == any($b))"

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
    // A jump to the block's own label completes it normally.
    case Labeled(label, _, body) => endsAbruptly(body) && !jumpsTo(label, body)
    case _                      => false

  /** Whether `tree` may jump to `label` (a jump whose value throws or returns does not). */
  private def jumpsTo(label: String, tree: Tree): Boolean = tree match
    case Return(Some(`label`), expr) => !endsAbruptly(expr) || jumpsTo(label, expr)
    case _                           => Traversal.children(tree).exists(jumpsTo(label, _))

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
