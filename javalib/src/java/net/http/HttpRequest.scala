package java.net.http

import java.io.{ByteArrayOutputStream, InputStream}
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.{Charset, StandardCharsets}
import java.time.Duration
import java.util.{ArrayList, List as JList, Optional}
import java.util.concurrent.{CountDownLatch, Flow}
import java.util.function.{BiPredicate, Supplier}

abstract class HttpRequest protected ():
  def bodyPublisher(): Optional[HttpRequest.BodyPublisher]
  def method(): String
  def timeout(): Optional[Duration]
  def expectContinue(): Boolean
  def uri(): URI
  def version(): Optional[HttpClient.Version]
  def headers(): HttpHeaders

  override final def equals(obj: Any): Boolean = obj match
    case that: HttpRequest => method() == that.method() && uri() == that.uri() && headers() == that.headers()
    case _                 => false

  override final def hashCode(): Int = method().hashCode + uri().hashCode + headers().hashCode

object HttpRequest:
  def newBuilder(): Builder = new RequestBuilder()
  def newBuilder(uri: URI): Builder = new RequestBuilder().uri(uri)

  /** A builder with the request's settings and the headers the filter keeps. */
  def newBuilder(request: HttpRequest, filter: BiPredicate[String, String]): Builder =
    val b = new RequestBuilder()
    b.uri(request.uri())
    b.expectContinue(request.expectContinue())
    request.version().ifPresent(v => b.version(v))
    request.timeout().ifPresent(t => b.timeout(t))
    request.headers().map().forEach((name, values) => values.forEach(v => if filter.test(name, v) then b.header(name, v)))
    b.method(request.method(), request.bodyPublisher().orElse(BodyPublishers.noBody()))
    b

  trait Builder:
    def uri(uri: URI): Builder
    def expectContinue(enable: Boolean): Builder
    def version(version: HttpClient.Version): Builder
    def header(name: String, value: String): Builder
    def headers(headers: Array[String]): Builder
    def timeout(duration: Duration): Builder
    def setHeader(name: String, value: String): Builder
    def GET(): Builder
    def POST(bodyPublisher: BodyPublisher): Builder
    def PUT(bodyPublisher: BodyPublisher): Builder
    def DELETE(): Builder
    def HEAD(): Builder = method("HEAD", BodyPublishers.noBody())
    def method(method: String, bodyPublisher: BodyPublisher): Builder
    def build(): HttpRequest
    def copy(): Builder

  /** A request body: a publisher of byte buffers, with its length if known (-1 otherwise). */
  trait BodyPublisher extends Flow.Publisher[ByteBuffer]:
    def contentLength(): Long

  object BodyPublishers:
    def ofString(s: String): BodyPublisher = ofString(s, StandardCharsets.UTF_8)
    def ofString(s: String, charset: Charset): BodyPublisher = new BytesPublisher(() => s.getBytes(charset), true)
    def ofByteArray(buf: Array[Byte]): BodyPublisher = ofByteArray(buf, 0, buf.length)

    def ofByteArray(buf: Array[Byte], offset: Int, length: Int): BodyPublisher =
      java.util.Objects.checkFromIndexSize(offset, length, buf.length)
      new BytesPublisher(() => java.util.Arrays.copyOfRange(buf, offset, offset + length), true)

    def ofByteArrays(iter: java.lang.Iterable[Array[Byte]]): BodyPublisher =
      new BytesPublisher(() => {
        val out = new ByteArrayOutputStream()
        iter.forEach(b => out.write(b))
        out.toByteArray()
      }, false)

    def ofInputStream(streamSupplier: Supplier[? <: InputStream]): BodyPublisher =
      new BytesPublisher(() => {
        val in = streamSupplier.get()
        if in == null then throw new java.io.UncheckedIOException(new java.io.IOException("Stream supplier returned null"))
        try in.readAllBytes()
        finally in.close()
      }, false)

    def ofFile(path: java.nio.file.Path): BodyPublisher =
      if !java.nio.file.Files.exists(path, Array()) then throw new java.io.FileNotFoundException(path.toString + " not found")
      new BytesPublisher(() => java.nio.file.Files.readAllBytes(path), true)

    def noBody(): BodyPublisher = new BytesPublisher(() => new Array[Byte](0), true)

    def fromPublisher(publisher: Flow.Publisher[? <: ByteBuffer]): BodyPublisher = fromPublisher(publisher, -1)

    def fromPublisher(publisher: Flow.Publisher[? <: ByteBuffer], contentLength: Long): BodyPublisher =
      val length = contentLength
      new BodyPublisher:
        def contentLength(): Long = length
        def subscribe(subscriber: Flow.Subscriber[? >: ByteBuffer]): Unit =
          publisher.asInstanceOf[Flow.Publisher[ByteBuffer]].subscribe(subscriber.asInstanceOf[Flow.Subscriber[ByteBuffer]])

    def concat(publishers: Array[BodyPublisher]): BodyPublisher =
      if publishers.isEmpty then noBody()
      else if publishers.length == 1 then publishers.head
      else new BytesPublisher(() => {
        val out = new ByteArrayOutputStream()
        for p <- publishers do out.write(collect(p))
        out.toByteArray()
      }, publishers.forall(_.contentLength() >= 0))

  /** A body whose bytes are made when it is sent (or subscribed to). */
  private[http] final class BytesPublisher(make: () => Array[Byte], knownLength: Boolean) extends BodyPublisher:
    private lazy val length = if knownLength then bytes().length.toLong else -1L
    def bytes(): Array[Byte] = make()
    def contentLength(): Long = length

    def subscribe(subscriber: Flow.Subscriber[? >: ByteBuffer]): Unit =
      val s = subscriber.asInstanceOf[Flow.Subscriber[ByteBuffer]]
      s.onSubscribe(new Flow.Subscription:
        private var done = false
        def request(n: Long): Unit =
          if !done then
            done = true
            if n <= 0 then s.onError(new IllegalArgumentException("non-positive subscription request: " + n))
            else
              try
                val b = bytes()
                if b.length > 0 then s.onNext(ByteBuffer.wrap(b))
                s.onComplete()
              catch case e: Exception => s.onError(e)
        def cancel(): Unit = done = true
      )

  /** The whole body of a publisher: built-in ones directly, others by subscribing. */
  private[http] def collect(publisher: BodyPublisher): Array[Byte] = publisher match
    case p: BytesPublisher => p.bytes()
    case p =>
      val out = new ByteArrayOutputStream()
      val done = new CountDownLatch(1)
      @volatile var failure: Throwable = null
      p.subscribe(new Flow.Subscriber[ByteBuffer]:
        def onSubscribe(subscription: Flow.Subscription): Unit = subscription.request(Long.MaxValue)
        def onNext(item: ByteBuffer): Unit = out.synchronized {
          val b = new Array[Byte](item.remaining())
          item.get(b)
          out.write(b)
        }
        def onError(throwable: Throwable): Unit =
          failure = throwable
          done.countDown()
        def onComplete(): Unit = done.countDown()
      )
      done.await()
      if failure != null then throw new java.io.IOException(failure)
      out.synchronized(out.toByteArray())

  private final class RequestImpl(b: RequestBuilder) extends HttpRequest:
    private val _uri = b.uriValue
    private val _method = b.methodValue
    private val _body = b.body
    private val _timeout = b.timeoutValue
    private val _version = b.versionValue
    private val _expectContinue = b.expectContinueValue
    private val _headers = HttpHeaders.ofPairs(new ArrayList(b.headerPairs))

    def bodyPublisher(): Optional[BodyPublisher] = Optional.ofNullable(_body)
    def method(): String = _method
    def timeout(): Optional[Duration] = Optional.ofNullable(_timeout)
    def expectContinue(): Boolean = _expectContinue
    def uri(): URI = _uri
    def version(): Optional[HttpClient.Version] = Optional.ofNullable(_version)
    def headers(): HttpHeaders = _headers
    override def toString(): String = _uri.toString + " " + _method

  private val restricted = Set("connection", "content-length", "expect", "host", "upgrade")

  private def isValidName(name: String): Boolean =
    !name.isEmpty && name.forall(c => c < 128 && (Character.isLetterOrDigit(c) || "!#$%&'*+-.^_`|~".indexOf(c) >= 0))

  private def isValidValue(value: String): Boolean =
    value.forall(c => c <= 255 && c != '\r' && c != '\n' && c != 0)

  private final class RequestBuilder extends Builder:
    var uriValue: URI = null
    var methodValue = "GET"
    var body: BodyPublisher = null
    var timeoutValue: Duration = null
    var versionValue: HttpClient.Version = null
    var expectContinueValue = false
    val headerPairs = new ArrayList[Array[String]]()

    def uri(uri: URI): Builder =
      java.util.Objects.requireNonNull(uri, "uri must be non-null")
      val scheme = uri.getScheme()
      if scheme == null then throw new IllegalArgumentException("URI with undefined scheme")
      val s = scheme.toLowerCase(java.util.Locale.ROOT)
      if s != "http" && s != "https" then throw new IllegalArgumentException("invalid URI scheme " + scheme)
      if uri.getHost() == null then throw new IllegalArgumentException("unsupported URI " + uri)
      uriValue = uri
      this

    def expectContinue(enable: Boolean): Builder =
      expectContinueValue = enable
      this

    def version(version: HttpClient.Version): Builder =
      versionValue = java.util.Objects.requireNonNull(version)
      this

    private def checkHeader(name: String, value: String): Unit =
      java.util.Objects.requireNonNull(name)
      java.util.Objects.requireNonNull(value)
      if !isValidName(name) then throw new IllegalArgumentException("invalid header name: \"" + name + "\"")
      if restricted.contains(name.toLowerCase(java.util.Locale.ROOT)) then
        throw new IllegalArgumentException("restricted header name: \"" + name + "\"")
      if !isValidValue(value) then
        throw new IllegalArgumentException("invalid header value: \"" + value + "\"")

    def header(name: String, value: String): Builder =
      checkHeader(name, value)
      headerPairs.add(Array(name, value))
      this

    def headers(headers: Array[String]): Builder =
      if headers.length == 0 || headers.length % 2 != 0 then
        throw new IllegalArgumentException("wrong number, " + headers.length + ", of parameters")
      for i <- 0 until headers.length by 2 do header(headers(i), headers(i + 1))
      this

    def timeout(duration: Duration): Builder =
      java.util.Objects.requireNonNull(duration)
      if duration.isNegative || duration.isZero then throw new IllegalArgumentException("Invalid duration: " + duration)
      timeoutValue = duration
      this

    def setHeader(name: String, value: String): Builder =
      checkHeader(name, value)
      headerPairs.removeIf(_(0).equalsIgnoreCase(name))
      headerPairs.add(Array(name, value))
      this

    def GET(): Builder = method0("GET", null)
    def POST(bodyPublisher: BodyPublisher): Builder = method0("POST", java.util.Objects.requireNonNull(bodyPublisher))
    def PUT(bodyPublisher: BodyPublisher): Builder = method0("PUT", java.util.Objects.requireNonNull(bodyPublisher))
    def DELETE(): Builder = method0("DELETE", null)

    def method(method: String, bodyPublisher: BodyPublisher): Builder =
      java.util.Objects.requireNonNull(method)
      if method.isEmpty then throw new IllegalArgumentException("illegal method <empty string>")
      if !isValidName(method) then throw new IllegalArgumentException("illegal method \"" + method + "\"")
      if method == "CONNECT" then throw new IllegalArgumentException("method CONNECT is not supported")
      method0(method, java.util.Objects.requireNonNull(bodyPublisher))

    private def method0(method: String, bodyPublisher: BodyPublisher): Builder =
      methodValue = method
      body = bodyPublisher
      this

    def build(): HttpRequest =
      if uriValue == null then throw new IllegalStateException("uri is null")
      new RequestImpl(this)

    def copy(): Builder =
      val b = new RequestBuilder()
      b.uriValue = uriValue
      b.methodValue = methodValue
      b.body = body
      b.timeoutValue = timeoutValue
      b.versionValue = versionValue
      b.expectContinueValue = expectContinueValue
      b.headerPairs.addAll(headerPairs)
      b
