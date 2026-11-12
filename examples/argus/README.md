# argus

Configuration that follows its file, on [argus](https://github.com/agilira/argus), a Go library
that watches configuration files and reloads them. Source: [`src/Main.scala`](src/Main.scala).
Explained in the documentation's [argus](../../docs/_docs/examples/argus.md) page.

## Running it

From the repository's root:

```sh
./mill examples.argus.goRun /path/to/service.json   # watches the file, prints each reload
./mill examples.argus.check                          # --self-test: edits files in a temporary directory
```

A configuration file:
`{"server": {"host": "0.0.0.0", "port": 9000, "timeout": "5s"}, "log": {"level": "debug"}, "features": ["search"]}`.
`check` ends with `all changes seen as expected`.

Go modules (`goRequires`): `github.com/agilira/argus@v1.7.5`, with generated facades; `time` comes
from `scalago-gostd`. The first build needs network access.

**cgo:** argus imports `github.com/mattn/go-sqlite3` for its audit trail. With cgo enabled (Go's
default when a C compiler is installed), the first build compiles SQLite's C code, which takes a
while. With `def goBuildEnv = Map("CGO_ENABLED" -> "0")`, go-sqlite3 is a stub; the example uses
no SQLite, and its check passes that way too.

## Limitations

- **No `argus.Bind[T]` onto a Scala class:** it binds onto a Go struct type with `argus:"..."`
  tags, which Scala cannot declare (the generic facade, `Bind[T]` returning a `Bound[T]`, exists).
  Workaround: `ServiceConfig.from` reads each key with `GetString`, `GetInt`, `GetDuration`,
  `GetStringSlice`.
- **Go values need converting:** a `time.Duration` is a facade value, not a number; it becomes a
  `java.time.Duration` through `Nanoseconds()`. `GetStringSlice` returns a `GoSlice[GoString]`,
  mapped with `toJavaString`. Durations for argus are made with `time.ParseDuration("100ms")`.
- **Audit trail off** for the universal watcher (`config.DisableAudit = true`): by default argus
  writes it to a SQLite database at a system path. The `Setup` builder records none unless asked
  (`.Audit()`).
- **`GoData.toScala` gives Go's shapes:** maps become `Map`s sorted by key, lists `Vector`s, YAML
  integers `Long`s, decimals `Double`s.
- **Left out of the generated facade:** `Source.UnmarshalJSON` (a pointer method of a non-struct
  type).
- **Runs only as Go:** facade members throw on the JVM.

All limitations: [docs/_docs/user/limitations.md](../../docs/_docs/user/limitations.md).
