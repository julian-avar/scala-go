package java.nio.charset

import scala.annotation.static

final class CodingErrorAction private (name: String):
  override def toString(): String = name

object CodingErrorAction:
  @static val IGNORE: CodingErrorAction = new CodingErrorAction("IGNORE")
  @static val REPLACE: CodingErrorAction = new CodingErrorAction("REPLACE")
  @static val REPORT: CodingErrorAction = new CodingErrorAction("REPORT")
