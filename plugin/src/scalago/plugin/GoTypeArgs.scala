package scalago.plugin

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.plugins.PluginPhase
import dotty.tools.dotc.report
import dotty.tools.dotc.util.Property

/** Records the type arguments of uses of generic Go facades, which erasure drops: calls of generic
 *  members (`slices.Max[GoInt](xs)`), calls of members of generic facade classes (the receiver's
 *  type arguments: `p.Load()` on an `atomic.Pointer[GoString]`), and `new` of generic facade
 *  classes. They are attached to the `Apply` nodes (sticky attachments survive tree copies), where
 *  [[GoIRCodeGen]] finds them after erasure.
 */
class GoTypeArgs extends PluginPhase:
  override def phaseName: String = GoTypeArgs.name
  override def description: String = "record the type arguments of generic Go facades for scala-go"
  // Early (inlined code is expanded by then), in a group of its own: placed among the transforms
  // before erasure, it changes how they are grouped, and erasure then leaves some calls unapplied.
  override val runsAfter: Set[String] = Set("pickleQuotes")
  override val runsBefore: Set[String] = Set("firstTransform")

  override def transformApply(tree: Apply)(using Context): Tree =
    GoTypeArgs.typeArgsOf(tree.fun, tree.fun.symbol).foreach { args =>
      tree.putAttachment(GoTypeArgs.Key, args)
      GoTypeArgs.checkInferred(tree.fun, tree.fun.symbol)
    }
    tree

  /** A member without a parameter list (`p.Value` on a `sql.Null[T]`): erasure makes it a call. */
  override def transformSelect(tree: Select)(using Context): Tree =
    // Only facade members' infos are looked at: forcing others' here would change later phases.
    val sym = tree.symbol
    if sym.exists && sym.is(Method) && GoTypeArgs.isFacade(sym.owner) && sym.info.isInstanceOf[ExprType] then
      GoTypeArgs.typeArgsOf(tree, sym).foreach(args => tree.putAttachment(GoTypeArgs.Key, args))
    tree

object GoTypeArgs:
  val name = "goTypeArgs"

  /** The type arguments of a use of a generic facade, by type parameter name. */
  val Key: Property.StickyKey[List[(String, Type)]] = Property.StickyKey()

  /** Whether `cls` is a Go facade: `@GoImport` on it (or its module), or a class of a `@GoImport` object. */
  def isFacade(cls: Symbol)(using Context): Boolean =
    val annot = getClassIfDefined("scalago.go.GoImport")
    def annotated(c: Symbol) = c.hasAnnotation(annot) || (c.is(ModuleClass) && c.sourceModule.hasAnnotation(annot))
    annot.exists && cls.exists && cls.isClass &&
      (annotated(cls) || (cls.owner.isClass && cls.owner.is(ModuleClass) && annotated(cls.owner)))

  /** Whether `sym`, a facade member or constructor, has type parameters (its own, or its class's). */
  def isGeneric(sym: Symbol)(using Context): Boolean =
    sym.info.isInstanceOf[PolyType] || sym.owner.typeParams.nonEmpty

  /** A type argument that Scala inferred for a type parameter that no value parameter uses
   *  (`reflect.TypeFor[T]()`) says nothing (Scala picks `Any` or `Nothing`): it must be given.
   */
  def checkInferred(fun: Tree, sym: Symbol)(using Context): Unit = (fun, sym.info) match
    case (TypeApply(_, targs), pt: PolyType) =>
      val params = pt.resultType match
        case mt: MethodType => mt.paramInfos
        case _              => Nil
      for (targ, i) <- targs.zipWithIndex if targ.isInstanceOf[InferredTypeTree] do
        val used = params.exists(_.existsPart {
          case tp: TypeParamRef => (tp.binder eq pt) && tp.paramNum == i
          case _                => false
        })
        if !used then
          report.error(s"scala-go: give the type argument of ${pt.paramNames(i)} of this generic Go facade explicitly " +
            s"(no parameter determines it; Scala inferred ${targ.tpe.show})", fun.srcPos)
    case _ => ()

  /** The type arguments of `fun` (applied, or selected), a use of `sym`. */
  def typeArgsOf(fun: Tree, sym: Symbol)(using Context): Option[List[(String, Type)]] =
    val owner = if sym.exists then sym.owner else NoSymbol
    if !sym.exists || !sym.is(Method) || !isFacade(owner) then None
    else if sym.isConstructor then
      fun match
        case TypeApply(Select(New(_), _), targs) if owner.typeParams.nonEmpty =>
          Some(owner.typeParams.map(_.name.toString).zip(targs.map(_.tpe)))
        case Select(New(tpt), _) if owner.typeParams.nonEmpty =>
          Some(owner.typeParams.map(_.name.toString).zip(tpt.tpe.argInfos))
        case _ => None
    else
      val (selection, methodArgs) = fun match
        case TypeApply(f, targs) =>
          val names = sym.info match
            case pt: PolyType => pt.paramNames.map(_.toString)
            case _            => Nil
          (f, names.zip(targs.map(_.tpe)))
        case f => (f, Nil)
      val classArgs =
        if owner.typeParams.isEmpty then Nil
        else
          selection match
            case Select(qual, _) => owner.typeParams.map(_.name.toString).zip(qual.tpe.widen.baseType(owner).argInfos)
            case _               => Nil
      Option.when(methodArgs.nonEmpty || classArgs.nonEmpty)(methodArgs ++ classArgs)
