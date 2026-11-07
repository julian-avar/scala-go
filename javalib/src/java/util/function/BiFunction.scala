// Adapted from Scala Native's javalib (Apache-2.0, see javalib/LICENSE-scala-native.md).
// Ported from Scala.js, commit SHA: 7b4e8a80b dated: 2022-12-06
package java.util.function

@FunctionalInterface
trait BiFunction[T, U, R] {
  def apply(t: T, u: U): R

  def andThen[V](after: Function[? >: R, ? <: V]): BiFunction[T, U, V] = {
    (t: T, u: U) =>
      after.apply(this.apply(t, u))
  }
}
