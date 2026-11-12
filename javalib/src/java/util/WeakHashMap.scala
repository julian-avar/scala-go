package java.util

/** A HashMap. Keys are held strongly: Go has weak pointers since Go 1.24 (generated code could
 *  use them; it does not yet), so entries are never removed by the garbage collector. Programs
 *  behave the same, except that memory a JVM would reclaim is retained; WeakHashMap is mostly
 *  used for caches (`scala.Symbol`'s, for example), where this is harmless.
 */
class WeakHashMap[K, V](initialCapacity: Int, loadFactor: Float) extends HashMap[K, V](initialCapacity, loadFactor):
  def this(initialCapacity: Int) = this(initialCapacity, 0.75f)
  def this() = this(16, 0.75f)
  def this(m: Map[? <: K, ? <: V]) =
    this(Math.max((m.size() / 0.75f).toInt + 1, 16), 0.75f)
    putAll(m)
