package scalago.runtime

/** Atomic (sequentially consistent) access to array elements (see runtime/rt/volatile.go). `Int`
 *  and `Long` arrays are ordinary arrays whose elements are only accessed through these methods.
 *  Arrays of references are opaque Go values (`newRefs`), since Go cannot access an interface
 *  atomically: `AtomicReferenceArray` and the tables of the concurrent collections use them.
 *  The linker replaces these methods with Go code.
 */
object AtomicArrays:
  private def native: Nothing = throw new UnsupportedOperationException("scala-go native")

  def getInt(array: Array[Int], i: Int): Int = native
  def setInt(array: Array[Int], i: Int, x: Int): Unit = native
  def compareAndSetInt(array: Array[Int], i: Int, expected: Int, update: Int): Boolean = native
  def getAndSetInt(array: Array[Int], i: Int, x: Int): Int = native
  def getAndAddInt(array: Array[Int], i: Int, delta: Int): Int = native

  def getLong(array: Array[Long], i: Int): Long = native
  def setLong(array: Array[Long], i: Int, x: Long): Unit = native
  def compareAndSetLong(array: Array[Long], i: Int, expected: Long, update: Long): Boolean = native
  def getAndSetLong(array: Array[Long], i: Int, x: Long): Long = native
  def getAndAddLong(array: Array[Long], i: Int, delta: Long): Long = native

  /** A new array of `n` volatile references, all null. */
  def newRefs(n: Int): Object = native
  def length(refs: Object): Int = native
  def get(refs: Object, i: Int): Object = native
  def set(refs: Object, i: Int, x: Object): Unit = native
  def compareAndSet(refs: Object, i: Int, expected: Object, update: Object): Boolean = native
  def getAndSet(refs: Object, i: Int, x: Object): Object = native
