# scala-go

Compile Scala 3 programs to native executables through Go. A Scala compiler plugin emits an
intermediate representation (`.goir`) next to the `.class` files. A whole-program linker turns
the program's IR into a Go module that `go build` compiles, together with IR for the Scala
standard library (recompiled from its TASTy) and a Java standard library written in Scala.

See [docs/design.md](docs/design.md) for the architecture, object model, status and roadmap.

**Status:** early, but real programs work. Classes, traits, objects, exceptions, pattern
matching, lambdas, `lazy val`, the standard collections and `java.util` (collections, `Arrays`,
`Collections`, `Optional`, `Random`, ...) all run, and Scala code can call Go
packages, and Go code can call Scala libraries. Threads run on goroutines, with `synchronized`,
`wait`/`notify`, volatiles and atomics, and gears runs on them. The generated code is safe to
call from any goroutine. `BigDecimal` and `BigInt` match the JDK. Programs read and write files
(`java.io`, `java.nio.file`, `scala.io.Source`) and standard input. `java.util.stream`, reflection and most of
`java.util.concurrent` don't work yet.

## Using it with Mill

```scala
//| mvnDeps: ["io.github.scalago:mill-scalago_mill1_3:0.1.0-SNAPSHOT"]
import scalago.mill.ScalaGoModule

object app extends ScalaGoModule   // Scala 3.9.0
```

```sh
mill app.goRun arg1 arg2    # compile, link, go build, run
mill app.goLink             # just the Go module (out/app/goLink.dest/go)
```

Nothing is published yet: `mill __.publishLocal` publishes everything to the local Ivy
repository. Requires a JDK and Go ≥ 1.22 on the `PATH`.

Strings are `java.lang.String` (UTF-16), so programs behave as on the JVM. `scalago.go.GoString`
is the opt-in Go (UTF-8) string, like Scala Native's `CString`:

```scala
import scalago.go.*
val g = "Grüße".toGoString    // a Go string on Go, a byte array on the JVM
g.byteLength                  // 7
```

## Calling Go

Declare the Go API you need as a facade. The linker checks it against the real Go package and
converts values at the boundary:

```scala
import scalago.go.*

@GoImport("strings")
object strings:
  def Repeat(s: String, count: Int): String = native          // count is a Go int
  def FieldsFunc(s: String, f: Int => Boolean): Array[String] = native
  class Builder:                                              // strings.Builder (new = zero value)
    def WriteString(s: String): Int = native                  // the (int, error) error is checked
    def String(): String = native

@GoImport("strconv")
object strconv:
  def Atoi(s: String): Int = native                           // a non-nil error throws GoException
  def ParseBool(s: String): (Boolean, GoError) = native       // or keep the error

@GoImport("io")
object io:
  trait Writer:                                               // Go interfaces can be implemented in Scala
    def Write(p: Array[Byte]): Int                            // ([]byte) (int, error)

@GoImport("slices")
object slices:
  def Max(xs: GoSlice[GoInt]): GoInt = native                 // generic: Go infers [[]int, int]
  def Sort(xs: GoSlice[Long]): Unit = native                  // sorts the shared []int64 in place
```

`GoSlice`, `GoMap`, `GoChan` and `GoPtr` are Go data shared without copying
(`GoSlice.make[GoInt](n)` is an `[]int`, `GoMap.make[GoString, Long]` a `map[string]int64`). They
also run on the JVM. Go funcs returned by facades are Scala functions.

Facades can also be generated from the Go packages themselves: list them in `goFacadePackages`
(their objects are in the Scala package `gofacades`), or depend on `scalago-gostd`, the generated
facades of the most-used standard library packages (`scalago.gostd.strings`, `fmt`, `time`,
`net/url`, `encoding/json`, ...).

