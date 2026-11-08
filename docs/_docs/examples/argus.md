---
title: argus
---

# argus

Configuration that reloads itself when its file changes, on
[argus](https://github.com/agilira/argus)
([source](https://github.com/julian-avar/scala-go/tree/main/examples/argus)). Each revision
becomes an immutable Scala case class, swapped atomically; a file that does not parse is rejected
and the last good configuration stays. A second watcher reads YAML feature flags as plain Scala
data.

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

## Running it

```sh
./mill examples.argus.goRun service.json   # watches the file
./mill examples.argus.check                # edits files in a temporary directory, checks each reload
```

Go modules: argus v1.7.5 (Go 1.25.9 or later). argus imports `mattn/go-sqlite3`, a cgo package:
with a C compiler installed, the first build compiles SQLite. `CGO_ENABLED=0` builds a stub of it
instead, and the example works that way too, since it uses no SQLite.

## Limitations

- No `argus.Bind[T]`: it binds onto a Go struct with `argus:"..."` tags, which Scala cannot
  declare (and it is generic over that struct). `ServiceConfig.from` reads key by key
  (`GetString`, `GetInt`, `GetDuration`, `GetStringSlice`).
- Go values need converting: a `time.Duration` to a `java.time.Duration` through
  `Nanoseconds()`, a `GoSlice[GoString]` to Scala strings.
- The universal watcher's audit trail is turned off (`DisableAudit`): by default argus writes it
  to a SQLite database at a system path.
- Facades throw on the JVM, so it runs only as Go.

More in the example's
[README](https://github.com/julian-avar/scala-go/blob/main/examples/argus/README.md).
