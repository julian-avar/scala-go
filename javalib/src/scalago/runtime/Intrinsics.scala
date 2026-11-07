package scalago.runtime

/** Compiler intrinsics for javalib: atomic operations on `@volatile` fields. The first argument
 *  must be a field selection (`Intrinsics.getAndAdd(count, 1)`, `Intrinsics.compareAndSet(node.next,
 *  null, n)`); the plugin replaces the calls with IR `CompareAndSet`, `GetAndSet` and `GetAndAdd`,
 *  which become Go `sync/atomic` operations. Objects are compared by identity.
 */
object Intrinsics:
  private def native: Nothing = throw new UnsupportedOperationException("scala-go intrinsic")

  /** Sets `field` to `update` if it holds `expected`; whether it did. */
  def compareAndSet(field: Int, expected: Int, update: Int): Boolean = native
  def compareAndSet(field: Long, expected: Long, update: Long): Boolean = native
  def compareAndSet(field: Boolean, expected: Boolean, update: Boolean): Boolean = native
  def compareAndSet(field: AnyRef, expected: AnyRef, update: AnyRef): Boolean = native

  /** Sets `field` to `value`; its previous value. */
  def getAndSet(field: Int, value: Int): Int = native
  def getAndSet(field: Long, value: Long): Long = native
  def getAndSet(field: Boolean, value: Boolean): Boolean = native
  def getAndSet(field: AnyRef, value: AnyRef): AnyRef = native

  /** Adds `delta` to `field`; its previous value. */
  def getAndAdd(field: Int, delta: Int): Int = native
  def getAndAdd(field: Long, delta: Long): Long = native
