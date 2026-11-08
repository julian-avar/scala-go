---
title: Concurrency
---

# Concurrency

Every Java thread is a goroutine, so the JVM's concurrency APIs work unchanged and Go's
scheduler runs them.

## Threads, executors and futures

- `java.lang.Thread` runs on a goroutine: `start`, `join`, `sleep`, `interrupt`, thread locals,
  daemon threads and uncaught-exception handlers work as on the JVM. Virtual threads
  (`Thread.ofVirtual()`) are goroutines too.
- `java.util.concurrent` is complete for common use: executors, `ForkJoinPool`,
  `CompletableFuture`, locks, atomics, latches, semaphores and concurrent collections.
- `scala.concurrent.Future` runs on the global `ExecutionContext`, a `ForkJoinPool`, and
  `blocking { ... }` works as on the JVM.
- `Runtime.availableProcessors` is `GOMAXPROCS`.
- As on the JVM, the program exits when `main` returns and all non-daemon threads have ended.

## gears

[gears](https://github.com/lampepfl/gears), the direct-style concurrency library, runs on
goroutines: each `Future` is a goroutine. Add `scalago-gears`, which brings gears with it:

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

gears itself is compiled from its published jar; `scalago-gears` replaces the one class that
needs JVM internals. The same code runs on the JVM, on virtual threads.

## Go channels

`GoChan` is a Go channel (see [Go data](../interop/go-data.md#channels)). `send` and `receive`
block the calling thread. In gears code, `scalago.gears` adds cancellable operations:

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

`withGoContext(ctx)(body)` runs `body` until it completes or the Go `context.Context` is done,
which cancels it and throws `CancellationException`. This is how exported Scala code honours
the deadlines and cancellation of its Go callers:

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

Synchronize shared state as on the JVM: `synchronized`, `@volatile`, atomics, locks, concurrent
collections, `Thread.start`/`join` and Go channels all order memory as the Java memory model
says.

One difference matters for buggy programs: a **data race** on a field holding a reference can
crash a Go program, where the JVM only shows a stale value. `final` fields also get no special
guarantee: publish objects to other threads through one of the mechanisms above. Go's race
detector finds races: `def goBuildFlags = Seq("-race")`.

Generated code is safe to call from any goroutine: objects and `lazy val`s initialize once,
and the runtime's own state is thread-safe.

## Limitations

- The atomic field updaters (`AtomicReferenceFieldUpdater`, ...) are missing, so
  `scala.collection.concurrent.TrieMap` does not link: use `ConcurrentHashMap`, or an immutable
  map behind `synchronized` (as the [gin example](../examples/gin.md) does).
- cats-effect and ZIO do not link yet (see [Scala libraries](../libraries/scala.md)); gears and
  `scala.concurrent` are the concurrency libraries that work.
- Parallel streams run sequentially. `SubmissionPublisher`, `Thread.getAllStackTraces` and thread
  groups' `activeCount` and `enumerate` are missing.
- No `select` over several Go channels from Scala.
- A data race can crash the program, and unsynchronized concurrent writes to a `GoMap` end it
  (see [Go data](../interop/go-data.md#limitations)).

[Limitations](limitations.md) lists all limitations.
