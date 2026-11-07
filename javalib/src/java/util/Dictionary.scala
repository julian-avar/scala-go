// Adapted from Scala Native's javalib (Apache-2.0, see javalib/LICENSE-scala-native.md).
package java.util

abstract class Dictionary[K, V] {
  def size(): Int
  def isEmpty(): Boolean
  def keys(): Enumeration[K]
  def elements(): Enumeration[V]
  def get(key: Any): V
  def put(key: K, value: V): V
  def remove(key: Any): V
}
