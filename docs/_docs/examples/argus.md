---
title: argus
---

# argus

Configuration that reloads itself when its file changes, on [argus](https://github.com/agilira/argus)
([source](https://github.com/julian-avar/scala-go/tree/main/examples/argus)), through
[generated facades](../interop/generated-facades.md) and `scalago-gostd`'s `time`. Each revision
becomes an immutable case class in an `AtomicReference`; a file that does not parse calls
`OnError` and the last good configuration stays. argus calls the Scala callbacks on its own
goroutines.

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

Run it with `./mill examples.argus.goRun service.json` (watches the file) or
`./mill examples.argus.check` (edits files in a temporary directory, checks each reload). argus
imports `mattn/go-sqlite3`, which needs [cgo](../interop/go-libraries.md#cgo).

Limitations:

- No `argus.Bind[T]` onto a Scala class (it needs a Go struct with tags): `ServiceConfig.from`
  reads key by key (`GetString`, `GetInt`, `GetDuration`, `GetStringSlice`).
- The universal watcher's audit trail is off (`config.DisableAudit = true`): by default argus
  writes it to a SQLite database at a system path.
- Left out of the generated facade: `Source.UnmarshalJSON` (a pointer method of a non-struct type).
