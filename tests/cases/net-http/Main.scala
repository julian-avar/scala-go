import java.io.*
import java.net.*
import java.net.http.*
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters.*

/** HttpURLConnection and java.net.http.HttpClient against a small HTTP/1.1 server running in
 *  the program, on the loopback interface.
 */
object Main:
  def attempt(label: String)(body: => Any): Unit =
    try println(s"$label: ${body}")
    catch case e: Exception => println(s"$label: ${e.getClass.getName}: ${e.getMessage}")

  // ---- The server ----------------------------------------------------------------------------

  final case class Request(method: String, path: String, headers: Map[String, String], body: Array[Byte])

  def readLine(in: InputStream): String =
    val sb = new StringBuilder
    var c = in.read()
    while c != -1 && c != '\n' do
      if c != '\r' then sb.append(c.toChar)
      c = in.read()
    if c == -1 && sb.isEmpty then null else sb.toString

  def readRequest(in: InputStream): Request =
    val Array(method, path, _) = readLine(in).split(" ")
    var headers = Map.empty[String, String]
    var line = readLine(in)
    while line != null && line.nonEmpty do
      val colon = line.indexOf(':')
      headers += line.substring(0, colon).trim.toLowerCase -> line.substring(colon + 1).trim
      line = readLine(in)
    val length = headers.get("content-length").map(_.toInt).getOrElse(0)
    val body = new Array[Byte](length)
    var n = 0
    while n < length do n += in.read(body, n, length - n)
    Request(method, path, headers, body)

  def respond(out: OutputStream, status: String, headers: List[(String, String)], body: Array[Byte],
      chunks: List[String] = Nil): Unit =
    val sb = new StringBuilder(s"HTTP/1.1 $status\r\n")
    for (k, v) <- headers do sb.append(s"$k: $v\r\n")
    if chunks.isEmpty then sb.append(s"Content-Length: ${body.length}\r\n")
    else sb.append("Transfer-Encoding: chunked\r\n")
    sb.append("Connection: close\r\n\r\n")
    out.write(sb.toString.getBytes(UTF_8))
    if chunks.isEmpty then out.write(body)
    else
      for c <- chunks do
        val bytes = c.getBytes(UTF_8)
        out.write(s"${bytes.length.toHexString}\r\n".getBytes(UTF_8))
        out.write(bytes)
        out.write("\r\n".getBytes(UTF_8))
      out.write("0\r\n\r\n".getBytes(UTF_8))
    out.flush()

  def handle(socket: Socket): Unit =
    try
      val in = new BufferedInputStream(socket.getInputStream)
      val out = new BufferedOutputStream(socket.getOutputStream)
      val req = readRequest(in)
      val text = List("Content-Type" -> "text/plain; charset=UTF-8")
      req.path match
        case "/hello" =>
          respond(out, "200 OK", text ++ List("X-Multi" -> "a", "X-Multi" -> "b"),
            if req.method == "HEAD" then Array.emptyByteArray else "Hello, world".getBytes(UTF_8))
        case "/echo" =>
          val ua = req.headers.get("user-agent").exists(_.startsWith("Java"))
          val described = s"${req.method} ${req.path} type=${req.headers.getOrElse("content-type", "-")} " +
            s"x-test=${req.headers.getOrElse("x-test", "-")} java-agent=$ua body=${new String(req.body, UTF_8)}"
          respond(out, "200 OK", text, described.getBytes(UTF_8))
        case "/missing" => respond(out, "404 Not Found", text, "no such thing".getBytes(UTF_8))
        case "/fail"    => respond(out, "500 Internal Server Error", text, "it broke".getBytes(UTF_8))
        case "/redirect" => respond(out, "302 Found", List("Location" -> "/hello"), Array.emptyByteArray)
        case "/slow" =>
          Thread.sleep(1000)
          respond(out, "200 OK", text, "late".getBytes(UTF_8))
        case "/chunked" => respond(out, "200 OK", text, null, List("one,", "two,", "three"))
        case "/utf" =>
          respond(out, "200 OK", List("Content-Type" -> "text/plain; charset=ISO-8859-1"), "café".getBytes("ISO-8859-1"))
        case "/big" =>
          respond(out, "200 OK", List("Content-Type" -> "application/octet-stream"),
            Array.tabulate[Byte](1 << 20)(i => (i % 251).toByte))
        case "/lines" => respond(out, "200 OK", text, "first\nsecond\r\nthird".getBytes(UTF_8))
        case _ => respond(out, "400 Bad Request", text, Array.emptyByteArray)
    catch case _: Exception => () // clients that time out go away
    finally socket.close()

  def startServer(): ServerSocket =
    val server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress)
    val acceptor = new Thread(() =>
      try
        while true do
          val s = server.accept()
          val t = new Thread(() => handle(s))
          t.setDaemon(true)
          t.start()
      catch case _: SocketException => () // closed
    )
    acceptor.setDaemon(true)
    acceptor.start()
    server

  // ---- The clients ---------------------------------------------------------------------------

  def main(args: Array[String]): Unit =
    val server = startServer()
    val base = s"http://127.0.0.1:${server.getLocalPort}"
    urlConnection(base)
    httpClient(base)
    server.close()

  def readAll(in: InputStream): String =
    try new String(in.readAllBytes(), UTF_8)
    finally in.close()

  def urlConnection(base: String): Unit =
    println("-- HttpURLConnection")
    def open(path: String) = new URL(base + path).openConnection().asInstanceOf[HttpURLConnection]

    val c = open("/hello")
    println(s"${c.getResponseCode} ${c.getResponseMessage} ${c.getContentType} ${c.getContentLength}")
    println(s"x-multi=${c.getHeaderField("x-multi")} status=${c.getHeaderField(0)} fields=${c.getHeaderFields.get(null)}")
    println(s"${c.getHeaderFieldKey(0)} ${c.getHeaderFields.containsKey("Content-Length")} ${c.getHeaderFieldInt("content-length", -1)}")
    println(readAll(c.getInputStream))
    println(readAll(new URL(base + "/hello").openStream()))

    val post = open("/echo")
    post.setDoOutput(true)
    post.getOutputStream.write("a=1&b=2".getBytes(UTF_8))
    println(s"${post.getRequestMethod} ${post.getResponseCode} ${readAll(post.getInputStream)}")

    val put = open("/echo")
    put.setRequestMethod("PUT")
    put.setRequestProperty("Content-Type", "application/json")
    put.setRequestProperty("X-Test", "yes")
    println(s"${put.getRequestProperty("x-test")} ${put.getRequestProperties.get("X-Test")}")
    put.setDoOutput(true)
    val w = new OutputStreamWriter(put.getOutputStream, UTF_8)
    w.write("{\"k\": \"vä\"}")
    w.close()
    println(s"${put.getResponseCode} ${readAll(put.getInputStream)}")
    attempt("set property after connecting")(put.setRequestProperty("a", "b"))

    val delete = open("/echo")
    delete.setRequestMethod("DELETE")
    println(s"${delete.getResponseCode} ${readAll(delete.getInputStream)}")
    attempt("bad method")(open("/echo").setRequestMethod("BREW"))

    val head = open("/hello")
    head.setRequestMethod("HEAD")
    println(s"HEAD ${head.getResponseCode} ${head.getHeaderField("Content-Type")} [${readAll(head.getInputStream)}]")

    val missing = open("/missing")
    println(s"${missing.getResponseCode} ${missing.getResponseMessage}")
    try missing.getInputStream
    catch case e: FileNotFoundException => println(s"FileNotFoundException for the URL: ${e.getMessage == base + "/missing"}")
    println(readAll(missing.getErrorStream))
    val failing = open("/fail")
    try failing.getInputStream
    catch case e: IOException => println(e.getMessage.replace(base, "BASE"))
    println(s"${failing.getResponseCode} ${readAll(failing.getErrorStream)}")
    println(s"error stream of a success: ${open("/hello").getErrorStream}")

    val redirected = open("/redirect")
    println(s"${redirected.getResponseCode} ${redirected.getURL.getPath} ${readAll(redirected.getInputStream)}")
    val notFollowed = open("/redirect")
    notFollowed.setInstanceFollowRedirects(false)
    println(s"${notFollowed.getResponseCode} ${notFollowed.getHeaderField("Location")} ${notFollowed.getURL.getPath}")

    val chunked = open("/chunked")
    println(s"chunked ${chunked.getContentLength} ${chunked.getHeaderField("Transfer-Encoding")} ${readAll(chunked.getInputStream)}")

    val big = open("/big")
    val data = big.getInputStream.readAllBytes()
    println(s"big ${big.getContentLengthLong} ${data.length} ${data.indices.forall(i => data(i) == (i % 251).toByte)}")

    val slow = open("/slow")
    slow.setReadTimeout(100)
    println(s"timeouts ${slow.getReadTimeout} ${slow.getConnectTimeout}")
    attempt("read timeout")(slow.getResponseCode)
    attempt("again")(slow.getInputStream)

    val closed = new ServerSocket(0, 50, InetAddress.getLoopbackAddress)
    val closedPort = closed.getLocalPort
    closed.close()
    attempt("refused")(new URL(s"http://127.0.0.1:$closedPort/").openConnection().asInstanceOf[HttpURLConnection].getResponseCode)

    val file = File.createTempFile("scalago-net-http", ".txt")
    try
      val fw = new FileWriter(file, UTF_8)
      fw.write("from a file\n")
      fw.close()
      val fc = file.toURI.toURL.openConnection()
      println(s"file ${fc.getContentType} ${fc.getContentLength} ${readAll(fc.getInputStream).trim}")
    finally file.delete()
    attempt("missing file")(new URL("file:/nonexistent/scalago/file").openStream())

  def httpClient(base: String): Unit =
    println("-- HttpClient")
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    println(s"${client.version} ${client.followRedirects} ${client.connectTimeout} ${client.executor}")
    def get(path: String) = HttpRequest.newBuilder(URI.create(base + path)).build()

    val hello = client.send(get("/hello"), HttpResponse.BodyHandlers.ofString())
    println(s"${hello.statusCode} ${hello.body} ${hello.version}")
    println(s"${hello.headers.firstValue("content-type")} ${hello.headers.allValues("X-MULTI")} ${hello.headers.firstValueAsLong("content-length")}")
    println(s"${hello.request.method} ${hello.uri.getPath} ${hello.previousResponse}")
    println(hello.toString.replace(base, "BASE"))

    val post = HttpRequest.newBuilder(URI.create(base + "/echo"))
      .header("Content-Type", "text/plain")
      .header("X-Test", "posted")
      .POST(HttpRequest.BodyPublishers.ofString("ünïcode body"))
      .build()
    println(s"${post.method} ${post.bodyPublisher.get.contentLength} ${post.headers.firstValue("x-test")} ${post.toString.replace(base, "BASE")}")
    println(client.send(post, HttpResponse.BodyHandlers.ofString()).body)
    val put = HttpRequest.newBuilder(URI.create(base + "/echo")).PUT(HttpRequest.BodyPublishers.ofByteArray(Array[Byte](65, 66, 67))).build()
    println(new String(client.send(put, HttpResponse.BodyHandlers.ofByteArray()).body, UTF_8))
    val delete = HttpRequest.newBuilder(URI.create(base + "/echo")).DELETE().build()
    println(client.send(delete, HttpResponse.BodyHandlers.ofString()).body)
    val patch = HttpRequest.newBuilder(URI.create(base + "/echo")).method("PATCH", HttpRequest.BodyPublishers.ofString("p")).build()
    println(client.send(patch, HttpResponse.BodyHandlers.ofString()).body)

    val missing = client.send(get("/missing"), HttpResponse.BodyHandlers.ofString())
    println(s"${missing.statusCode} ${missing.body}")
    val redirect = client.send(get("/redirect"), HttpResponse.BodyHandlers.ofString())
    println(s"never: ${redirect.statusCode} ${redirect.headers.firstValue("location")}")
    val following = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    val followed = following.send(get("/redirect"), HttpResponse.BodyHandlers.ofString())
    println(s"normal: ${followed.statusCode} ${followed.uri.getPath} ${followed.body}")

    println(s"utf: ${client.send(get("/utf"), HttpResponse.BodyHandlers.ofString()).body}")
    println(s"chunked: ${client.send(get("/chunked"), HttpResponse.BodyHandlers.ofString()).body}")
    val stream = client.send(get("/big"), HttpResponse.BodyHandlers.ofInputStream())
    val data = stream.body.readAllBytes()
    stream.body.close()
    println(s"stream: ${stream.statusCode} ${data.length} ${data.indices.forall(i => data(i) == (i % 251).toByte)}")
    val lines = client.send(get("/lines"), HttpResponse.BodyHandlers.ofLines())
    println(s"lines: ${lines.body.toList.asScala}")
    println(s"discarding: ${client.send(get("/hello"), HttpResponse.BodyHandlers.discarding()).body}")
    println(s"replacing: ${client.send(get("/hello"), HttpResponse.BodyHandlers.replacing("instead")).body}")
    val total = new AtomicLong
    client.send(get("/big"), HttpResponse.BodyHandlers.ofByteArrayConsumer(o => o.ifPresent(b => total.addAndGet(b.length))))
    println(s"consumer: ${total.get}")
    val mapped = client.send(get("/hello"),
      _ => HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.ofString(UTF_8), (s: String) => s.length))
    println(s"mapped: ${mapped.body}")

    val slow = HttpRequest.newBuilder(URI.create(base + "/slow")).timeout(Duration.ofMillis(100)).build()
    attempt("timeout")(client.send(slow, HttpResponse.BodyHandlers.ofString()))

    val futures = (1 to 5).map(i => client.sendAsync(get("/echo"), HttpResponse.BodyHandlers.ofString()).thenApply(r => s"$i ${r.body}"))
    futures.foreach(f => println(f.join()))

    val closed = new ServerSocket(0, 50, InetAddress.getLoopbackAddress)
    val closedPort = closed.getLocalPort
    closed.close()
    val refused = HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$closedPort/")).build()
    attempt("refused")(client.send(refused, HttpResponse.BodyHandlers.ofString()))
    try client.sendAsync(refused, HttpResponse.BodyHandlers.ofString()).join()
    catch case e: Exception => println(s"async refused: ${e.getClass.getName} ${e.getCause}")

    attempt("no uri")(HttpRequest.newBuilder().build())
    attempt("bad scheme")(HttpRequest.newBuilder(URI.create("ftp://example.com/")))
    attempt("restricted header")(HttpRequest.newBuilder(URI.create(base)).header("Host", "x"))
    attempt("bad header")(HttpRequest.newBuilder(URI.create(base)).header("a b", "x"))
    attempt("odd headers")(HttpRequest.newBuilder(URI.create(base)).headers("a"))
    attempt("zero timeout")(HttpRequest.newBuilder(URI.create(base)).timeout(Duration.ZERO))
