package scalago.scalalib.runtime

/** Replacement for the Scala library's LazyVals, whose initializer relies on sun.misc.Unsafe.
 *  Only the control-state markers used by code generated for `lazy val` are provided. The lazy
 *  val field is volatile and updated with an atomic compare-and-set; a goroutine that finds
 *  another one evaluating the initializer waits on a `Waiting` marker.
 */
object LazyVals:
  trait LazyValControlState extends java.io.Serializable

  final class Waiting extends LazyValControlState:
    private var done = false

    def countDown(): Unit = synchronized {
      done = true
      notifyAll()
    }

    def await(): Unit = synchronized {
      while !done do wait()
    }

  object Evaluating extends LazyValControlState
  object NullValue extends LazyValControlState
