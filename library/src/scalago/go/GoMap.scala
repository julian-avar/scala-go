package scalago.go

import scalago.go.internal.GoNatives

/** A Go map, `map[K]V`, shared with Go code without copying. Keys and values are in their
 *  canonical Scala representations (see `GoType` and `GoSlice`). Iteration order is Go's
 *  (randomized); on the JVM it is insertion order.
 */
opaque type GoMap[K, V] = AnyRef

object GoMap:
  def make[K, V](using k: GoType[K], v: GoType[V]): GoMap[K, V] = GoNatives.makeMap(k.underlying, v.underlying)

  def of[K, V](entries: (K, V)*)(using k: GoType[K], v: GoType[V]): GoMap[K, V] =
    val m = make[K, V]
    for (key, value) <- entries do m(key) = value
    m

  private object Missing

  extension [K, V](m: GoMap[K, V])
    def size: Int = GoNatives.mapSize(m)
    def isEmpty: Boolean = size == 0
    def isNil: Boolean = GoNatives.isNil(m)
    def get(key: K): Option[V] =
      val r = GoNatives.mapLookup(m, key, Missing)
      if r.asInstanceOf[AnyRef] eq Missing then None else Some(r.asInstanceOf[V])
    def getOrElse(key: K, default: => V): V = get(key).getOrElse(default)
    def contains(key: K): Boolean = get(key).isDefined
    /** The value for `key`; throws `NoSuchElementException` if there is none. */
    def apply(key: K): V = get(key).getOrElse(throw new NoSuchElementException(s"key not found: $key"))
    def update(key: K, value: V): Unit = GoNatives.mapStore(m, key, value)
    /** Go's `delete(m, key)`. */
    def remove(key: K): Unit = GoNatives.mapDelete(m, key)
    def iterator: Iterator[(K, V)] =
      val entries = GoNatives.mapEntries(m)
      Iterator.range(0, entries.length / 2).map(i => (entries(2 * i).asInstanceOf[K], entries(2 * i + 1).asInstanceOf[V]))
    def keys: Iterable[K] = iterator.map(_._1).toSeq
    def foreach[U](f: ((K, V)) => U): Unit = iterator.foreach(f)
    def toMap: Map[K, V] = iterator.toMap
