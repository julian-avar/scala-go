package scalago.runtime

/** An array of volatile references, for javalib's concurrent code: `AtomicReferenceArray`, and
 *  the arrays that the JDK's concurrent collections read without locks (the tables of
 *  `ConcurrentHashMap`, the cells of `LongAdder`, ...). The JVM reads references atomically even
 *  in a data race, but Go does not (a reference is an interface, two words), so such arrays must
 *  be volatile. Elements are compared by identity.
 */
final class VolatileArray[T](val length: Int):
  private val refs: Object = AtomicArrays.newRefs(length)

  def apply(i: Int): T = AtomicArrays.get(refs, i).asInstanceOf[T]
  def update(i: Int, x: T): Unit = AtomicArrays.set(refs, i, x.asInstanceOf[Object])
  def compareAndSet(i: Int, expected: T, update: T): Boolean =
    AtomicArrays.compareAndSet(refs, i, expected.asInstanceOf[Object], update.asInstanceOf[Object])
  def getAndSet(i: Int, x: T): T = AtomicArrays.getAndSet(refs, i, x.asInstanceOf[Object]).asInstanceOf[T]

  def foreach(f: T => Unit): Unit =
    var i = 0
    while i < length do
      f(apply(i))
      i += 1

  /** A copy of the first `newLength` elements, padded with nulls (`Arrays.copyOf`). */
  def copyOf(newLength: Int): VolatileArray[T] =
    val copy = new VolatileArray[T](newLength)
    var i = 0
    val n = math.min(length, newLength)
    while i < n do
      copy(i) = apply(i)
      i += 1
    copy
