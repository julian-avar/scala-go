package scalago.linker

import java.nio.file.Path

import scala.collection.mutable

import scalago.ir.*

/** The Go facades on a classpath (dependencies'), read from their IR: the Scala types that stand
 *  for Go types, and the members that package facades declare. [[FacadeGen]] uses them instead
 *  of generating facades of its own, as ScalablyTyped reuses converted libraries, so that values
 *  flow between libraries. Facade classes and traits are found by their `GoFacade`, type aliases
 *  (`type Cmd = () => Msg`) by the package facade that records them. When several facades stand
 *  for the same Go type, the first one on the classpath is used.
 */
final class FacadeIndex(classes: Seq[ClassDef]):
  import FacadeIndex.*

  private val facades: Map[ClassName, ClassDef] =
    classes.filter(_.goFacade.isDefined).distinctBy(_.name).map(c => c.name -> c).toMap

  private val packageFacades: Map[String, Seq[ClassDef]] =
    classes.filter(_.goFacade.exists(_.isPackage)).distinctBy(_.name).groupBy(_.goFacade.get.pkg)

  private val types: Map[(String, String), ProvidedType] =
    val found = mutable.LinkedHashMap.empty[(String, String), ProvidedType]
    for c <- classes; f <- c.goFacade if f.pkg.nonEmpty do
      if f.isPackage then
        for (goName, scalaName) <- f.typeAliases do
          found.getOrElseUpdate((f.pkg, goName), ProvidedType(s"${GoInterop.scalaName(c.name)}.$scalaName", None))
      else found.getOrElseUpdate((f.pkg, f.name), ProvidedType(GoInterop.scalaName(c.name), Some(c)))
    found.toMap

  /** The facade standing for Go type `pkg.name`, if a dependency has one. */
  def typeOf(pkg: String, name: String): Option[ProvidedType] = types.get((pkg, name))

  /** The Go interface types with a facade trait. */
  def interfaces: Seq[((String, String), ClassName)] =
    types.toSeq.collect { case (key, ProvidedType(_, Some(c))) if c.kind == ClassKind.Interface => key -> c.name }

  /** The Scala names of the facade objects of Go package `pkg`. */
  def packageObjects(pkg: String): Seq[String] = packageFacades.getOrElse(pkg, Nil).map(c => GoInterop.scalaName(c.name))

  /** The Go names of the package-level members (functions, variables, constants, conversions)
   *  that the facade objects of `pkg` declare.
   */
  def members(pkg: String): Set[String] =
    packageFacades.getOrElse(pkg, Nil).flatMap(_.methods.flatMap(_.goMember).map(_.goName)).toSet

  /** Whether a facade class (or object) of that binary name exists. */
  def hasFacade(cls: ClassName): Boolean = facades.contains(cls)

  /** The facade members of facade `cls` and of the facades it extends, by Go name (the nearest
   *  declaration wins).
   */
  def methods(cls: ClassName): Map[String, GoMember] =
    val found = mutable.LinkedHashMap.empty[String, GoMember]
    def visit(c: ClassName): Unit = facades.get(c).foreach { d =>
      d.methods.flatMap(_.goMember).foreach(g => found.getOrElseUpdate(g.goName, g))
      (d.superClass.toList ++ d.interfaces).foreach(visit)
    }
    visit(cls)
    found.toMap

object FacadeIndex:
  /** A dependency's facade of a Go type: a class or trait (`facade`), or a type alias.
   *
   *  @param scalaName the Scala name, as written in source (`scalago.gostd.io.Reader`)
   */
  final case class ProvidedType(scalaName: String, facade: Option[ClassDef])

  val empty: FacadeIndex = FacadeIndex(Nil)

  /** The facades in the IR of the classpath's directories and jars. */
  def load(classpath: Seq[Path]): FacadeIndex = FacadeIndex(IRLoader.load(classpath).filter(_.goFacade.isDefined))
