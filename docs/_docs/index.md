# scala-go

scala-go compiles Scala 3 programs to native executables through Go. A Scala compiler plugin
emits an intermediate representation next to the `.class` files, and a whole-program linker
turns the program, its Scala libraries, the Scala standard library and a Java standard library
written in Scala into a Go module that `go build` compiles.

Programs keep the JVM's semantics: strings are `java.lang.String`, exceptions have stack
traces, `java.util`, `java.time` and `scala.concurrent` work as on the JVM. They also get what
Go offers: a single static binary that starts in milliseconds, cross-compilation, goroutines,
and direct access to Go libraries.

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

- **The language:** classes, traits, objects, enums, pattern matching, lambdas, `lazy val`,
  exceptions, `synchronized`, the standard collections, `Future`.
- **The JDK** most programs use: `java.lang`, `java.util` (with `concurrent`, streams, regex),
  `java.io`, `java.nio`, `java.math`, `java.time`, `java.net` (sockets, HTTP), subprocesses,
  digests and `java.util.zip`. See [The JDK](libraries/jdk.md).
- **Scala libraries**, compiled from the TASTy in their published jars: cats, upickle,
  fastparse, pprint, mainargs, os-lib and gears are checked against the JVM.
- **Go interop** both ways: Scala calls Go packages, including popular Go libraries such as
  bubbletea; Go calls Scala libraries.

What does not work: runtime reflection (beyond `getClass`), Java code in dependencies, and
Scala 2 libraries. [Differences from the JVM](user/differences.md) lists the details, and
[Limitations](user/limitations.md) all known limitations.

## Where to go next

- [Getting started](getting-started.md): a first program, run as Go and on the JVM.
- [Build setup](user/build.md): the Mill module and its settings.
- [Calling Go](interop/facades.md) and [Calling Scala from Go](interop/exports.md).
- [Examples](examples/index.md): complete programs, with bubbletea, gin and argus.
- The API of [scalago.go](scalago.go) (facades and Go data types), [scalago.gears](scalago.gears)
  and [scalago.gostd](scalago.gostd).
- The [specification](spec/index.md) of what scala-go guarantees, and the
  [design](contributing/design.md) document for contributors.
