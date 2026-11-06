package scalago.javalib.lang

/** java.lang.Enum, for the JDK's enums written in javalib (Scala `enum`s use scala.reflect.Enum). */
abstract class Enum[E <: Enum[E]] protected (_name: String, _ordinal: Int)
    extends Object with Comparable[E] with scalago.javalib.io.Serializable:
  final def name(): String = _name
  final def ordinal(): Int = _ordinal
  override def toString(): String = _name
  final def compareTo(o: E): Int = _ordinal - o.ordinal()
