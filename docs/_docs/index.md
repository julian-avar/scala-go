# scala-go

scala-go compiles Scala 3 programs to native executables through Go.

- A Scala compiler plugin writes IR (`.goir`) next to the `.class` files.
- A whole-program linker turns the program, its Scala libraries, the Scala standard library and
  javalib (a Java standard library written in Scala) into a Go module.
- `go build` compiles that module into one executable that needs no JVM.

Programs keep the JVM's semantics: `String` is `java.lang.String`, exceptions have stack traces,
`java.util`, `java.time` and `scala.concurrent` behave as on the JVM. They also get Go's
strengths: millisecond startup, cross-compilation, goroutines and direct calls to Go libraries.

```scala sc:nocompile
import gofacades.{lipgloss, tea}

final case class Counter(n: Int) extends tea.Model:   // a Go interface, implemented in Scala
  def Init(): tea.Cmd = null
  def Update(msg: tea.Msg): (tea.Model, tea.Cmd) = msg match
    case k: tea.KeyMsg if k.String() == "q" => (this, tea.Quit)
    case k: tea.KeyMsg if k.String() == "+" => (Counter(n + 1), null)
    case _                                  => (this, null)
  def View(): String = lipgloss.NewStyle().Bold(true).Render(s"count: $n")
```

## What works

| Area            | Coverage                                                                                   |
|-----------------|--------------------------------------------------------------------------------------------|
| Language        | classes, traits, objects, enums, pattern matching, lambdas, `lazy val`, exceptions, `synchronized`, collections, `Future` |
| JDK             | `java.lang`, `java.util` (with `concurrent`, streams, regex), `java.io`, `java.nio`, `java.math`, `java.time`, `java.net` (sockets, HTTP), subprocesses, digests, `java.util.zip`: see [The JDK](libraries/jdk.md) |
| Scala libraries | compiled from the TASTy in their jars; cats, upickle, fastparse, pprint, mainargs, os-lib and gears are checked against the JVM |
| Go interop      | Scala calls Go packages (bubbletea, gin, ...); Go calls Scala libraries                    |

Not supported: runtime reflection beyond `getClass`, Java code in dependencies, Scala 2
libraries. See [Limitations](user/limitations.md) and
[Differences from the JVM](user/differences.md).

## Where to go next

- [Getting started](getting-started.md): a first program, run as Go and on the JVM.
- [Build setup](user/build.md): the Mill module, its tasks and settings.
- [Calling Go](interop/facades.md) and [Calling Scala from Go](interop/exports.md).
- [Examples](examples/index.md): complete programs with bubbletea, gin and argus.
- API: [scalago.go](scalago.go) (facades, Go data types), [scalago.gears](scalago.gears),
  [scalago.gostd](scalago.gostd).
- [Specification](spec/index.md): what scala-go guarantees.
- [Design](contributing/design.md): for contributors.
