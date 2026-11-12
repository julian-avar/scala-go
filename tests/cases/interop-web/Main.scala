import gofacades.web
import scalago.go.*

final case class User(id: Long, name: String, roles: Seq[String], manager: Option[String])

object Main:
  def main(args: Array[String]): Unit =
    // Promoted methods of the embedded RouterGroup, variadic named func types, pointer receivers.
    val engine = web.New()
    engine.Use(c => { c.Set("seen", "yes"); c.Next() })
    engine.GET("/ping", c => c.JSON(200, web.H("message" -> "pong", "n" -> 3, "nested" -> web.H("ok" -> true))))
    val users = engine.Group("/users", c => if c.Param("id") == "0" then c.AbortWithStatusJSON(403, web.H("error" -> "no")))
    users.GET("/:id", c => c.JSON(200, GoData(User(c.Param("id").toLong, "ana", Seq("admin", "dev"), None))))
    users.POST("/:id", c =>
      val body = GoPtr.alloc[Any]
      try
        c.BindJSON(body)
        c.JSON(201, GoData(GoData.toScala(body.get)))
      catch case e: GoException => c.String(400, "bad body: %s", e.getMessage)
    )
    engine.GET("/seen", c => c.String(200, "seen=%s path=%s", c.GetString("seen"), c.Path))
    println(engine.Serve("GET", "/ping", ""))
    println(engine.Serve("GET", "/users/7", ""))
    println(engine.Serve("GET", "/users/0", ""))
    println(engine.Serve("POST", "/users/7", """{"name": "bo", "tags": ["x", 1, 2.5, null], "admin": false}"""))
    println(engine.Serve("POST", "/users/7", """{"name": """))
    println(engine.Serve("GET", "/seen", ""))
    println(engine.Serve("GET", "/missing", ""))

    // Handlers run on goroutines: Scala callbacks from Go code that Go started.
    val paths = (1 to 8).map(i => s"GET /users/$i")
    println(engine.ServeAll(paths*).toSeq.map(_.toJavaString.take(14)).mkString(" | "))

    // Composite literals of named slice and map types.
    val chain = web.HandlersChain(c => c.Next(), c => c.String(200, "last of %d", 2))
    println(web.Run(chain, "/"))
    println(chain.Last() != null)
    val params = web.Params({ val p = new web.Param; p.Key = "a"; p.Value = "1"; p })
    println(params.ByName("a"))
    println(web.H("b" -> 1, "a" -> 2, "b" -> 3).Keys().toSeq.map(_.toJavaString))
    println(web.Describe(web.H()))

    // GoData: Scala data as Go code sees it.
    println(web.Describe(GoData(User(1, "ana", Nil, Some("cy")))))
    println(web.Describe(GoData(Seq(1, 2L, 'x', 1.5, "s", None, Map(3 -> List(true))))))
    println(web.Describe(GoData.map("k" -> Vector(1, 2))))

    // Callbacks with error results: exceptions become the errors, and Go errors exceptions.
    val group = new web.Group
    group.Go(() => ())
    group.Go(() => throw GoException(web.ErrNotFound))
    group.Go(() => throw IllegalStateException("broken"))
    try
      group.Wait()
      println("no errors")
    catch case e: GoException => println(s"errors: ${e.getMessage}")

    val loader: web.Loader = name =>
      name match
        case "missing" => throw GoException(web.ErrNotFound)
        case "bad"     => throw IllegalArgumentException("unreadable")
        case _         => web.H(name -> name.length)
    println(web.LoadAll(loader, "a", "missing", "abc").Keys().toSeq.map(_.toJavaString))
    try web.LoadAll(loader, "a", "bad")
    catch case e: GoException => println(s"load failed: ${e.getMessage}")

    // Go funcs with an error result or several results, returned to Scala.
    val binary = web.Parser(2)
    println(binary("1011"))
    try binary("12")
    catch case e: GoException => println(s"parse failed: ${e.getMessage}")
    val (k, v) = web.Split("=")("key=value")
    println(s"$k -> $v")
