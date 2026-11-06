package scalago.linker

import scala.collection.mutable

import scalago.ir.*
import scalago.ir.Tree.*

/** Whole-program reachability analysis: finds which classes are instantiated and which method
 *  implementations can run, starting from the entry point. Only those are emitted, which is what
 *  makes linking against the whole Scala library practical.
 *
 *  Virtual calls are resolved by name: once some code calls method `m` on any receiver, the
 *  implementation of `m` in every instantiated class is reachable (an over-approximation, as in
 *  Scala.js's analyzer).
 */
final class Analyzer(program: Program, lambdas: Lambdas, errors: Errors):
  val instantiated = mutable.LinkedHashSet.empty[ClassName]
  val calledNames = mutable.LinkedHashSet.empty[MethodName]
  val reachedMethods = mutable.LinkedHashSet.empty[(ClassName, MethodName)]
  val staticInit = mutable.LinkedHashSet.empty[ClassName]
  val usedStaticFields = mutable.LinkedHashSet.empty[(ClassName, String)]
  private val bodies = mutable.HashMap.empty[(ClassName, MethodName), Tree]
  private val queue = mutable.Queue.empty[(ClassName, MethodName)]

  /** For diagnostics: the method whose code first made a method or class reachable. */
  private val reachedFrom = mutable.HashMap.empty[(ClassName, MethodName), (ClassName, MethodName)]
  private val instantiatedFrom = mutable.HashMap.empty[ClassName, (ClassName, MethodName)]
  private var current: Option[(ClassName, MethodName)] = None

  /** The body of a reached method, with closures lowered. */
  def body(cls: ClassName, m: MethodName): Tree = bodies((cls, m))

  def instantiate(cls: ClassName): Unit =
    if instantiated.add(cls) then
      current.foreach(instantiatedFrom(cls) = _)
      for (m, impl) <- program.vtable(cls) if calledNames.contains(m) do reach(impl, m)

  def callVirtual(m: MethodName): Unit =
    if calledNames.add(m) then
      for c <- instantiated.toList; impl <- program.vtable(c).get(m) do reach(impl, m)

  def reach(cls: ClassName, m: MethodName): Unit =
    program.findMethod(cls, m).filterNot(_.isAbstract).foreach { _ =>
      if reachedMethods.add((cls, m)) then
        current.foreach(reachedFrom((cls, m)) = _)
        queue.enqueue((cls, m))
    }

  def initializeStatics(cls: ClassName): Unit =
    program.clinit(cls).foreach { init =>
      if staticInit.add(cls) then reach(cls, init.name)
    }

  def run(): Unit =
    while queue.nonEmpty do
      val (cls, m) = queue.dequeue()
      val method = program.findMethod(cls, m).get
      val lowered = lambdas.lower(cls, method.body.get)
      bodies((cls, m)) = lowered
      current = Some((cls, m))
      scan(lowered)
    current = None

  private def error(tree: Tree, msg: String): Unit =
    errors.add(tree.pos, s"$msg\n${reachabilityChain(current)}")

  /** "called from" lines explaining why a method is reachable, innermost first. */
  private def reachabilityChain(start: Option[(ClassName, MethodName)]): String =
    val lines = mutable.ListBuffer.empty[String]
    val seen = mutable.HashSet.empty[(ClassName, MethodName)]
    var at = start
    while at.isDefined && lines.size < 12 && seen.add(at.get) do
      val (cls, m) = at.get
      lines += s"    in $cls.${m.simple}${m.params.map(_.toString).mkString("(", ", ", ")")}"
      // A virtual method is reached because its class was instantiated (and the name called).
      at = reachedFrom.get((cls, m)).orElse(instantiatedFrom.get(cls))
    lines.mkString("\n")

  private def scan(tree: Tree): Unit =
    def scanAll(trees: List[Tree]): Unit = trees.foreach(scan)
    tree match
      case New(cls, ctor, args) =>
        if Natives.lookup(cls, ctor).isEmpty then
          program.get(cls) match
            case Some(c) if program.isInstantiable(c) && program.findMethod(cls, ctor).isDefined =>
              instantiate(cls)
              reach(cls, ctor)
              initializeStatics(cls)
            case Some(_) => error(tree, s"cannot instantiate $cls with constructor $ctor")
            case None    => error(tree, s"class $cls is not available (no IR)")
        scanAll(args)

      case LoadModule(cls) =>
        loadModule(tree, cls)

      case Apply(receiver, decl, m, args, _) =>
        Natives.lookup(decl, m) match
          case Some(Natives.Native.Static(_)) => () // module method: the receiver is not evaluated
          case Some(_)                        => scan(receiver)
          case None =>
            scan(receiver)
            callVirtual(m)
            if Natives.objectMethod(m).isEmpty then
              receiver.tpe match
                case Type.Class(c) if program.contains(c) && !program.vtable(c).contains(m) && !Hijacked.hijackedAncestors(c) =>
                  error(tree, s"method $m not found in $c")
                case Type.Class(c) if !program.contains(c) && !Hijacked.hijackedAncestors(c) && !Hijacked.isHijacked(c) =>
                  error(tree, s"method $c.$m is not available (no IR and no native implementation)")
                case _ => ()
        scanAll(args)

      case ApplyStatically(receiver, cls, m, args, _) =>
        if Natives.lookup(cls, m).isEmpty && !Natives.isNoOpConstructor(cls, m) then
          program.resolveImplementation(cls, m) match
            case Some(impl) => reach(impl, m)
            case None       => error(tree, s"method $cls.$m is not available")
        scan(receiver)
        scanAll(args)

      case ApplyStatic(cls, m, args, _) =>
        if Natives.lookup(cls, m).isEmpty then
          if program.findMethod(cls, m).exists(d => d.isStatic && !d.isAbstract) then
            reach(cls, m)
            initializeStatics(cls)
          else error(tree, s"static method $cls.$m is not available")
        scanAll(args)

      case SelectStatic(cls, field, _) =>
        if Natives.staticField(cls, field).isEmpty then
          if program.get(cls).exists(_.fields.exists(f => f.isStatic && f.name == field)) then
            usedStaticFields += ((cls, field))
            initializeStatics(cls)
          else error(tree, s"static field $cls.$field is not available")

      case Unsupported(msg, _) =>
        error(tree, msg)

      case _ =>
        Traversal.children(tree).foreach(scan)

  private def loadModule(tree: Tree, cls: ClassName): Unit =
    val ctor = MethodName(MethodName.ConstructorSimpleName, Nil, Type.Void)
    if program.findMethod(cls, ctor).isDefined then
      instantiate(cls)
      reach(cls, ctor)
      initializeStatics(cls)
    else error(tree, s"module $cls is not available (no IR)")
end Analyzer

object Analyzer:
  /** java.lang.Object methods the runtime calls dynamically (`rt.ToString`, `rt.Equals`, ...). */
  val runtimeCalledMethods: List[MethodName] = List(
    Natives.parseMethod("toString()Ljava.lang.String;"),
    Natives.parseMethod("equals(Ljava.lang.Object;)Z"),
    Natives.parseMethod("hashCode()I")
  )
