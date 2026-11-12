---
title: Go libraries
---

# Go libraries

To use a Go module from Scala, require it and generate facades for the packages you use.

## Requiring Go modules

```scala sc:nocompile
object app extends ScalaGoModule {
  // Modules from the Go module proxy, as module@version.
  def goRequires = Seq("github.com/google/uuid@v1.6.0")
  // Local modules: directories with their own go.mod.
  def goLocalModules = Task.Sources(moduleDir / "go" / "textstats")
  def goFacadePackages = Seq("github.com/google/uuid", "example.com/textstats")
}
```

- `go` downloads required modules through the module proxy and its cache, so the first build
  needs network access. Go's `go.sum` checks apply.
- A local module is referred to by the module path in its `go.mod` (`example.com/textstats`
  above), also in hand-written facades: `@GoImport("example.com/textstats")`.
- A module may require a newer Go than 1.27. The
  generated `go.mod` then asks for it too, and with Go's default `GOTOOLCHAIN=auto` an older `go`
  downloads that version.
- Generated facades leave out what Scala cannot express; see
  [What is left out](generated-facades.md#what-is-left-out).

## Example: a terminal app with bubbletea

[bubbletea](https://github.com/charmbracelet/bubbletea) (the Elm architecture in the terminal)
and [lipgloss](https://github.com/charmbracelet/lipgloss) (styles). The build requires
`github.com/charmbracelet/bubbletea@v1.3.10` and `github.com/charmbracelet/lipgloss@v1.1.0`, and
lists both packages in `goFacadePackages`.

```scala sc:nocompile
import gofacades.{lipgloss, tea}

final case class Counter(n: Int) extends tea.Model:            // a Go interface, implemented in Scala
  def Init(): tea.Cmd = null
  def Update(msg: tea.Msg): (tea.Model, tea.Cmd) = msg match   // Go's two results are a tuple
    case k: tea.KeyMsg if k.String() == "q" => (this, tea.Quit) // Go functions are Scala functions
    case k: tea.KeyMsg if k.String() == "+" => (Counter(n + 1), null)
    case _                                  => (this, null)
  def View(): String = Counter.style.Render(s"count: $n")

object Counter:
  val style = lipgloss.NewStyle().Bold(true).Foreground(lipgloss.Color("205"))

@main def run(): Unit = tea.NewProgram(Counter(0)).Run()
```

The [gotui example](../examples/gotui.md) is a complete bubbletea application with
[bubbles](https://github.com/charmbracelet/bubbles) components (text input, spinner, key
bindings).

## Working with Go structs

Facades follow the library's Go API closely, and Go values behave as in Go:

- Structs are held by reference. Assigning a field (`input.Placeholder = "..."`) changes the
  struct you hold.
- Methods with value receivers return copies: `val (input2, cmd) = input.Update(msg)`.
- Structs go back to Go as Go code would pass them: by value where their methods allow
  (`tea.KeyMsg`, `lipgloss.Style`), so the library's type switches see them.
- `new T` is Go's zero value of `T`, and `null` is `nil`.

Scala cannot declare Go struct types, so there are no struct tags to bind JSON into. Bind into
`any` and convert with [`GoData`](go-data.md#scala-data-for-go-code), or parse the raw body
with a Scala JSON library. In [gin](https://github.com/gin-gonic/gin) (facades of `net/http`
and `github.com/gin-gonic/gin@v1.12.0`):

```scala sc:nocompile
router.GET("/ping", c => c.JSON(http.StatusOK, gin.H("message" -> "pong")))  // handlers are lambdas
api.GET("/todos/:id", c => c.JSON(http.StatusOK, GoData(store(c.Param("id").toLong))))
api.POST("/todos", c =>
  val body = GoPtr.alloc[Any]
  c.ShouldBindJSON(body)                        // throws GoException on bad JSON
  GoData.toScala(body.get) match
    case fields: Map[String, Any] @unchecked => ...)
```

## cgo

A library that needs cgo needs a C compiler, and rules out `CGO_ENABLED=0` builds unless it has
a fallback without cgo. [argus](https://github.com/agilira/argus) imports `mattn/go-sqlite3`:
with a C compiler, the first build compiles SQLite; with `CGO_ENABLED=0`, it builds a stub, which
suits programs that use no SQLite.

## Examples

| Example                              | Libraries                         | Shows                                         |
|--------------------------------------|-----------------------------------|-----------------------------------------------|
| [gotui](../examples/gotui.md)        | bubbletea, bubbles, lipgloss      | a terminal UI; Go interfaces implemented in Scala |
| [gin](../examples/gin.md)            | gin, `net/http`                   | a JSON API; middleware, binding, `GoData` responses |
| [argus](../examples/argus.md)        | argus, `scalago-gostd`            | configuration reloading; callbacks on Go goroutines |

Each example lists the limitations it met. [Limitations](../user/limitations.md#go-interop)
lists all limitations of Go interop.
