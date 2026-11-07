// Adapted from Scala Native's javalib (Apache-2.0, see javalib/LICENSE-scala-native.md).

package java.io

trait FilenameFilter {
  def accept(dir: File, filename: String): Boolean
}

object FilenameFilter {
  val allPassFilter: FilenameFilter =
    new FilenameFilter {
      override def accept(dir: File, filename: String): Boolean = true
    }
}
