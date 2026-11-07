// Adapted from Scala Native's javalib (Apache-2.0, see javalib/LICENSE-scala-native.md).
package java.util

trait Queue[E] extends Collection[E] {
  def add(e: E): Boolean
  def offer(e: E): Boolean
  def remove(): E
  def poll(): E
  def element(): E
  def peek(): E
}
