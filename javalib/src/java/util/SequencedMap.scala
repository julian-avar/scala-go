// Adapted from Scala Native's javalib (Apache-2.0, see javalib/LICENSE-scala-native.md).
package java.util

/** Java 21's SequencedMap. scala-go: the reversed and sequenced views are not implemented (they
 *  throw `UnsupportedOperationException`); `NavigableMap`s have their descending views instead.
 */
trait SequencedMap[K, V] extends Map[K, V] {
  def reversed(): SequencedMap[K, V] =
    throw new UnsupportedOperationException("SequencedMap.reversed")

  def firstEntry(): Map.Entry[K, V] = {
    val it = entrySet().iterator()
    if (it.hasNext()) SequencedMap.copyOf(it.next()) else null
  }

  def lastEntry(): Map.Entry[K, V] = {
    var last: Map.Entry[K, V] = null
    val it = entrySet().iterator()
    while (it.hasNext()) last = it.next()
    if (last == null) null else SequencedMap.copyOf(last)
  }

  def pollFirstEntry(): Map.Entry[K, V] = {
    val it = entrySet().iterator()
    if (it.hasNext()) {
      val entry = SequencedMap.copyOf(it.next())
      it.remove()
      entry
    } else null
  }

  def pollLastEntry(): Map.Entry[K, V] = {
    val entry = lastEntry()
    if (entry != null) remove(entry.getKey())
    entry
  }

  def putFirst(key: K, value: V): V = throw new UnsupportedOperationException()
  def putLast(key: K, value: V): V = throw new UnsupportedOperationException()
}

private object SequencedMap {
  def copyOf[K, V](entry: Map.Entry[K, V]): Map.Entry[K, V] =
    new AbstractMap.SimpleImmutableEntry(entry.getKey(), entry.getValue())
}
