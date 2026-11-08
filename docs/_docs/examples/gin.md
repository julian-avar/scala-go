---
title: gin
---

# gin

A JSON API for todos on [gin](https://github.com/gin-gonic/gin)
([source](https://github.com/julian-avar/scala-go/tree/main/examples/gin)): routes with path and
query parameters, middleware, a route group with a token check, JSON responses from Scala data and
request bodies bound from JSON. Handlers are Scala lambdas, run concurrently on gin's goroutines.

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

## Running it

```sh
./mill examples.gin.goRun      # serves on :8080
./mill examples.gin.check      # serves on a free port, sends itself 14 requests, checks the answers
```

Go modules: gin v1.12.0 (Go 1.25.0 or later); facades of `net`, `net/http` and gin are generated.

## Limitations

- Scala cannot declare Go structs with `json`/`binding` tags, so gin cannot bind a request into a
  Scala class: the body is bound into `any` and converted with `GoData.toScala`, then validated by
  hand. JSON numbers arrive as `Double`s.
- `GoData(todo)` is a Go map, so `encoding/json` writes the fields sorted by name, under their
  Scala names.
- `scala.collection.concurrent.TrieMap` does not link (its nodes are Java classes of the Scala
  library): the store is an immutable `TreeMap` behind `synchronized`.
- The generated facades leave out `Context.Errors` (an unexported type), `Context.Done()` (a
  channel of `struct{}`), gin's debug-print hooks (func types they cannot express) and, in
  `net/http`, `NoBody` and the fields holding funcs.
- Facades throw on the JVM, so it runs only as Go.

More in the example's
[README](https://github.com/julian-avar/scala-go/blob/main/examples/gin/README.md).
