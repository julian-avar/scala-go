package scalago.scalalib.runtime

/** Replacement for the Scala library's LazyVals, whose initializer relies on sun.misc.Unsafe.
 *  Only the control-state markers used by code generated for `lazy val` are provided; programs
 *  are single-threaded, so a lazy val never waits for another thread.
 */
object LazyVals:
  trait LazyValControlState extends java.io.Serializable

  final class Waiting extends LazyValControlState:
    def countDown(): Unit = ()
    def await(): Unit = throw new IllegalStateException("cyclic lazy val initialization")

  object Evaluating extends LazyValControlState
  object NullValue extends LazyValControlState
