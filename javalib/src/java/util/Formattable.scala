// Adapted from Scala Native's javalib (Apache-2.0, see javalib/LICENSE-scala-native.md).

package java.util

trait Formattable {
  def formatTo(
      formatter: Formatter,
      flags: Int,
      width: Int,
      precision: Int
  ): Unit
}
