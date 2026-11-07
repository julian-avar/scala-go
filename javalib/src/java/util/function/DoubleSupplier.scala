// Adapted from Scala Native's javalib (Apache-2.0, see javalib/LICENSE-scala-native.md).
// Ported from Scala.js, commit SHA: db63dabed dated: 2020-10-06
package java.util.function

@FunctionalInterface
trait DoubleSupplier {
  def getAsDouble(): Double
}