Go modules other than the standard library go in `goRequires` (`"module@version"`), or in
`goLocalModules` for local directories with a `go.mod` (see `example/`). Facade members throw on
the JVM. See [docs/design.md](docs/design.md#go-interop) for the conversion rules.

## Concurrency with gears

[gears](https://github.com/lampepfl/gears) runs on goroutines, unchanged: add it as a normal
dependency, plus `scalago-gears`. Scala library dependencies are compiled to Go from the TASTy
in their jars.

```scala
import gears.async.*
import gears.async.default.given

Async.blocking:
  val lengths = names.map(n => Future(n.length))   // each Future is a goroutine
  println(lengths.awaitAll)
```

`scalago.gears` adds cancellable Go channel operations (`awaitReceive`, `awaitSend`) and
`withGoContext(ctx)(body)`, which cancels `body` when a Go `context.Context` is done.

## Calling Scala from Go

Mark the API with `@GoExport` and link the module as a library (`ScalaGoLibraryModule`, with
`goModulePath` set to the import path). `goLink` produces a Go module that Go code requires and
imports:

```scala
object Api:
  @GoExport def greet(name: String): String = s"Hello, $name!"     // func Greet(name string) string

@GoExport class Counter(start: Int):                              // type Counter interface { ... }
  private var count = start                                       // func NewCounter(start int32) Counter
  @GoExport def increment(): Unit = synchronized { count += 1 }   // Increment()
```

```go
c := mylib.NewCounter(1)
go c.Increment() // Scala code is safe to call from any goroutine
```

Methods annotated with `@throws` return a Go `error`. Other exceptions are panics. See
`tests/cases/go-library` and docs/design.md.

## Layout

| Path                 | What                                                                    |
|----------------------|-------------------------------------------------------------------------|
| `ir/`                | IR trees and their binary serialization                                 |
| `plugin/`            | Scala 3 compiler plugin (phase `genGoIR`)                               |
| `linker/`            | Linker: lambdas, reachability, Go codegen; CLI `scalago.linker.Main`    |
| `runtime/rt/`        | Go runtime package (strings, exceptions, natives), bundled in the linker |
| `runtime/goapi/`     | Helper the linker runs to read the APIs of Go packages                  |
| `javalib/`           | JDK classes in Scala (emitted as `java.*`) and Scala-library overrides  |
| `library/`           | User-facing APIs: Go facades and exports, `GoString`, `GoSlice`, ...    |
| `gears/`             | gears on goroutines (`scalago-gears`): an override and Go helpers       |
| `gostd/`             | Generated facades of Go standard library packages (`scalago-gostd`)     |
| `mill-build/src/`    | `ScalaGoModule`/`ScalaGoLibraryModule` and `TastyIR`, published as `mill-scalago` |
| `example/`           | A program built with `ScalaGoModule`: `mill example.goRun Ada Grace`    |
| `bench/`             | CPU benchmarks, run on the JVM (`mill bench.run`) and as Go (`mill bench.goRun`) |
| `tests/cases/`       | End-to-end programs with their expected output                          |

The `stdlib` module produces the Scala standard library's IR (`mill stdlib.ir`).

## Development

`./mill` is Mill's launcher (it downloads the Mill version the build names), so only a JDK (17+)
and Go (1.22+) need to be installed. CI (`.github/workflows/ci.yml`) runs the runtime's unit
tests on amd64 and arm64 (Linux, macOS; oldest and newest Go) and everything below.

```sh
mill linker.test                 # end-to-end tests (compile, run on JVM and as Go, compare)
cd runtime && go test ./...      # runtime unit tests
mill example.goRun               # the example, through ScalaGoModule
mill bench.run 3; mill bench.goRun 3   # CPU benchmarks on the JVM and as Go
```

Each directory in `tests/cases/` holds a program and an `expected.txt`. Cases with a
`go-only.txt` (they call Go) skip the JVM, subdirectories with a `go.mod` are local Go modules, and
a `link-errors.txt` lists errors that linking must report instead of running. Cases with a
`race.txt` also run under Go's race detector (`allow-race: <Go function>` lines in it tolerate
known races in third-party libraries). A `library.txt` case is linked as a library and
used by the Go program in its `consumer/` directory. The entry point is
`Main` unless `main.txt` names another one. Both runs read `stdin.txt` on standard input if there
is one (otherwise standard input is closed), and must exit with the status in `exit-status.txt`
(0 by default; an uncaught exception exits with 1). The test compiles the program with the plugin and
runs it on the JVM. It then links it to Go, runs `go vet` and `go build`, and runs the binary.
Both outputs must equal `expected.txt`. The work directory of a failing case is kept under
`/tmp/scalago-<case>-*` for inspection. Set `SCALAGO_KEEP_WORK=1` to keep passing ones too.
Cases run concurrently; `SCALAGO_TEST_PARALLELISM` sets how many at once, and
`SCALAGO_TEST_ONLY=regex,traits` runs only the named cases.

The generated Go code carries line directives, so Go reports Scala positions, also in
compiler errors. `SCALAGO_LINE_DIRECTIVES=false` (the linker's `--line-directives false`)
leaves them out, to see positions in the generated code. `SCALAGO_TRACE=1` at run time keeps
all Go frames in stack traces.
