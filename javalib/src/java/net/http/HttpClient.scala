package java.net.http

import java.io.IOException
import java.net.{ConnectException, HttpExchanges, URI}
import java.nio.ByteBuffer
import java.time.Duration
import java.util.{ArrayList, List as JList, Optional}
import java.util.concurrent.{CompletableFuture, ExecutionException, Executor, ExecutorService, Executors, Flow, ThreadFactory}
import java.util.concurrent.atomic.AtomicInteger

import scala.annotation.static

import scalago.runtime.{Net, OS}

abstract class HttpClient protected () extends AutoCloseable:
  def connectTimeout(): Optional[Duration]
  def followRedirects(): HttpClient.Redirect
  def executor(): Optional[Executor]
  def version(): HttpClient.Version
  def send[T <: AnyRef](request: HttpRequest, responseBodyHandler: HttpResponse.BodyHandler[T]): HttpResponse[T]
  def sendAsync[T <: AnyRef](request: HttpRequest, responseBodyHandler: HttpResponse.BodyHandler[T])
      : CompletableFuture[HttpResponse[T]]
  def sendAsync[T <: AnyRef](request: HttpRequest, responseBodyHandler: HttpResponse.BodyHandler[T],
      pushPromiseHandler: HttpResponse.PushPromiseHandler[T]): CompletableFuture[HttpResponse[T]]
  def shutdown(): Unit = ()
  def awaitTermination(duration: Duration): Boolean = true
  def isTerminated(): Boolean = false
  def shutdownNow(): Unit = shutdown()
  def close(): Unit = shutdown()

