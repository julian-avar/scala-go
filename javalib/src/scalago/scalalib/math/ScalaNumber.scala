package scalago.scalalib.math

/** Base class of BigInt and BigDecimal (a Java class in the Scala library). */
abstract class ScalaNumber extends java.lang.Number:
  protected def isWhole(): Boolean
  def underlying(): Object
