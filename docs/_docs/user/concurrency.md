---
title: Concurrency
---

# Concurrency

Every Java thread is a goroutine. The JVM's concurrency APIs work unchanged, scheduled by Go's
scheduler.

## Threads, executors and futures

| API                                | On scala-go                                                          |
|------------------------------------|----------------------------------------------------------------------|
| `java.lang.Thread`                 | a goroutine; `start`, `join`, `sleep`, `interrupt`, thread locals, daemon threads and uncaught-exception handlers work as on the JVM |
| `Thread.ofVirtual()`               | a goroutine too                                                      |
| `java.util.concurrent`             | executors, `ForkJoinPool`, `CompletableFuture`, locks, atomics, latches, semaphores, concurrent collections |
| `scala.concurrent.Future`          | runs on the global `ExecutionContext`, a `ForkJoinPool`; `blocking { ... }` works |
| `Runtime.availableProcessors`      | `GOMAXPROCS`                                                         |

The program exits when `main` returns and all non-daemon threads have ended, as on the JVM.

## gears

[gears](https://github.com/lampepfl/gears) runs on goroutines: each gears `Future` is a
goroutine. gears itself is compiled from its published jar; `scalago-gears` replaces the one class
that needs JVM internals. Add both:

```scala sc:nocompile
object app extends ScalaGoModule {
  def mvnDeps = Seq(
    mvn"ch.epfl.lamp::gears:0.3.1",
    mvn"io.github.scalago::scalago-gears:${scalaGoVersion()}"
  )
}
```

```scala
import gears.async.*
import gears.async.default.given

@main def lengths(): Unit =
  Async.blocking:
    val lengths = Seq("Ada", "Grace").map(n => Future(n.length))   // two goroutines
    println(lengths.awaitAll)
```

The same code runs on the JVM, on virtual threads.

## Go channels

`GoChan` is a Go channel ([Go data](../interop/go-data.md#channels)). `send` and `receive` block
the calling thread. In gears code, `scalago.gears` adds cancellable operations:

```scala
import gears.async.*
import scalago.go.*
import scalago.gears.*

def drain(ch: GoChan[Int])(using Async): Unit =
  ch.awaitReceive match              // cancelling the computation interrupts the receive
    case Some(v) => println(v); drain(ch)
    case None    => ()               // closed
```

## Go contexts

`withGoContext(ctx)(body)` runs `body` until it completes or the Go `context.Context` is done.
When `ctx` is done, `body` is cancelled and `CancellationException` is thrown. Exported Scala code
uses it to honour its Go callers' deadlines and cancellation:

```scala
import gears.async.*
import gears.async.default.given
import scalago.go.*
import scalago.gears.*

object Api:
  @GoExport def search(ctx: context.Context, query: String): String =
    Async.blocking:
      withGoContext(ctx)(slowSearch(query))

  private def slowSearch(query: String)(using Async): String =
    AsyncOperations.sleep(1000)   // cancelled when ctx is done
    s"results for $query"
```

## The memory model

Synchronize shared state as on the JVM. `synchronized`, `@volatile`, atomics, locks, concurrent
collections, `Thread.start`/`join` and Go channels order memory as the Java memory model says.

Two differences affect only incorrectly synchronized programs:

- A **data race** on a reference field can crash the program; the JVM shows a stale value.
- `final` fields get no publication guarantee. Publish objects through one of the mechanisms
  above.

Find races with Go's race detector: `def goBuildFlags = Seq("-race")`.

Generated code is safe to call from any goroutine: objects and `lazy val`s initialize once, and
the runtime's own state is thread-safe. The precise rules are in
[Runtime: memory model](../spec/runtime.md#memory-model).

## Limitations

Missing concurrency APIs (atomic field updaters, `TrieMap`, ...) and their workarounds are in
[Limitations: concurrency](limitations.md#concurrency); `select` over Go channels and `GoMap`
races in [Limitations: Go interop](limitations.md#go-interop).
