// Adapted from Scala Native's javalib (Apache-2.0, see javalib/LICENSE-scala-native.md).
package java.util

trait SequencedSet[E /* <: AnyRef */ ]
    extends SequencedCollection[E]
    with Set[E] {

  def reversed(): SequencedSet[E]
}
