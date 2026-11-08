---
title: Go libraries
---

# Go libraries

Any Go module can be used from Scala: require the module, and generate facades for the packages
you use.

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

`go` downloads required modules through the module proxy and its cache, so the first build
needs network access. Go's `go.sum` checks apply as for any Go build.

A local module is referred to by the module path of its `go.mod` (`example.com/textstats`
above). Hand-written facades of it use that path too: `@GoImport("example.com/textstats")`.

Programs that use Go libraries run only as Go, not on the JVM.

## Example: a terminal app with bubbletea

[bubbletea](https://github.com/charmbracelet/bubbletea) (the Elm architecture in the terminal)
and [lipgloss](https://github.com/charmbracelet/lipgloss) (styles):

```scala sc:nocompile
object app extends ScalaGoModule {
  def goRequires = Seq(
    "github.com/charmbracelet/bubbletea@v1.3.10",
    "github.com/charmbracelet/lipgloss@v1.1.0"
  )
  def goFacadePackages = Seq("github.com/charmbracelet/bubbletea", "github.com/charmbracelet/lipgloss")
}
```

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

The repository's `examples/gotui/` is a complete bubbletea application with
[bubbles](https://github.com/charmbracelet/bubbles) components (text input, spinner, key
bindings).

## Working with Go structs

Facades of Go libraries follow the library's Go API closely. A few rules about Go values:

- Go structs are held by reference. Assigning a field (`input.Placeholder = "..."`) changes the
  struct you hold.
- Methods with value receivers return copies, as in Go: `val (input2, cmd) = input.Update(msg)`.
- Structs go back to Go as Go code would pass them: by value where their methods allow
  (`tea.KeyMsg`, `lipgloss.Style`), so the library's type switches see them.
- `new T` is Go's zero value of `T`, and `null` is `nil`.

<!-- More examples of Go libraries (web servers, command-line parsing, ...) go here. -->
