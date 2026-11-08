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

The [gotui example](../examples/gotui.md) is a complete bubbletea application with
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

## Example: a JSON API with gin

[gin](https://github.com/gin-gonic/gin) (the [gin example](../examples/gin.md)):

```scala sc:nocompile
object app extends ScalaGoModule {
  def mvnDeps = Seq(mvn"io.github.scalago::scalago-gostd:${scalaGoVersion()}")
  def goRequires = Seq("github.com/gin-gonic/gin@v1.12.0")
  def goFacadePackages = Seq("net/http", "github.com/gin-gonic/gin")
}
```

```scala sc:nocompile
val router = gin.New()
router.Use(c => { c.Header("X-Served-By", "scala"); c.Next() })
router.GET("/ping", c => c.JSON(http.StatusOK, gin.H("message" -> "pong")))
val api = router.Group("/api", c =>
  if c.GetHeader("Authorization") != "Bearer s3cret" then
    c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H("error" -> "missing or wrong token")))
api.GET("/todos/:id", c => store.get(c.Param("id").toLong) match
  case Some(todo) => c.JSON(http.StatusOK, GoData(todo))   // a case class as JSON
  case None       => c.JSON(http.StatusNotFound, gin.H("error" -> "no such todo")))
api.POST("/todos", c =>
  val body = GoPtr.alloc[Any]
  c.ShouldBindJSON(body)                                    // throws GoException on bad JSON
  GoData.toScala(body.get) match
    case fields: Map[String, Any] @unchecked => ...)
router.Run(":8080")
```

Scala cannot declare Go struct types, so struct tags for binding are not available: bind JSON
into `any` as above, or parse the raw body with a Scala JSON library.

## Example: configuration reloading with argus

[argus](https://github.com/agilira/argus) (the [argus example](../examples/argus.md))
watches configuration files:

```scala sc:nocompile
val settings = argus.Setup("app")
  .Defaults(GoData.map("server.port" -> 8080, "log.level" -> "info"))
  .File("service.json")
  .MaxStaleness(time.ParseDuration("100ms"))
  .OnReload((s, change) => current.set(ServiceConfig.from(s)))   // on argus's goroutine
  .OnError(err => println(s"rejected: ${err.Error()}"))          // the last good revision stays
  .Start()
```

argus imports `mattn/go-sqlite3`, a cgo package: with a C compiler installed, the first build
compiles SQLite. With `CGO_ENABLED=0` it builds a stub of it instead, which suits programs that
use no SQLite.

## Limitations

- Programs that use Go libraries run only as Go.
- Go modules may require a newer Go than scala-go's minimum (gin and argus need Go 1.25), and
  the generated `go.mod` then asks for it too; with Go's default `GOTOOLCHAIN=auto`, an older
  `go` downloads that version.
- No Go structs from Scala, so no struct tags: bind into `any` and use `GoData`, or read the raw
  data and parse it in Scala.
- Generated facades leave out what Scala cannot express, with a comment saying why (see
  [Generated facades](generated-facades.md#what-is-left-out)).
- Libraries that need cgo need a C compiler, and rule out `CGO_ENABLED=0` builds unless they have
  a fallback without cgo.

The [examples](../examples/index.md) list the limitations each met, and
[Limitations](../user/limitations.md#go-interop) all limitations of Go interop.
