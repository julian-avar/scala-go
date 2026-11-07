// Adapted from Scala Native's javalib (Apache-2.0, see javalib/LICENSE-scala-native.md).
// Ported from Scala.js, commit SHA: 4a394815e dated: 2020-09-06
package java.util.function

@FunctionalInterface
trait UnaryOperator[T] extends Function[T, T]

object UnaryOperator {
  def identity[T](): UnaryOperator[T] = (t: T) => t
}
