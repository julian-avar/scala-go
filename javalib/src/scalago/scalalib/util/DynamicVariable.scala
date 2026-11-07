package scalago.scalalib.util

import java.lang.InheritableThreadLocal

/** Replacement for the Scala library's DynamicVariable, with the same behavior and a fast path:
 *  until a variable is first set, its value is `init` in every thread, so reading it needs no
 *  thread-local lookup. `Console.out`, read by every `println`, is such a variable unless the
 *  program uses `Console.withOut`.
 */
class DynamicVariable[T](init: T):
  @volatile private var everSet = false

  private val tl = new InheritableThreadLocal[T]:
    override protected def initialValue(): T = init

  def value: T = if everSet then tl.get() else init

  def withValue[S](newval: T)(thunk: => S): S =
    val oldval = value
    everSet = true
    tl.set(newval)
    try thunk
    finally tl.set(oldval)

  def value_=(newval: T): Unit =
    everSet = true
    tl.set(newval)

  override def toString(): String = "DynamicVariable(" + value + ")"
