# gin

A JSON API for todos on [gin](https://github.com/gin-gonic/gin): routes, middleware, a route group
with a token check, JSON in and out. Source: [`src/Main.scala`](src/Main.scala). Explained in the
documentation's [gin](../../docs/_docs/examples/gin.md) page.

## Running it

From the repository's root:

```sh
./mill examples.gin.goRun      # serves on http://localhost:8080 until interrupted
curl localhost:8080/ping
curl -H 'Authorization: Bearer s3cret' -d '{"title": "write docs", "tags": ["work"]}' localhost:8080/api/todos
./mill examples.gin.check      # --self-test: serves on a free port, sends 14 requests, checks the answers
```

`check` ends with `all requests answered as expected`.

Go modules (`goRequires`): `github.com/gin-gonic/gin@v1.12.0`. Facades are generated for `net`,
`net/http` (neither is in `scalago-gostd`) and gin; `context` comes from `scalago-gostd`. The first
build needs network access.

## Limitations

- **No Go structs with struct tags,** so gin cannot bind a request into a Scala class
  (`ShouldBindJSON(&todo)` with `json:"title" binding:"required"`). Workaround: bind into an `any`
  (`GoPtr.alloc[Any]`), convert with `GoData.toScala` to a `Map[String, Any]`, validate by hand.
  JSON numbers arrive as `Double`s (`float64`). Or parse `c.GetRawData()` with a Scala JSON
  library such as upickle.
- **Responses are maps:** `GoData(todo)` is a `map[string]any`, so `encoding/json` writes fields
  sorted by name (`{"done":false,"id":1,...}`) under their Scala names. Build a `gin.H` for other
  names.
- **`scala.collection.concurrent.TrieMap` does not link** (its nodes are Java classes). `TodoStore`
  uses an immutable `TreeMap` behind `synchronized`; `java.util.concurrent.ConcurrentHashMap` works
  too.
- **Left out of the generated facades** (`// not generated:` comments in
  `out/examples/gin/goFacadeSources.dest`): `Context.Errors` (an unexported type), `Context.Done()`
  (a channel of `struct{}`), `gin.DebugPrintFunc` (a variadic func type), `gin.DebugPrintRouteFunc`
  (a func with more than 3 parameters), `HandlersChain`'s conversion (funcs inside Go data); in
  `net/http`, `NoBody`, the context keys, and fields holding funcs (`Server.TLSNextProto`).
- **Runs only as Go:** facade members throw on the JVM.

All limitations: [docs/_docs/user/limitations.md](../../docs/_docs/user/limitations.md).
