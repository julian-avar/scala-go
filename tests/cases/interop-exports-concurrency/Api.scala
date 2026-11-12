import java.util.concurrent.atomic.AtomicInteger

import gears.async.*
import gears.async.default.given

import scalago.go.*
import scalago.gears.*

// Exported Scala code called from goroutines (docs/_docs/spec/interop.md, "Goroutines and
// threads"; docs/_docs/interop/exports.md, "Rules"), and honouring Go contexts with gears
// (docs/_docs/user/concurrency.md, "Go contexts").
object Expensive:
  val inits = AtomicInteger()
  lazy val table: Map[Int, String] =
    inits.incrementAndGet()
    Thread.sleep(20)
    (0 until 10).map(i => i -> s"v$i").toMap

object Api:
  private val calls = ThreadLocal.withInitial[Integer](() => 0)
  private var threads = Set.empty[Thread]

  /** Initializes the lazy val once, whichever goroutines get there first. */
  @GoExport def lookup(i: Int): String = Expensive.table(i % 10)
  @GoExport def inits(): Int = Expensive.inits.get

  /** Counts the calls of the calling goroutine: a ThreadLocal per goroutine. */
  @GoExport def countCall(): Int =
    calls.set(calls.get + 1)
    calls.get

  /** Records the calling goroutine's Thread, and tells how many there were. */
  @GoExport def recordThread(): Int = synchronized {
    threads += Thread.currentThread
    threads.size
  }

  /** Runs until done, or until ctx is done (the example of the user guide). */
  @GoExport @throws[Exception] def search(ctx: context.Context, query: String, ms: Int): String =
    Async.blocking:
      withGoContext(ctx):
        AsyncOperations.sleep(ms)
        s"results for $query"
