import scala.util.Try

import gears.async.*
import gears.async.default.given

import scalago.go.*
import scalago.gears.*

@GoImport("example.com/deadline")
object deadline:
  def After(ms: Int): context.Context = native

/** A Go context's cancellation cancels gears code (as exported code would honour its callers'). */
object Main:
  def main(args: Array[String]): Unit =
    Async.blocking:
      val quick = withGoContext(deadline.After(5000))("finished in time")
      println(quick)
      val slow = Try(withGoContext(deadline.After(30)) {
        AsyncOperations.sleep(5000)
        "too late"
      })
      println(slow.failed.map(e => s"${e.getClass.getSimpleName}: ${e.getMessage}"))
      println(s"background: ${context.Background().Err() == null}")
