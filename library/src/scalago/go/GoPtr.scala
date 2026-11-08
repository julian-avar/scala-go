package scalago.go

import scalago.go.internal.GoNatives

/** A Go pointer, `*T`, for types that facades do not already represent by pointers.
 *
 *  {{{
 *  import scalago.go.*
 *
 *  val p = GoPtr.to(42L)   // *int64
 *  p.set(p.get + 1)
 *  }}}
 */
opaque type GoPtr[T] = AnyRef

object GoPtr:
  /** Go's `new(T)`: a pointer to a zero value. */
  def alloc[T](using t: GoType[T]): GoPtr[T] = GoNatives.newPtr(t.underlying)
  /** A pointer to a new variable holding `value`. */
  def to[T](value: T)(using t: GoType[T]): GoPtr[T] =
    val p = alloc[T]
    p.set(value)
    p

  extension [T](p: GoPtr[T])
    /** `*p`; a nil pointer throws `NullPointerException`. */
    def get: T = GoNatives.ptrGet(p).asInstanceOf[T]
    /** `*p = value`. */
    def set(value: T): Unit = GoNatives.ptrSet(p, value)
    def isNil: Boolean = GoNatives.isNil(p)
