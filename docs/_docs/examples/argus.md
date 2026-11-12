---
title: argus
---

# argus

Configuration that reloads itself when its file changes, on [argus](https://github.com/agilira/argus)
([source](https://github.com/julian-avar/scala-go/tree/main/examples/argus)). Facades of argus
are [generated](../interop/generated-facades.md); `time` comes from
[`scalago-gostd`](../libraries/gostd.md).

`LiveConfig` uses argus's `Setup` builder, with defaults, a JSON file and Scala callbacks:

```scala sc:nocompile
private val settings = argus.Setup("demo")
  .Defaults(GoData.map("server.port" -> 8080, "server.timeout" -> "30s", "log.level" -> "info"))
  .File(file.toString)
  .MaxStaleness(time.ParseDuration("100ms"))
  .OnReload { (s, change) =>                                    // on argus's goroutine
    val config = ServiceConfig.from(s)
    current.set(config)                                         // an AtomicReference
    onEvent(ConfigEvent.Reloaded(config, change.Keys.toSeq.map(_.toJavaString).sorted))
  }
  .OnError(err => onEvent(ConfigEvent.Rejected(err.Error())))
  .Start()
```

- **Immutable revisions.** Each revision becomes an immutable `ServiceConfig` case class,
  swapped atomically in an `AtomicReference`. A file that does not parse calls `OnError`; the last
  good configuration stays.
- **Reading keys.** `ServiceConfig.from` reads key by key (`GetString`, `GetInt`, `GetDuration`,
  `GetStringSlice`), because `argus.Bind[T]` binds onto a Go struct Scala cannot declare. A Go
  `time.Duration` becomes a `java.time.Duration` through `Nanoseconds()`.
- **Plain data.** `FlagsWatcher` uses argus's universal watcher: it parses a YAML file into a Go
  map, which [`GoData.toScala`](../interop/go-data.md) turns into a Scala `Map`.
- **Callbacks on Go goroutines.** argus calls `OnReload`, `OnError` and the watcher's callback on
  its own goroutines. They hand events to Scala code through an `AtomicReference` and a
  `LinkedBlockingQueue`.

Run it with `./mill examples.argus.goRun service.json` (watches the file) or
`./mill examples.argus.check` (edits files in a temporary directory, checks each reload). The
[README](https://github.com/julian-avar/scala-go/blob/main/examples/argus/README.md) has the
file format, the cgo note and the limitations, with workarounds.
