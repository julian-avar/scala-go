// Adapted from Scala Native's javalib (Apache-2.0, see javalib/LICENSE-scala-native.md).

package java.io

trait Flushable {
  def flush(): Unit
}
