package scalago.scalalib.math

/** Base class of BigInt and BigDecimal (a Java class in the Scala library). */
abstract class ScalaNumber extends java.lang.Number:
  protected def isWhole(): Boolean
  def underlying(): Object

  /** Marks the instances for the runtime: `rt.Equals` compares a box with a `ScalaNumber` by the
   *  latter's `equals`, as `BoxesRunTime` does.
   */
  final def scalaNumber(): Unit = ()
