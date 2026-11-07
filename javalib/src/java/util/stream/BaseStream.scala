// Adapted from Scala Native's javalib (Apache-2.0, see javalib/LICENSE-scala-native.md).
package java.util.stream

import java.util.{Iterator, Spliterator}

trait BaseStream[T, S <: BaseStream[T, S]] extends AutoCloseable {
  def close(): Unit
  def isParallel(): Boolean
  def iterator(): Iterator[T]
  def onClose(closeHandler: Runnable): S
  def parallel(): S
  def sequential(): S
  def spliterator(): Spliterator[T]
  def unordered(): S
}
