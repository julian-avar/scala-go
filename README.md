# scala-go

Compile Scala 3 programs to native executables through Go. A Scala compiler plugin emits an
intermediate representation (`.goir`) next to the `.class` files. A whole-program linker turns
the program's IR into a Go module that `go build` compiles, together with IR for the Scala
standard library (recompiled from its TASTy) and a Java standard library written in Scala.

**Status:** not yet published. The language, the standard collections, `scala.concurrent` and
gears (on goroutines) run with the JVM's semantics, as does most of the JDK that programs use
([coverage](docs/_docs/libraries/jdk.md)). Scala 3 libraries compile from the TASTy in their jars;
cats, upickle, fastparse, pprint, mainargs and os-lib are checked against the JVM. Scala calls Go
packages (bubbletea, gin), and Go calls Scala libraries. Runtime reflection, Java-only libraries
and Scala 2 libraries do not work ([limitations](docs/_docs/user/limitations.md),
[roadmap](docs/roadmap.md)).

## Documentation

**https://julian-avar.github.io/scala-go/docs/**, with the
[API](https://julian-avar.github.io/scala-go/). It is a Scaladoc site, published from `main` by
`.github/workflows/docs.yml`. Build it locally with `./mill docs.site` and open
`out/docs/site.dest/docs/index.html`. Sources are in [docs/_docs](docs/_docs):
[getting started](docs/_docs/getting-started.md), the [user's guide](docs/_docs/user/index.md),
[Go interop](docs/_docs/interop/index.md), [libraries](docs/_docs/libraries/index.md),
[examples](docs/_docs/examples/index.md). The [design document](docs/design.md) explains the
architecture.

## Quick start

Requires JDK 25, Go 1.27 and Mill. Nothing is published yet: `./mill __.publishLocal`
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
| [`examples/basics/`](examples/basics/README.md) | A program built with `ScalaGoModule`: `mill examples.basics.goRun Ada Grace` |
| [`examples/ecosystem/`](examples/ecosystem/README.md) | Popular Scala libraries compiled from TASTy (`mill examples.ecosystem.check`: JVM and Go agree) |
| [`examples/gotui/`](examples/gotui/README.md) | A bubbletea/bubbles/lipgloss terminal app in Scala (`mill examples.gotui.goBuild`, then run it; `mill examples.gotui.check`) |
| [`examples/gin/`](examples/gin/README.md) | A JSON API on gin (`mill examples.gin.goRun`; `mill examples.gin.check`) |
| [`examples/argus/`](examples/argus/README.md) | Configuration reloading with argus (`mill examples.argus.check`) |
| `bench/`             | CPU benchmarks, run on the JVM (`mill bench.run`) and as Go (`mill bench.goRun`) |
| `tests/published/`   | A project using only the published artifacts (`tests/published/check.sh` after `publishLocal`) |
| `tests/cases/`       | End-to-end programs with their expected output                          |
| `docs/`              | Documentation site (`_docs/`, `sidebar.yml`; `mill docs.site`), the design document and the roadmap |

The `stdlib` module produces the Scala standard library's IR (`mill stdlib.ir`).

## Development

`./mill` is Mill's launcher: it downloads the Mill version the build names, so only JDK 25
and Go 1.27 need to be installed. CI (`.github/workflows/ci.yml`) runs the runtime's unit tests
on amd64 and arm64 (Linux, macOS) and everything below.

With Nix, `nix develop` (or direnv's `.envrc`) provides JDK 25, Go and the Mill that `./mill`
then uses, and `nix flake check` runs CI's runtime checks. Mill runs on the system JDK
(`mill-jvm-version: system`).

```sh
mill linker.test                 # end-to-end tests (compile, run on JVM and as Go, compare)
cd runtime && go test ./...      # runtime unit tests
mill examples.basics.goRun       # an example, through ScalaGoModule
mill examples.__.check           # examples that check themselves (bubbletea, Scala libraries)
mill bench.run 3; mill bench.goRun 3   # CPU benchmarks on the JVM and as Go
mill docs.site                   # documentation site with the API: out/docs/site.dest
```

### End-to-end tests

Each directory in `tests/cases/` is a program, entry point `Main`, with an `expected.txt`. The test
compiles it with the plugin and runs it on the JVM; links it to Go, runs `go vet` and `go build`,
and runs the binary. Both outputs must equal `expected.txt`. Optional files change this:

| File                | Effect                                                                  |
|---------------------|-------------------------------------------------------------------------|
| `main.txt`          | names another entry point                                               |
| `stdin.txt`         | standard input of both runs (otherwise closed)                          |
| `env.txt`           | environment variables, `NAME=value` lines (such as a fixed `TZ`)        |
| `exit-status.txt`   | expected exit status (default 0; an uncaught exception exits with 1)    |
| `go-only.txt`       | skips the JVM run (the program calls Go); says why                      |
| `facades.txt`       | Go packages to generate facades of (package `gofacades`); `facades-<name>.txt` adds a set compiled first, in package `<name>`; `facade-notes.txt` lists notes the generator must give; `facade-left-out.txt` members it must leave out |
| `compile-errors.txt` | errors compiling must report, instead of running                       |
| `link-errors.txt`   | errors linking must report, instead of running                          |
| `go-errors.txt`     | errors `go vet` and `go build` must report on the linked code (known bugs only) |
| `missing-apis.txt`  | links with `--missing-apis throw`                                       |
| `race.txt`          | also runs under Go's race detector; `allow-race: <Go function>` lines tolerate known races in third-party libraries |
| `library.txt`       | links the case as a library, used by the Go program in `consumer/`      |
| `<dir>/go.mod`      | a subdirectory with a `go.mod` is a local Go module                     |

Cases named `limits-*` and `known-bugs*` pin what does not work: each limitation in
[Limitations](docs/_docs/user/limitations.md) and the not-planned list in
[design.md](docs/design.md#status), and each known bug in the [roadmap](docs/roadmap.md#known-bugs).
They expect the failure (an error file, or `expected.txt` holding today's Go output, with the
JVM's in comments). A change that lifts a limitation or fixes a bug makes its case fail: move the
check to a case that runs on the JVM and Go, and update the documentation.

| Variable                    | Effect                                                          |
|-----------------------------|-----------------------------------------------------------------|
| `SCALAGO_TEST_ONLY`         | runs only the named cases: `SCALAGO_TEST_ONLY=regex,traits`     |
| `SCALAGO_TEST_PARALLELISM`  | how many cases run at once (they run concurrently)              |
| `SCALAGO_KEEP_WORK=1`       | keeps the work directory of passing cases too; failing ones are always kept, under `/tmp/scalago-<case>-*` |
| `SCALAGO_TEST_GOCACHE_GB`   | size cap of the tests' Go build cache, `~/.cache/scalago-tests/go-build` (default 10). At start, entries unused for a day are pruned, then the least recently used beyond the cap |
| `SCALAGO_LINE_DIRECTIVES=false` | leaves out line directives (the linker's `--line-directives false`), so Go reports positions in the generated code instead of Scala positions |
| `SCALAGO_TRACE=1`           | at run time: keeps all Go frames in stack traces                |
