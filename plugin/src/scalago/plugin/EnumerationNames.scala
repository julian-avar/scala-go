package scalago.plugin

import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.NameOps.*
import dotty.tools.dotc.core.Names.typeName
import dotty.tools.dotc.core.Phases.erasurePhase
import dotty.tools.dotc.core.Symbols.*

import scalago.ir

/** Names of `scala.Enumeration` values without reflection.
 *
 *  On the JVM, a value created without a name (`val Mon, Tue = Value`) gets the name of the
 *  `val` holding it: `Enumeration` looks for public, parameterless methods returning a `Value`
 *  that are the getters of fields (`populateNameMap`). scala-go's `Enumeration` (javalib) has no
 *  reflection; instead, each assignment of such a field in a subclass of `Enumeration` is followed
 *  by `this.scalago$registerName(this.field, "<getter name>")`. As on the JVM, this covers every
 *  value held by a public `val` (also those created with `new Val` or a subclass of it), and the
 *  value's own name, if it has one, still takes precedence.
 *
 *  This only changes the IR: the JVM bytecode is untouched. It also applies when libraries (such
 *  as the Scala library's `BigDecimal.RoundingMode`) are compiled from TASTy.
 */
final class EnumerationNames(using Context):
  private val EnumerationClass: Symbol = getClassIfDefined("scala.Enumeration")
  // Looked up before Flatten, which makes nested classes members of their package.
  private val ValueClass: Symbol =
    if !EnumerationClass.exists then NoSymbol
    else atPhase(erasurePhase)(EnumerationClass.info.member(typeName("Value")).symbol)

  /** The IR method of javalib's `Enumeration` that records a name. */
  val registerName: ir.MethodName =
    ir.MethodName("scalago$registerName", List(ir.Type.Class("scala.Enumeration$Value"), ir.Type.Class("java.lang.String")),
      ir.Type.Void)

  val enumerationClassName: ir.ClassName = "scala.Enumeration"

  def isEnumeration(cls: ClassSymbol): Boolean =
    EnumerationClass.exists && cls != EnumerationClass && cls.derivesFrom(EnumerationClass)

  /** For a field of `cls` (a subclass of Enumeration), the name the JVM's reflection gives the
   *  value it holds: that of its public getter, if the field is a `val` of a `Value` type.
   */
  def valName(cls: ClassSymbol, field: Symbol): Option[String] =
    if !field.exists || field.owner != cls || field.is(Method) || field.is(Mutable) then None
    else if !field.info.classSymbol.derivesFrom(ValueClass) then None
    else
      cls.info.decls.lookupAll(field.name.asTermName.getterName)
        .find(getter => getter.is(Method) && !getter.is(Private) && getter.info.firstParamTypes.isEmpty &&
          getter.info.finalResultType.classSymbol == field.info.classSymbol)
        .map(_.name.mangledString)
