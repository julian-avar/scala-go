// Adapted from Scala Native's javalib (Apache-2.0, see javalib/LICENSE-scala-native.md).
package java.util

trait ListIterator[E] extends Iterator[E] {
  def add(e: E): Unit
  def hasPrevious(): Boolean
  def previous(): E
  def previousIndex(): Int
  def nextIndex(): Int
  def set(e: E): Unit
  def hasNext(): scala.Boolean
  def next(): E
  def remove(): Unit
}
