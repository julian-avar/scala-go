---
title: gin
---

# gin

A JSON API for todos on [gin](https://github.com/gin-gonic/gin)
([source](https://github.com/julian-avar/scala-go/tree/main/examples/gin)): routes with path and
query parameters, middleware, a route group with a token check, JSON responses from Scala data and
request bodies bound from JSON. Facades of `net`, `net/http` and gin are
[generated](../interop/generated-facades.md).

```scala sc:nocompile
val api = router.Group("/api", authorized(token))
api.GET("/todos/:id", c => withTodo(store, c)(todo => c.JSON(http.StatusOK, GoData(todo))))
api.POST("/todos", c =>
  val body = GoPtr.alloc[Any]                      // no Go struct to bind into: bind into `any`
  try
    c.ShouldBindJSON(body)
    GoData.toScala(body.get) match
      case fields: Map[String, Any] @unchecked => ...   // validate, store, answer 201
      case _ => badRequest(c, "expected a JSON object")
  catch case e: GoException => badRequest(c, s"invalid JSON: ${e.getMessage}")
)
```

- **Handlers are Scala lambdas.** `gin.HandlerFunc` is a Go func type, so a Scala function is one.
  The middleware `counting` calls `c.Next()`; `authorized` ends the chain with
  `AbortWithStatusJSON`.
- **Concurrency.** Go's `net/http` serves each connection on its own goroutine, so handlers run
  concurrently. `TodoStore` keeps an immutable `TreeMap` behind `synchronized`;
  `scala.collection.concurrent.TrieMap` does not link.
- **Responses.** [`GoData`](../interop/go-data.md) converts Scala data to what `encoding/json`
  writes: a case class becomes a `map[string]any`, so fields come out sorted by name, under their
  Scala names. Error bodies are `gin.H("error" -> ...)`.
- **Request bodies.** Scala cannot declare a Go struct with `json`/`binding` tags for gin to bind
  into. The body is bound into an `any` (`GoPtr.alloc[Any]`), converted with `GoData.toScala` to a
  `Map[String, Any]` and validated by hand. Bad JSON is a `GoException` from `ShouldBindJSON`.
- **Self-test.** `--self-test` serves on a free port (`net.Listen("tcp", "127.0.0.1:0")` and an
  `http.Server`), sends 14 requests with `java.net.http.HttpClient`, checks each response, and
  shuts down with `server.Shutdown(context.Background())`.

Run it with `./mill examples.gin.goRun` (serves on `:8080`) or `./mill examples.gin.check`. The
[README](https://github.com/julian-avar/scala-go/blob/main/examples/gin/README.md) has `curl`
commands and the limitations, with workarounds.
