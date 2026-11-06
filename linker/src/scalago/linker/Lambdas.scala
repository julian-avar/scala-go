package scalago.linker

import scala.collection.mutable

import scalago.ir.*
import scalago.ir.Tree.*

/** Rewrites `Closure` nodes into instantiations of synthetic classes, like the JVM's
 *  LambdaMetafactory: each lambda class stores the captured values in fields and implements the
 *  functional interface's single abstract method by calling the target method, adapting boxing
 *  and casts where the signatures differ. The rest of the linker then sees ordinary classes.
 */
final class Lambdas(program: Program):
  private val counters = mutable.HashMap.empty[ClassName, Int].withDefaultValue(0)

  def lower(owner: ClassName, body: Tree): Tree =
    Traversal.transform(body) { case c: Closure => instantiate(owner, c) }

  private def instantiate(owner: ClassName, closure: Closure): Tree =
    val pos = closure.pos
    counters(owner) += 1
    val name = s"$owner$$$$Lambda$$${counters(owner)}"
    val captureTypes = closure.captures.map(_.tpe)
    val ctor = MethodName(MethodName.ConstructorSimpleName, captureTypes, Type.Void)
    val cls = lambdaClass(name, closure, captureTypes, ctor)
    program.addSynthetic(cls)
    program.addSynthetic(cls.copy(methods = cls.methods ++ bridges(cls)))
    New(name, ctor, closure.captures)(pos)

  /** Bridges for abstract methods left in the lambda class's vtable, to the concrete method with
   *  the same name and arity (as LambdaMetafactory does): e.g. `Function1.apply(Object): Object`
   *  to `JProcedure1.apply(Object): BoxedUnit`, since Scala traits carry no bridge methods.
   */
  private def bridges(cls: ClassDef): List[MethodDef] =
    val pos = cls.pos
    val vtable = program.vtable(cls.name)
    def isConcrete(owner: ClassName, m: MethodName) = program.findMethod(owner, m).exists(!_.isAbstract)
    for
      (m, owner) <- vtable.toList.sortBy(_._1.descriptor)
      if !isConcrete(owner, m)
      (target, targetOwner) <- vtable.collectFirst {
        case (t, o) if t != m && t.simple == m.simple && t.params.size == m.params.size && isConcrete(o, t) => (t, o)
      }
    yield
      val params = m.params.zipWithIndex.map((t, i) => ParamDef(s"arg$i", t))
      val args = params.zip(target.params).map((p, t) => adapt(VarRef(p.name, p.tpe)(pos), t, pos))
      val call = ApplyStatically(This(Type.Class(cls.name))(pos), targetOwner, target, args, target.result)(pos)
      MethodDef(m, params, Some(adapt(call, m.result, pos)), isStatic = false, isPrivate = false, pos)

  private def lambdaClass(name: ClassName, closure: Closure, captureTypes: List[Type], ctor: MethodName): ClassDef =
    val pos = closure.pos
    val self = Type.Class(name)
    val fields = captureTypes.zipWithIndex.map((t, i) => FieldDef(s"capture$i", t, isStatic = false, pos))
    def captured(i: Int): Tree = Select(This(self)(pos), name, s"capture$i", captureTypes(i))(pos)

    val ctorParams = captureTypes.zipWithIndex.map((t, i) => ParamDef(s"capture$i", t))
    val ctorBody = Block(
      ctorParams.zipWithIndex.map((p, i) => Assign(captured(i), VarRef(p.name, p.tpe)(pos))(pos)),
      Skip()(pos)
    )(pos)

    val sam = closure.samMethod
    val samParams = sam.params.zipWithIndex.map((t, i) => ParamDef(s"arg$i", t))
    val target = closure.targetMethod
    val (receiver, captureArgs) =
      if closure.targetIsStatic then (None, captureTypes.indices.map(captured).toList)
      else (Some(captured(0)), captureTypes.indices.drop(1).map(captured).toList)
    val targetArgTypes = target.params.drop(captureArgs.size)
    val adaptedArgs = samParams.zip(targetArgTypes).map((p, t) => adapt(VarRef(p.name, p.tpe)(pos), t, pos))
    val args = captureArgs ++ adaptedArgs
    val call = receiver match
      case Some(r) => ApplyStatically(r, closure.targetClass, target, args, target.result)(pos)
      case None    => ApplyStatic(closure.targetClass, target, args, target.result)(pos)
    val samBody = adapt(call, sam.result, pos)

    ClassDef(
      name,
      ClassKind.Class,
      isAbstract = false,
      superClass = Some(ClassName.Object),
      interfaces = List(closure.samInterface),
      fields = fields,
      methods = List(
        MethodDef(ctor, ctorParams, Some(ctorBody), isStatic = false, isPrivate = false, pos),
        MethodDef(sam, samParams, Some(samBody), isStatic = false, isPrivate = false, pos)
      ),
      pos = pos
    )

  /** Converts `tree` to type `to`: boxing, unboxing, casts and numeric conversions. */
  private def adapt(tree: Tree, to: Type, pos: Position): Tree =
    val from = tree.tpe
    if from == to then tree
    else if to == Type.Void then Block(List(tree), Skip()(pos))(pos)
    else if from == Type.Void then
      Block(List(tree), SelectStatic(Hijacked.BoxedUnit, "UNIT", Type.Class(Hijacked.BoxedUnit))(pos))(pos)
    else if to.isPrimitive && from.isPrimitive then Convert(tree, to)(pos)
    else if to.isPrimitive then Unbox(tree, to)(pos)
    else if from.isPrimitive then Box(tree)(pos)
    else if to == Type.Object then tree
    else AsInstanceOf(tree, to)(pos)
