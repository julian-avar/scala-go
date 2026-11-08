# gin

A JSON API for todos on [gin](https://github.com/gin-gonic/gin), Go's most used web framework
([`src/Main.scala`](src/Main.scala)):

- routes with path and query parameters (`/api/todos/:id`, `?done=true`), all HTTP methods;
- Scala lambdas as gin handlers (`gin.HandlerFunc`), a middleware counting requests, and a route
  group with its own middleware (a bearer token check, `AbortWithStatusJSON`);
- JSON responses from Scala data (`GoData(todo)`, `gin.H("error" -> ...)`) and request bodies
  bound from JSON (`ShouldBindJSON`), with error responses for bad input;
- state shared by the requests that gin serves concurrently, on goroutines of its own;
- `net/http`'s `Server` and `net.Listen`, to serve on a free port and shut down.

## Running it

From the repository's root:

```sh
./mill examples.gin.goRun      # serves on http://localhost:8080 until interrupted
curl localhost:8080/ping
curl -H 'Authorization: Bearer s3cret' -d '{"title": "write docs", "tags": ["work"]}' localhost:8080/api/todos
./mill examples.gin.check      # --self-test: serves on a free port, sends 14 requests, checks the answers
```

`check` ends with `all requests answered as expected`.

## Go modules

`goRequires`: `github.com/gin-gonic/gin@v1.12.0`. Facades are generated for `net`, `net/http`
(neither is in `scalago-gostd`) and `github.com/gin-gonic/gin`; `context` comes from
`scalago-gostd`. gin's `go.mod` asks for Go 1.25.0 or later (with Go's default
`GOTOOLCHAIN=auto`, an older `go` downloads it). The first build needs network access.

## Limitations

- **No Go structs with struct tags.** Scala cannot declare a Go struct type, so gin cannot bind a
  request into a Scala class (`ShouldBindJSON(&todo)` with `json:"title" binding:"required"`
  tags). The example binds the body into an `any` (`GoPtr.alloc[Any]`), converts it with
  `GoData.toScala` into a `Map[String, Any]`, and validates it by hand. JSON numbers arrive as
  `Double`s (`encoding/json` decodes them as `float64`). The other option is to parse the raw body
  (`c.GetRawData()`) with a Scala JSON library such as upickle.
- **Responses are maps:** `GoData(todo)` turns the case class into a `map[string]any`, so
  `encoding/json` writes its fields sorted by name (`{"done":false,"id":1,...}`), not in
  declaration order, and their names are the Scala field names (no `json:"..."` renaming). Build
  a `gin.H` for other names.
- **`scala.collection.concurrent.TrieMap` does not link** (its nodes are Java classes of the Scala
  library, which have no TASTy). `TodoStore` keeps an immutable `TreeMap` behind `synchronized`
  instead; `java.util.concurrent.ConcurrentHashMap` would work too.
- **Members left out of the generated facades** (see the `// not generated:` comments in
  `out/examples/gin/goFacadeSources.dest`): `Context.Errors` (an unexported type),
  `Context.Done()` (a channel of `struct{}`), `gin.DebugPrintFunc` and `gin.DebugPrintRouteFunc`
  (a variadic func type, a func with more than 3 parameters), `HandlersChain`'s conversion from a
  slice (funcs inside Go data); in `net/http`, `NoBody`, the context keys, and the fields holding
  funcs (`Server.TLSNextProto`).
- **Runs only as Go:** facade members throw on the JVM.

See also the documentation's [Limitations](../../docs/_docs/user/limitations.md) page.
