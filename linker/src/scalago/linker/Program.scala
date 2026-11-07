package scalago.linker

import scala.collection.mutable

import scalago.ir.*

/** All classes with IR, plus the class-hierarchy queries the analyzer and emitter need.
 *
 *  When a class appears several times on the classpath the first occurrence wins, as on the JVM,
 *  so overrides (javalib, scalalib replacements) must come before the libraries they replace.
 */
final class Program(irClasses: Seq[ClassDef]):
  private val byName = mutable.LinkedHashMap.empty[ClassName, ClassDef]
  /** Go facades are kept apart: they are not part of the generated object model. */
  private val facadesByName = mutable.LinkedHashMap.empty[ClassName, ClassDef]
  for c <- irClasses if !byName.contains(c.name) && !facadesByName.contains(c.name) do
    if c.goFacade.isDefined then facadesByName(c.name) = c else byName(c.name) = c

  def classes: Iterable[ClassDef] = byName.values
  def get(name: ClassName): Option[ClassDef] = byName.get(name)
  def contains(name: ClassName): Boolean = byName.contains(name)
  def isModuleClass(name: ClassName): Boolean = get(name).exists(_.kind == ClassKind.ModuleClass)

  def facade(name: ClassName): Option[ClassDef] = facadesByName.get(name)
  def isFacade(name: ClassName): Boolean = facadesByName.contains(name)

  /** The facade member `m` of facade `cls` or of the facades it extends. */
  def facadeMember(cls: ClassName, m: MethodName): Option[(ClassDef, MethodDef)] =
    facade(cls).flatMap { f =>
      f.methods.find(d => d.name == m && d.goMember.isDefined).map(f -> _)
        .orElse((f.superClass.toList ++ f.interfaces).iterator.map(facadeMember(_, m)).collectFirst { case Some(r) => r })
    }

  /** The facades among the ancestors of `cls` (Go interfaces that a Scala class implements). */
  def facadeAncestors(cls: ClassName): List[ClassName] =
    def all(c: ClassName): List[ClassName] =
      val parents = get(c).orElse(facade(c)).toList.flatMap(d => d.superClass.toList ++ d.interfaces)
      parents ++ parents.flatMap(all)
    all(cls).distinct.filter(isFacade)

  /** Adds (or replaces) a class created by the linker (lambda classes). */
  def addSynthetic(c: ClassDef): Unit =
    byName(c.name) = c
    vtables.clear()
    interfacesCache.clear()

  def isInterface(name: ClassName): Boolean = get(name).exists(_.kind == ClassKind.Interface)

  def isInstantiable(c: ClassDef): Boolean = c.kind != ClassKind.Interface && !c.isAbstract

  /** Methods that take part in virtual dispatch. */
  def isVirtual(m: MethodDef): Boolean = !m.isStatic && !m.isPrivate && !m.name.isConstructor

  /** `cls` and its superclasses that have IR, nearest first. Stops at the first class without IR. */
  def superClassChain(cls: ClassName): List[ClassDef] =
    get(cls) match
      case Some(c) if c.kind != ClassKind.Interface => c :: c.superClass.toList.flatMap(superClassChain)
      case _                                        => Nil

  /** Every class and interface `cls` extends or implements (transitively, IR or not), excluding itself. */
  def ancestors(cls: ClassName): Set[ClassName] =
    get(cls) match
      case Some(c) =>
        val parents = c.superClass.toList ++ c.interfaces
        parents.toSet ++ parents.flatMap(ancestors)
      case None => Set.empty

  def isSubclass(sub: ClassName, sup: ClassName): Boolean =
    sub == sup || sup == ClassName.Object || ancestors(sub).contains(sup)

  private val interfacesCache = mutable.HashMap.empty[ClassName, List[ClassName]]

  /** The interfaces with IR that `cls` implements (or, for an interface, extends), transitively,
   *  in a deterministic order. Includes `cls` itself if it is an interface.
   */
  def allInterfaces(cls: ClassName): List[ClassName] =
    interfacesCache.getOrElseUpdate(
      cls, {
        val seen = mutable.LinkedHashSet.empty[ClassName]
        def visit(name: ClassName): Unit = get(name).foreach { c =>
          if c.kind == ClassKind.Interface then seen += name
          (c.superClass.toList ++ c.interfaces).foreach(visit)
        }
        visit(cls)
        seen.toList.filter(contains)
      }
    )

  private final case class Slot(owner: ClassName, fromClass: Boolean, concrete: Boolean)
  private val vtables = mutable.HashMap.empty[ClassName, Map[MethodName, ClassName]]

  /** Every virtual method of `cls` (inherited ones included), mapped to the class or interface
   *  whose implementation is used, or the declaring one if the method is abstract. Methods from
   *  the class hierarchy win over interface default methods; among defaults the most specific
   *  interface wins, as in JVM method resolution.
   */
  def vtable(cls: ClassName): Map[MethodName, ClassName] =
    vtables.getOrElseUpdate(
      cls, {
        val table = mutable.LinkedHashMap.empty[MethodName, Slot]
        for c <- superClassChain(cls).reverse; m <- c.methods if isVirtual(m) do
          if !m.isAbstract || !table.contains(m.name) then table(m.name) = Slot(c.name, fromClass = true, !m.isAbstract)
        for i <- allInterfaces(cls); iface <- get(i); m <- iface.methods if isVirtual(m) do
          val candidate = Slot(i, fromClass = false, !m.isAbstract)
          table.get(m.name) match
            case None => table(m.name) = candidate
            case Some(Slot(_, true, _)) => // class methods (even abstract ones) take precedence
            case Some(Slot(other, false, otherConcrete)) =>
              val moreSpecific = isSubclass(i, other) && i != other
              if (candidate.concrete && !otherConcrete) || (candidate.concrete == otherConcrete && moreSpecific) then
                table(m.name) = candidate
        table.view.mapValues(_.owner).toMap
      }
    )

  /** The class or interface providing the implementation of `m` for a non-virtual call on `cls`
   *  (super calls, private methods, constructors, trait `super[T]` calls).
   */
  def resolveImplementation(cls: ClassName, m: MethodName): Option[ClassName] =
    def concreteIn(c: ClassName) = get(c).exists(_.methods.exists(d => d.name == m && !d.isAbstract))
    superClassChain(cls).map(_.name).find(concreteIn).orElse {
      if isInterface(cls) && concreteIn(cls) then Some(cls)
      else vtable(cls).get(m).filter(concreteIn)
    }

  def findMethod(cls: ClassName, m: MethodName): Option[MethodDef] =
    get(cls).flatMap(_.methods.find(_.name == m))

  /** The interface whose default method implements `m` for instances of the hijacked class `h`
   *  when no native does (`CharSequence.chars()` for strings), as for a JVM class that inherits
   *  the default. The class has no IR, so its interfaces with IR are searched.
   */
  def hijackedDefault(h: ClassName, m: MethodName): Option[ClassName] =
    if Natives.lookup(h, m).isDefined then None
    else
      Hijacked.ancestors.getOrElse(h, Set.empty).toList.sorted.iterator
        .flatMap(i => resolveImplementation(i, m).filter(isInterface))
        .find(i => findMethod(i, m).exists(d => !d.isAbstract && !d.isStatic))

  /** A read of static field `cls.field` (of type `tpe`) that `cls` does not have, as a call of the
   *  getter of the same name on its companion object, if that object has one. javalib's classes
   *  are Scala, where Java's static constants are usually members of the companion object
   *  (`Collections.EMPTY_LIST`), while code compiled against the JDK reads static fields.
   */
  def staticFieldViaCompanion(sel: Tree.SelectStatic): Option[Tree] =
    val Tree.SelectStatic(cls, field, tpe) = sel
    val module = cls + "$"
    val getter = MethodName(field, Nil, tpe)
    val hasStatic = get(cls).exists(_.fields.exists(f => f.isStatic && f.name == field))
    Option.when(!hasStatic && get(module).exists(_.kind == ClassKind.ModuleClass) && findMethod(module, getter).exists(!_.isStatic)) {
      Tree.Apply(Tree.LoadModule(module)(sel.pos), module, getter, Nil, tpe)(sel.pos)
    }

  /** The static initializer of `cls`, if it has one. */
  def clinit(cls: ClassName): Option[MethodDef] =
    get(cls).flatMap(_.methods.find(m => m.isStatic && m.name.simple == "<clinit>"))
