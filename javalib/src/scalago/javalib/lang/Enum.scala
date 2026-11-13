package scalago.javalib.lang

import scala.annotation.static

/** java.lang.Enum, for the JDK's enums written in javalib and Scala 3 enums that extend it (other
 *  Scala `enum`s use scala.reflect.Enum).
 */
abstract class Enum[E <: Enum[E]] protected (_name: String, _ordinal: Int)
    extends Object with Comparable[E] with java.io.Serializable:
  final def name(): String = _name
  final def ordinal(): Int = _ordinal
  override def toString(): String = _name
  final def compareTo(o: E): Int = _ordinal - o.ordinal()

  /** The enum class: the constant's class, or its superclass for a constant with a body. */
  final def getDeclaringClass(): Class[E] =
    val c = getClass()
    val s = c.getSuperclass()
    (if s eq classOf[Enum[?]] then c else s).asInstanceOf[Class[E]]

object Enum:
  @static def valueOf[T <: Enum[T]](enumClass: Class[T], name: String): T =
    if name == null then throw new java.lang.NullPointerException("Name is null")
    val constants = enumClass.getEnumConstants()
    if constants == null then throw new java.lang.IllegalArgumentException(enumClass.getName() + " is not an enum class")
    constants.find(_.name() == name).getOrElse(
      throw new java.lang.IllegalArgumentException("No enum constant " + enumClass.getCanonicalName() + "." + name))
