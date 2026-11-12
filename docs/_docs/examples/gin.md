---
title: gin
---

# gin

A JSON API for todos on [gin](https://github.com/gin-gonic/gin)
([source](https://github.com/julian-avar/scala-go/tree/main/examples/gin)): routes with path and
query parameters, middleware written as Scala lambdas, a route group with a token check, JSON
responses from [`GoData`](../interop/go-data.md#scala-data-for-go-code) and request bodies bound
into `any`. Handlers run concurrently on `net/http`'s goroutines; the store is an immutable
`TreeMap` behind `synchronized`. gin's facades are [generated](../interop/generated-facades.md);
`net` and `net/http` come from [`scalago-gostd`](../libraries/gostd.md).

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

Run it with `./mill examples.gin.goRun` (serves on `:8080`) or `./mill examples.gin.check`
(`--self-test`: serves on a free port and checks 14 requests sent with `java.net.http.HttpClient`).

Limitations:

- No binding into a Scala class; bound JSON numbers are `Double`s. Parsing `c.GetRawData()` with
  upickle also works.
- Response fields come out sorted by name, under their Scala names; build a `gin.H` for others.
- Left out of the generated facades: `gin.DebugPrintFunc` (a variadic func type),
  `gin.DebugPrintRouteFunc` (more than 3 parameters), `HandlersChain`'s conversion (funcs inside
  Go data), the setter of `Context.Errors` (an unexported type).
