package scalago.javalib.lang

import scala.annotation.static

import scalago.javalib.io.PrintStream

/** java.lang.System: only the standard streams are defined here; the other methods are
 *  implemented natively by the linker.
 */
final class System private ()

object System:
  @static val out: PrintStream = new PrintStream(false)
  @static val err: PrintStream = new PrintStream(true)
