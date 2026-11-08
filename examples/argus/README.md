# argus

Configuration that follows its file, on [argus](https://github.com/agilira/argus), a Go
configuration library that watches files and reloads them
([`src/Main.scala`](src/Main.scala)):

- `LiveConfig`: argus's `Setup` builder with defaults (`GoData.map`), a JSON file and Scala
  callbacks (`OnReload`, `OnError`). Each revision becomes an immutable `ServiceConfig` case
  class, swapped atomically; a file that does not parse is rejected and the last good
  configuration stays;
- `FlagsWatcher`: argus's universal watcher parses a YAML file into a Go map, which
  `GoData.toScala` turns into Scala data;
- Go callbacks arriving on argus's own goroutines, handed to Scala code through an
  `AtomicReference` and a `LinkedBlockingQueue`.

## Running it

From the repository's root:

```sh
./mill examples.argus.goRun /path/to/service.json   # watches the file, prints each reload
./mill examples.argus.check                          # --self-test: edits files in a temporary directory
```

A configuration file looks like
`{"server": {"host": "0.0.0.0", "port": 9000, "timeout": "5s"}, "log": {"level": "debug"}, "features": ["search"]}`.
`check` ends with `all changes seen as expected`.

## Go modules

`goRequires`: `github.com/agilira/argus@v1.7.5`, with facades generated for it; `time` comes from
`scalago-gostd`. argus's `go.mod` asks for Go 1.25.9 or later (with Go's default
`GOTOOLCHAIN=auto`, an older `go` downloads it). The first build needs network access.

argus imports `github.com/mattn/go-sqlite3`, a cgo package, for its audit trail. With cgo enabled
(Go's default when a C compiler is installed), the first build compiles SQLite's C code, which
takes a while. With `CGO_ENABLED=0` (`def goBuildEnv = Map("CGO_ENABLED" -> "0")`) go-sqlite3 is a
stub; the example does not use SQLite, and its check passes that way too.

## Limitations

- **No `argus.Bind[T]`.** argus can bind a configuration onto a Go struct type with `argus:"..."`
  tags, but Scala cannot declare Go struct types, and `Bind` is generic over the struct (it is
  left out of the generated facade, as is `Bound.Value`). `ServiceConfig.from` reads each key
  with `GetString`, `GetInt`, `GetDuration` and `GetStringSlice` instead.
- **Go values to convert:** a Go `time.Duration` is a facade value, not a number; it becomes a
  `java.time.Duration` through its `Nanoseconds()`. `GetStringSlice` returns a
  `GoSlice[GoString]`, mapped to Scala strings with `toJavaString`. Durations given to argus are
  made with `time.ParseDuration("100ms")`.
- **The audit trail is turned off** for the universal watcher (`config.DisableAudit = true`):
  by default argus writes one into a SQLite database at a system path. The `Setup` builder
  records no audit trail unless asked to (`.Audit()`).
- **`GoData.toScala` gives Go's shapes:** maps become `Map`s sorted by key, lists `Vector`s, YAML
  integers `Long`s and decimals `Double`s.
- **A member left out of the generated facade:** `CacheStats.Path` (a Go array).
- **Runs only as Go:** facade members throw on the JVM.

See also the documentation's [Limitations](../../docs/_docs/user/limitations.md) page.