object HttpClient:
  def newHttpClient(): HttpClient = newBuilder().build()
  def newBuilder(): Builder = new ClientBuilder()

  trait Builder:
    def connectTimeout(duration: Duration): Builder
    def executor(executor: Executor): Builder
    def followRedirects(policy: Redirect): Builder
    def version(version: Version): Builder
    def priority(priority: Int): Builder
    def build(): HttpClient

  final class Version private (name: String, ordinal: Int) extends scalago.javalib.lang.Enum[Version](name, ordinal)

  object Version:
    @static val HTTP_1_1: Version = new Version("HTTP_1_1", 0)
    @static val HTTP_2: Version = new Version("HTTP_2", 1)

    @static def values(): Array[Version] = Array(HTTP_1_1, HTTP_2)

    @static def valueOf(name: String): Version =
      if name == null then throw new NullPointerException("Name is null")
      values().find(_.name() == name).getOrElse(
        throw new IllegalArgumentException("No enum constant java.net.http.HttpClient.Version." + name))

  final class Redirect private (name: String, ordinal: Int) extends scalago.javalib.lang.Enum[Redirect](name, ordinal)

  object Redirect:
    @static val NEVER: Redirect = new Redirect("NEVER", 0)
    @static val ALWAYS: Redirect = new Redirect("ALWAYS", 1)
    @static val NORMAL: Redirect = new Redirect("NORMAL", 2)

    @static def values(): Array[Redirect] = Array(NEVER, ALWAYS, NORMAL)

    @static def valueOf(name: String): Redirect =
      if name == null then throw new NullPointerException("Name is null")
      values().find(_.name() == name).getOrElse(
        throw new IllegalArgumentException("No enum constant java.net.http.HttpClient.Redirect." + name))

  private final class ClientBuilder extends Builder:
    var connectTimeoutValue: Duration = null
    var executorValue: Executor = null
    var redirect: Redirect = Redirect.NEVER
    var versionValue: Version = Version.HTTP_2

    def connectTimeout(duration: Duration): Builder =
      java.util.Objects.requireNonNull(duration)
      if duration.isNegative || duration.isZero then throw new IllegalArgumentException("Invalid duration: " + duration)
      connectTimeoutValue = duration
      this

    def executor(executor: Executor): Builder =
      executorValue = java.util.Objects.requireNonNull(executor)
      this

    def followRedirects(policy: Redirect): Builder =
      redirect = java.util.Objects.requireNonNull(policy)
      this

    def version(version: Version): Builder =
      versionValue = java.util.Objects.requireNonNull(version)
      this

    def priority(priority: Int): Builder =
      if priority < 1 || priority > 256 then throw new IllegalArgumentException("priority must be between 1 and 256")
      this

    def build(): HttpClient = new ClientImpl(this)

  private val clientIds = new AtomicInteger()

  /** Saturating conversion of a timeout to milliseconds (0: none). */
  private def millis(d: Duration): Int =
    if d == null then 0 else d.toMillis.max(1L).min(Int.MaxValue.toLong).toInt

  /** The client, on Go's net/http (`scalago.runtime.Net.httpSend`): requests are HTTP/1.1
   *  whatever the version asked for. `sendAsync` runs `send` on the executor (by default a
   *  cached pool of daemon threads).
   */
  private final class ClientImpl(b: ClientBuilder) extends HttpClient:
    private val timeoutValue = b.connectTimeoutValue
    private val executorValue = b.executorValue
    private val redirect = b.redirect
    private val versionValue = b.versionValue
    private val id = clientIds.incrementAndGet()
    @volatile private var shut = false
    private lazy val defaultExecutor: ExecutorService =
      val workers = new AtomicInteger()
      Executors.newCachedThreadPool(new ThreadFactory:
        def newThread(r: Runnable): Thread =
          val t = new Thread(r, s"HttpClient-$id-Worker-${workers.getAndIncrement()}")
          t.setDaemon(true)
          t
      )

    def connectTimeout(): Optional[Duration] = Optional.ofNullable(timeoutValue)
    def followRedirects(): Redirect = redirect
    def executor(): Optional[Executor] = Optional.ofNullable(executorValue)
    def version(): Version = versionValue

    def send[T <: AnyRef](request: HttpRequest, handler: HttpResponse.BodyHandler[T]): HttpResponse[T] =
      java.util.Objects.requireNonNull(request)
      java.util.Objects.requireNonNull(handler)
      if shut then throw new IOException("closed")
      val fields = new ArrayList[String]()
      request.headers().map().forEach((name, values) => values.forEach { v => fields.add(name); fields.add(v) })
      if request.headers().firstValue("User-Agent").isEmpty() then
        fields.add("User-Agent")
        fields.add("Java-http-client/" + System.getProperty("java.version"))
      val body = request.bodyPublisher().map(p => HttpRequest.collect(p)).orElse(null)
      val policy =
        if redirect == Redirect.ALWAYS then Net.RedirectAlways
        else if redirect == Redirect.NORMAL then Net.RedirectNormal
        else Net.RedirectNever
      val exchange = Net.httpSend(request.method(), request.uri().toString, fields.toArray(new Array[String](0)), body,
        millis(timeoutValue), millis(request.timeout().orElse(null)), policy, 5)
      if OS.isError(exchange) then throw failure(exchange)
      val status = Net.httpStatus(exchange)
      val responseHeaders = HttpHeaders.ofPairs(HttpExchanges.headers(exchange))
      val responseVersion = if Net.httpVersion(exchange) == 20 then Version.HTTP_2 else Version.HTTP_1_1
      val finalURI = URI.create(Net.httpURL(exchange))
      val info = new HttpResponse.ResponseInfo:
        def statusCode(): Int = status
        def headers(): HttpHeaders = responseHeaders
        def version(): Version = responseVersion
      val subscriber =
        try handler.apply(info)
        catch
          case e: Throwable =>
            Net.close(exchange)
            throw e
      new BodyPump(exchange, subscriber).start()
      val value =
        try subscriber.getBody().toCompletableFuture().get()
        catch
          case e: ExecutionException =>
            e.getCause() match
              case io: IOException => throw io
              case other           => throw new IOException(other)
      val finalRequest =
        if finalURI == request.uri() then request
        else HttpRequest.newBuilder(request, (_, _) => true).uri(finalURI).build()
      new ResponseImpl(status, finalRequest, responseHeaders, value, finalURI, responseVersion)

    def sendAsync[T <: AnyRef](request: HttpRequest, handler: HttpResponse.BodyHandler[T]): CompletableFuture[HttpResponse[T]] =
      java.util.Objects.requireNonNull(request)
      java.util.Objects.requireNonNull(handler)
      val executor = if executorValue != null then executorValue else defaultExecutor
      CompletableFuture.supplyAsync(() => send(request, handler), executor)

    def sendAsync[T <: AnyRef](request: HttpRequest, handler: HttpResponse.BodyHandler[T],
        pushPromiseHandler: HttpResponse.PushPromiseHandler[T]): CompletableFuture[HttpResponse[T]] =
      sendAsync(request, handler)

    override def shutdown(): Unit =
      shut = true
      if executorValue == null then defaultExecutor.shutdown()

    override def isTerminated(): Boolean = shut

    override def toString(): String = s"java.net.http.HttpClient@$id"

  /** The exception of a failed exchange. */
  private def failure(error: Object): IOException =
    val message = Net.errorMessage(error)
    Net.errorKind(error) match
      case Net.ConnectTimeout => new HttpConnectTimeoutException("HTTP connect timed out")
      case Net.Timeout        => new HttpTimeoutException("request timed out")
      case Net.Refused | Net.UnknownHost | Net.HostUnreachable | Net.NetworkUnreachable =>
        val e = new ConnectException()
        e.initCause(new ConnectException())
        e
      case Net.TLS => new javax.net.ssl.SSLHandshakeException(message)
      case _       => new IOException(message)

  private final class ResponseImpl[T <: AnyRef](status: Int, req: HttpRequest, hdrs: HttpHeaders, value: T, finalURI: URI,
      ver: Version) extends HttpResponse[T]:
    def statusCode(): Int = status
    def request(): HttpRequest = req
    def previousResponse(): Optional[HttpResponse[T]] = Optional.empty()
    def headers(): HttpHeaders = hdrs
    def body(): T = value
    def uri(): URI = finalURI
    def version(): Version = ver
    override def toString(): String = "(" + req.method() + " " + req.uri() + ") " + status

  /** Delivers a response's body to its subscriber, in chunks as the subscriber requests them.
   *  The chunks are read in the thread that requests them (or the one already delivering), so
   *  `send` needs no thread of its own: `ofString` reads the whole body while it subscribes, and
   *  `ofInputStream` as the stream is read.
   */
  private final class BodyPump(exchange: Object, subscriber: HttpResponse.BodySubscriber[?]) extends Flow.Subscription:
    private val in = new HttpExchanges.BodyStream(exchange, 0, true)
    private var demand = 0L
    private var delivering = false
    private var finished = false

    def start(): Unit = subscriber.onSubscribe(this)

    def request(n: Long): Unit =
      if n <= 0 then
        cancel()
        subscriber.onError(new IllegalArgumentException("non-positive subscription request: " + n))
      else
        val deliverHere = synchronized {
          if finished then false
          else
            demand = if demand + n < 0 then Long.MaxValue else demand + n
            if delivering then false
            else
              delivering = true
              true
        }
        if deliverHere then deliver()

    private def deliver(): Unit =
      var more = true
      while more do
        val go = synchronized {
          if finished || demand == 0 then
            delivering = false
            false
          else
            if demand != Long.MaxValue then demand -= 1
            true
        }
        if !go then more = false
        else
          val buf = new Array[Byte](16384)
          val n =
            try in.read(buf, 0, buf.length)
            catch
              case e: IOException =>
                if finish() then subscriber.onError(e)
                -2
          if n == -1 then
            if finish() then subscriber.onComplete()
            more = false
          else if n == -2 then more = false
          else subscriber.onNext(JList.of(ByteBuffer.wrap(buf, 0, n)))

    /** Marks the delivery finished: whether it was not already. */
    private def finish(): Boolean = synchronized {
      val first = !finished
      finished = true
      first
    }

    def cancel(): Unit =
      finish()
      in.close()
