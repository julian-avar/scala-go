# scala-go

Compile Scala 3 programs to native executables through Go. A Scala compiler plugin emits an
intermediate representation (`.goir`) next to the `.class` files. A whole-program linker turns
the program's IR into a Go module that `go build` compiles, together with IR for the Scala
standard library (recompiled from its TASTy) and a Java standard library written in Scala.

**Status:** real programs and popular libraries work; not yet published. The language, the
standard collections, `scala.concurrent` and gears on goroutines, and most of the JDK that
programs use (`java.util` with `concurrent` and streams, `java.io`, `java.nio`, `java.math`,
`java.time`, `java.net` with HTTP, subprocesses, digests, `java.util.zip`) run with the JVM's
semantics. Scala 3 libraries are compiled from the TASTy in their jars: cats, upickle,
fastparse, pprint, mainargs and os-lib are checked against the JVM. Scala code calls Go packages
(bubbletea, for example), and Go code calls Scala libraries. Runtime reflection, Java-only
libraries and Scala 2 libraries do not work.

## Documentation

The documentation is a Scaladoc site, with the API: `./mill docs.site`, then open
`out/docs/site.dest/docs/index.html`. Its pages are in [docs/_docs](docs/_docs):
[getting started](docs/_docs/getting-started.md), the [user's guide](docs/_docs/user/index.md),
[Go interop](docs/_docs/interop/index.md), [libraries](docs/_docs/libraries/index.md). The
[design document](docs/design.md) explains the architecture, and lists the status and roadmap.

## Quick start

Requires a JDK (17+), Go (1.22+) and Mill. Nothing is published yet: `./mill __.publishLocal`
publishes everything to the local Ivy repository. Then, in `build.mill`:

```scala
//| mvnDeps: ["io.github.scalago:mill-scalago_mill1_3:0.1.0-SNAPSHOT"]
import scalago.mill.ScalaGoModule

object app extends ScalaGoModule   // Scala 3.9.0
```

```sh
mill app.goRun arg1 arg2    # compile, link, go build, run
mill app.goBuild            # the executable: out/app/goBuild.dest/app
mill app.run                # the same program on the JVM
```

Calling Go, from generated facades (`goFacadePackages = Seq("github.com/charmbracelet/bubbletea", ...)`):

```scala
import gofacades.{lipgloss, tea}

final case class Counter(n: Int) extends tea.Model:   // a Go interface, implemented in Scala
  def Init(): tea.Cmd = null
  def Update(msg: tea.Msg): (tea.Model, tea.Cmd) = msg match
    case k: tea.KeyMsg if k.String() == "q" => (this, tea.Quit)
    case k: tea.KeyMsg if k.String() == "+" => (Counter(n + 1), null)
    case _                                  => (this, null)
  def View(): String = lipgloss.NewStyle().Bold(true).Render(s"count: $n")
```

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
| `examples/basics/`   | A program built with `ScalaGoModule`: `mill examples.basics.goRun Ada Grace` |
| `examples/ecosystem/`| Popular Scala libraries compiled from TASTy (`mill examples.ecosystem.check`: JVM and Go agree) |
| `examples/gotui/`    | A bubbletea/bubbles/lipgloss terminal app in Scala (`mill examples.gotui.goRun`; `mill examples.gotui.check`) |
| `examples/gin/`      | A JSON API on gin (`mill examples.gin.goRun`; `mill examples.gin.check`) |
| `examples/argus/`    | Configuration reloading with argus (`mill examples.argus.check`; needs cgo) |
| `bench/`             | CPU benchmarks, run on the JVM (`mill bench.run`) and as Go (`mill bench.goRun`) |
| `tests/published/`   | A project using only the published artifacts (`tests/published/check.sh` after `publishLocal`) |
| `tests/cases/`       | End-to-end programs with their expected output                          |
| `docs/`              | Documentation site (`_docs/`, `sidebar.yml`; `mill docs.site`) and the design document |

The `stdlib` module produces the Scala standard library's IR (`mill stdlib.ir`).

## Development

`./mill` is Mill's launcher (it downloads the Mill version the build names), so only a JDK (17+)
and Go (1.22+) need to be installed. CI (`.github/workflows/ci.yml`) runs the runtime's unit
tests on amd64 and arm64 (Linux, macOS; oldest and newest Go) and everything below.

```sh
mill linker.test                 # end-to-end tests (compile, run on JVM and as Go, compare)
cd runtime && go test ./...      # runtime unit tests
mill examples.basics.goRun       # an example, through ScalaGoModule
mill examples.__.check           # examples that check themselves (bubbletea, Scala libraries)
mill bench.run 3; mill bench.goRun 3   # CPU benchmarks on the JVM and as Go
mill docs.site                   # documentation site with the API: out/docs/site.dest
```

Each directory in `tests/cases/` holds a program and an `expected.txt`. Cases with a
`go-only.txt` (they call Go) skip the JVM, subdirectories with a `go.mod` are local Go modules, and
a `link-errors.txt` lists errors that linking must report instead of running. Cases with a
`race.txt` also run under Go's race detector (`allow-race: <Go function>` lines in it tolerate
known races in third-party libraries). A `library.txt` case is linked as a library and
used by the Go program in its `consumer/` directory. The entry point is
`Main` unless `main.txt` names another one. Both runs read `stdin.txt` on standard input if there
is one (otherwise standard input is closed), get the environment variables of `env.txt`
(`NAME=value` lines, such as a fixed `TZ`), and must exit with the status in `exit-status.txt`
(0 by default; an uncaught exception exits with 1). The test compiles the program with the plugin and
runs it on the JVM. It then links it to Go, runs `go vet` and `go build`, and runs the binary.
Both outputs must equal `expected.txt`. The work directory of a failing case is kept under
`/tmp/scalago-<case>-*` for inspection. Set `SCALAGO_KEEP_WORK=1` to keep passing ones too.
Cases run concurrently; `SCALAGO_TEST_PARALLELISM` sets how many at once, and
`SCALAGO_TEST_ONLY=regex,traits` runs only the named cases. A `missing-apis.txt` case is linked
with `--missing-apis throw`. The tests use their own Go build cache
(`~/.cache/scalago-tests/go-build`), pruned when they start of entries unused for a day and,
beyond `SCALAGO_TEST_GOCACHE_GB` (10 GB by default), of the least recently used ones.

The generated Go code carries line directives, so Go reports Scala positions, also in
compiler errors. `SCALAGO_LINE_DIRECTIVES=false` (the linker's `--line-directives false`)
leaves them out, to see positions in the generated code. `SCALAGO_TRACE=1` at run time keeps
all Go frames in stack traces.
