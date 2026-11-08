import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.immutable.TreeMap

import gofacades.{gin, http, net}
import scalago.go.{GoData, GoException, GoPtr}
import scalago.gostd.context

/** A todo, as the API stores and returns it. */
final case class Todo(id: Long, title: String, done: Boolean, tags: Seq[String])

/** The todos, shared by the requests that gin serves concurrently (on goroutines of its own). */
final class TodoStore:
  private var todos = TreeMap.empty[Long, Todo]
  private var lastId = 0L

  def all: Seq[Todo] = synchronized(todos.values.toSeq)
  def get(id: Long): Option[Todo] = synchronized(todos.get(id))
  def add(title: String, tags: Seq[String]): Todo = synchronized {
    lastId += 1
    val todo = Todo(lastId, title, done = false, tags)
    todos += todo.id -> todo
    todo
  }
  def update(id: Long)(f: Todo => Todo): Option[Todo] = synchronized {
    todos.get(id).map(f).map { updated =>
      todos += id -> updated
      updated
    }
  }
  def remove(id: Long): Boolean = synchronized {
    val found = todos.contains(id)
    todos -= id
    found
  }

/** A JSON API for todos on gin: routes with path and query parameters, a request body bound
 *  from JSON, middleware and a route group with its own middleware.
 */
object Api:
  /** Requests served, counted by a middleware. */
  val served = AtomicInteger(0)

  /** Counts requests and tags responses with their number; `c.Next()` runs the handlers. */
  def counting: gin.HandlerFunc = c =>
    c.Header("X-Request-Number", served.incrementAndGet().toString)
    c.Next()

  /** Lets requests through with the right token only: an error response ends the chain. */
  def authorized(token: String): gin.HandlerFunc = c =>
    if c.GetHeader("Authorization") != s"Bearer $token" then
      c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H("error" -> "missing or wrong token"))

  def routes(store: TodoStore, token: String): gin.Engine =
    gin.SetMode(gin.ReleaseMode)
    val router = gin.New()
    router.Use(counting, gin.Recovery())

    router.GET("/ping", c => c.JSON(http.StatusOK, gin.H("message" -> "pong")))

    val api = router.Group("/api", authorized(token))
    api.GET("/todos", c =>
      // `?done=true` filters; GoData turns Scala data into what encoding/json understands.
      val todos = c.Query("done") match
        case ""   => store.all
        case done => store.all.filter(_.done == done.toBoolean)
      c.JSON(http.StatusOK, GoData(todos))
    )
    api.GET("/todos/:id", c => withTodo(store, c)(todo => c.JSON(http.StatusOK, GoData(todo))))
    api.POST("/todos", c =>
      // The body is bound into a Go map (gin's ShouldBindJSON), then read as Scala data.
      val body = GoPtr.alloc[Any]
      try
        c.ShouldBindJSON(body)
        GoData.toScala(body.get) match
          case fields: Map[String, Any] @unchecked =>
            fields.get("title") match
              case Some(title: String) if title.trim.nonEmpty =>
                val tags = fields.get("tags") match
                  case Some(ts: Seq[?]) => ts.map(_.toString)
                  case _                => Nil
                c.JSON(http.StatusCreated, GoData(store.add(title.trim, tags)))
              case _ => badRequest(c, "a todo needs a title")
          case _ => badRequest(c, "expected a JSON object")
      catch case e: GoException => badRequest(c, s"invalid JSON: ${e.getMessage}")
    )
    api.PUT("/todos/:id/done", c => withTodo(store, c)(t => c.JSON(http.StatusOK, GoData(store.update(t.id)(_.copy(done = true)).get))))
    api.DELETE("/todos/:id", c => withTodo(store, c) { t =>
      store.remove(t.id)
      c.Status(http.StatusNoContent)
    })
    router.NoRoute(c => c.JSON(http.StatusNotFound, gin.H("error" -> s"no route for ${c.Request.URL.Path}")))
    router

  private def withTodo(store: TodoStore, c: gin.Context)(f: Todo => Unit): Unit =
    c.Param("id").toLongOption.flatMap(store.get) match
      case Some(todo) => f(todo)
      case None       => c.JSON(http.StatusNotFound, gin.H("error" -> s"no todo ${c.Param("id")}"))

  private def badRequest(c: gin.Context, message: String): Unit =
    c.JSON(http.StatusBadRequest, gin.H("error" -> message))

object Main:
  val token = "s3cret"

  def main(args: Array[String]): Unit =
    val router = Api.routes(TodoStore(), token)
    if args.contains("--self-test") then selfTest(router)
    else
      println("serving on http://localhost:8080 (try: curl localhost:8080/ping)")
      router.Run(":8080")

  /** Serves on a free port, sends requests and checks the responses, then shuts down. */
  def selfTest(router: gin.Engine): Unit =
    val listener = net.Listen("tcp", "127.0.0.1:0")
    val base = s"http://${listener.Addr().String()}"
    val server = new http.Server
    server.Handler = router
    val serving = Thread(() =>
      try server.Serve(listener)
      catch case e: GoException if e.error == http.ErrServerClosed => () // after Shutdown
    )
    serving.start()

    val client = HttpClient.newHttpClient()
    var failures = 0
    def expect(method: String, path: String, body: String = "", auth: Boolean = true)(status: Int, expected: String): Unit =
      val publisher = if body.isEmpty then HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
      val builder = HttpRequest.newBuilder(URI.create(base + path)).method(method, publisher)
      if auth then builder.header("Authorization", s"Bearer $token")
      val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
      val ok = response.statusCode == status && response.body == expected
      if !ok then failures += 1
      println(s"${if ok then "ok  " else "FAIL"} $method $path -> ${response.statusCode} ${response.body}")
      if !ok then println(s"     expected $status $expected")

    expect("GET", "/ping", auth = false)(200, """{"message":"pong"}""")
    expect("GET", "/api/todos", auth = false)(401, """{"error":"missing or wrong token"}""")
    expect("GET", "/api/todos")(200, "[]")
    expect("POST", "/api/todos", """{"title": "write docs", "tags": ["work"]}""")(201,
      """{"done":false,"id":1,"tags":["work"],"title":"write docs"}""")
    expect("POST", "/api/todos", """{"title": "buy milk"}""")(201, """{"done":false,"id":2,"tags":[],"title":"buy milk"}""")
    expect("POST", "/api/todos", """{"tags": []}""")(400, """{"error":"a todo needs a title"}""")
    expect("POST", "/api/todos", """{"title": """)(400, """{"error":"invalid JSON: unexpected EOF"}""")
    expect("PUT", "/api/todos/2/done")(200, """{"done":true,"id":2,"tags":[],"title":"buy milk"}""")
    expect("GET", "/api/todos?done=true")(200, """[{"done":true,"id":2,"tags":[],"title":"buy milk"}]""")
    expect("GET", "/api/todos/1")(200, """{"done":false,"id":1,"tags":["work"],"title":"write docs"}""")
    expect("GET", "/api/todos/7")(404, """{"error":"no todo 7"}""")
    expect("DELETE", "/api/todos/1")(204, "")
    expect("GET", "/api/todos")(200, """[{"done":true,"id":2,"tags":[],"title":"buy milk"}]""")
    expect("GET", "/nowhere", auth = false)(404, """{"error":"no route for /nowhere"}""")

    server.Shutdown(context.Background())
    serving.join()
    println(s"${Api.served.get} requests served")
    if failures == 0 then println("all requests answered as expected")
    else
      println(s"$failures unexpected responses")
      sys.exit(1)
