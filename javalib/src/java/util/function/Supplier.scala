// Adapted from Scala Native's javalib (Apache-2.0, see javalib/LICENSE-scala-native.md).
// Ported from Scala.js, commit SHA: 5df5a4142 dated: 2020-09-06
package java.util.function

@FunctionalInterface
trait Supplier[T] {
  def get(): T
}
