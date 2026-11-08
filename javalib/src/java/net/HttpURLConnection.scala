package java.net

import java.io.{ByteArrayOutputStream, FileNotFoundException, InputStream, IOException, OutputStream}
import java.util.{ArrayList, List as JList, Map as JMap}

import scalago.runtime.{Net, OS}

abstract class HttpURLConnection protected (u: URL) extends URLConnection(u):
  protected var method: String = "GET"
  protected var chunkLength: Int = -1
  protected var fixedContentLength: Int = -1
  protected var fixedContentLengthLong: Long = -1L
  protected var responseCode: Int = -1
  protected var responseMessage: String = null
  protected var instanceFollowRedirects: Boolean = HttpURLConnection.followRedirects

  def setFixedLengthStreamingMode(contentLength: Int): Unit = setFixedLengthStreamingMode(contentLength.toLong)

  def setFixedLengthStreamingMode(contentLength: Long): Unit =
    if connected then throw new IllegalStateException("Already connected")
    if chunkLength != -1 then throw new IllegalStateException("Chunked encoding streaming mode set")
    if contentLength < 0 then throw new IllegalArgumentException("invalid content length")
    fixedContentLengthLong = contentLength
    fixedContentLength = if contentLength > Int.MaxValue then -1 else contentLength.toInt

  def setChunkedStreamingMode(chunklen: Int): Unit =
    if connected then throw new IllegalStateException("Can't set streaming mode: already connected")
    if fixedContentLengthLong != -1 then throw new IllegalStateException("Fixed length streaming mode set")
    chunkLength = if chunklen <= 0 then 4096 else chunklen

  def setInstanceFollowRedirects(followRedirects: Boolean): Unit = instanceFollowRedirects = followRedirects
  def getInstanceFollowRedirects(): Boolean = instanceFollowRedirects

  def setRequestMethod(method: String): Unit =
    if connected then throw new ProtocolException("Can't reset method: already connected")
    if !HttpURLConnection.methods.contains(method) then throw new ProtocolException("Invalid HTTP method: " + method)
    this.method = method

  def getRequestMethod(): String = method

  def getResponseCode(): Int = responseCode
  def getResponseMessage(): String = responseMessage

  def disconnect(): Unit
  def usingProxy(): Boolean

  def getErrorStream(): InputStream = null

object HttpURLConnection:
  @volatile private var followRedirects = true
  private val methods = Set("GET", "POST", "HEAD", "OPTIONS", "PUT", "DELETE", "TRACE")

  def setFollowRedirects(set: Boolean): Unit = followRedirects = set
  def getFollowRedirects(): Boolean = followRedirects

  final val HTTP_OK = 200
  final val HTTP_CREATED = 201
  final val HTTP_ACCEPTED = 202
  final val HTTP_NOT_AUTHORITATIVE = 203
  final val HTTP_NO_CONTENT = 204
  final val HTTP_RESET = 205
  final val HTTP_PARTIAL = 206
  final val HTTP_MULT_CHOICE = 300
  final val HTTP_MOVED_PERM = 301
  final val HTTP_MOVED_TEMP = 302
  final val HTTP_SEE_OTHER = 303
  final val HTTP_NOT_MODIFIED = 304
  final val HTTP_USE_PROXY = 305
  final val HTTP_BAD_REQUEST = 400
  final val HTTP_UNAUTHORIZED = 401
  final val HTTP_PAYMENT_REQUIRED = 402
  final val HTTP_FORBIDDEN = 403
  final val HTTP_NOT_FOUND = 404
  final val HTTP_BAD_METHOD = 405
  final val HTTP_NOT_ACCEPTABLE = 406
  final val HTTP_PROXY_AUTH = 407
  final val HTTP_CLIENT_TIMEOUT = 408
  final val HTTP_CONFLICT = 409
  final val HTTP_GONE = 410
  final val HTTP_LENGTH_REQUIRED = 411
  final val HTTP_PRECON_FAILED = 412
  final val HTTP_ENTITY_TOO_LARGE = 413
  final val HTTP_REQ_TOO_LONG = 414
  final val HTTP_UNSUPPORTED_TYPE = 415
  final val HTTP_INTERNAL_ERROR = 500
  final val HTTP_NOT_IMPLEMENTED = 501
  final val HTTP_BAD_GATEWAY = 502
  final val HTTP_UNAVAILABLE = 503
  final val HTTP_GATEWAY_TIMEOUT = 504
  final val HTTP_VERSION = 505

/** The http and https connections of `URL.openConnection`, on Go's net/http
 *  (`scalago.runtime.Net.httpSend`). The request is sent whole when the response is first
 *  needed (a request body is buffered, whatever the streaming mode); redirects are followed
 *  within the protocol, at most 20 times, as on the JDK.
 */
private[net] final class HttpURLConnectionImpl(u: URL) extends HttpURLConnection(u):
  private var body: ByteArrayOutputStream = null
  private var exchange: Object = null
  private var failure: IOException = null
  private var headers: JList[Array[String]] = null // name/value pairs
  private var input: InputStream = null

  def connect(): Unit =
    if !connected && !doOutput then send()
    connected = true

  override def getOutputStream(): OutputStream =
    if !doOutput then
      throw new ProtocolException("cannot write to a URLConnection if doOutput=false - call setDoOutput(true)")
    if exchange != null || failure != null then throw new ProtocolException("Cannot write output after reading input.")
    if method == "GET" then method = "POST"
    if method == "TRACE" then throw new ProtocolException("HTTP method TRACE doesn't support output")
    connected = true
    if body == null then body = new ByteArrayOutputStream()
    body

  /** Sends the request, once; a failure is remembered and thrown again. */
  private def send(): Unit =
    if failure != null then throw failure
    if exchange != null then return
    connected = true
    val fields = new ArrayList[String]()
    def has(name: String) = requestList.stream().anyMatch(_(0).equalsIgnoreCase(name))
    requestList.forEach { r => fields.add(r(0)); fields.add(if r(1) == null then "" else r(1)) }
    if !has("User-Agent") then
      fields.add("User-Agent")
      fields.add("Java/" + System.getProperty("java.version"))
    if !has("Accept") then
      fields.add("Accept")
      fields.add("*/*")
    if body != null && method == "POST" && !has("Content-Type") then
      fields.add("Content-type")
      fields.add("application/x-www-form-urlencoded")
    val result = Net.httpSend(method, url.toString, fields.toArray(new Array[String](0)),
      if body == null then null else body.toByteArray(), getConnectTimeout(), getReadTimeout(),
      if instanceFollowRedirects then Net.RedirectSameProtocol else Net.RedirectNever, 20)
    if OS.isError(result) then
      failure = HttpExchanges.failure(result, url.getHost())
      throw failure
    exchange = result
    responseCode = Net.httpStatus(result)
    responseMessage = Net.httpReason(result)
    headers = HttpExchanges.headers(result)
    val finalURL = Net.httpURL(result)
    if finalURL != url.toString then url = new URL(finalURL)

  override def getResponseCode(): Int =
    send()
    responseCode

  override def getResponseMessage(): String =
    send()
    responseMessage

  override def getInputStream(): InputStream =
    if !doInput then
      throw new ProtocolException("Cannot read from URLConnection if doInput=false (call setDoInput(true))")
    send()
    if responseCode >= 400 then
      if responseCode == 404 || responseCode == 410 then throw new FileNotFoundException(url.toString)
      throw new IOException("Server returned HTTP response code: " + responseCode + " for URL: " + url)
    bodyStream()

  override def getErrorStream(): InputStream =
    if exchange == null || responseCode < 400 then null else bodyStream()

  private def bodyStream(): InputStream =
    if input == null then input = new HttpExchanges.BodyStream(exchange, getReadTimeout(), false)
    input

  private def statusLine: String = s"HTTP/1.1 $responseCode $responseMessage"

  /** The response's fields, or null if the request failed. */
  private def responseFields: JList[Array[String]] =
    try
      send()
      headers
    catch case _: IOException => null

  override def getHeaderField(name: String): String =
    val fields = responseFields
    if fields == null then null
    else if name == null then statusLine
    else
      var value: String = null
      fields.forEach(f => if f(0).equalsIgnoreCase(name) then value = f(1))
      value

  override def getHeaderFields(): JMap[String, JList[String]] =
    val fields = responseFields
    if fields == null then java.util.Collections.emptyMap()
    else
      val all = new ArrayList[Array[String]](fields)
      all.add(0, Array(null, statusLine))
      URLConnection.fieldMap(all)

  override def getHeaderFieldKey(n: Int): String =
    val fields = responseFields
    if fields == null || n <= 0 || n > fields.size() then null else fields.get(n - 1)(0)

  override def getHeaderField(n: Int): String =
    val fields = responseFields
    if fields == null || n < 0 || n > fields.size() then null
    else if n == 0 then statusLine
    else fields.get(n - 1)(1)

  def disconnect(): Unit =
    if exchange != null then Net.close(exchange)

  def usingProxy(): Boolean = false

/** What HttpURLConnection and java.net.http share: response bodies and failures. */
private[net] object HttpExchanges:
  /** The response's header fields, as name/value pairs (by name). */
  def headers(exchange: Object): JList[Array[String]] =
    val raw = Net.httpHeaders(exchange)
    val list = new ArrayList[Array[String]](raw.length / 2)
    for i <- 0 until raw.length by 2 do list.add(Array(raw(i), raw(i + 1)))
    list

  /** The exception HttpURLConnection throws for a failed exchange. */
  def failure(error: Object, host: String): IOException =
    val message = Net.errorMessage(error)
    Net.errorKind(error) match
      case Net.ConnectTimeout    => new SocketTimeoutException("Connect timed out")
      case Net.Timeout           => new SocketTimeoutException("Read timed out")
      case Net.Refused           => new ConnectException("Connection refused")
      case Net.UnknownHost       => new UnknownHostException(host)
      case Net.TooManyRedirects  => new ProtocolException("Server redirected too many  times (20)")
      case Net.TLS               => new javax.net.ssl.SSLHandshakeException(message)
      case Net.MalformedResponse => new IOException("Invalid Http response")
      case Net.Reset             => new SocketException("Connection reset")
      case Net.HostUnreachable   => new NoRouteToHostException(message)
      case _                     => new IOException(message)

  /** A response's body. The exchange is closed at the end or when the stream is closed;
   *  `httpClient` selects java.net.http's exceptions.
   */
  final class BodyStream(exchange: Object, readTimeout: Int, httpClient: Boolean) extends InputStream:
    @volatile private var closed = false
    private var eof = false

    override def read(): Int =
      val b = new Array[Byte](1)
      var n = 0
      while n == 0 do n = read(b, 0, 1)
      if n == -1 then -1 else b(0) & 0xff

    override def read(b: Array[Byte], off: Int, len: Int): Int =
      java.util.Objects.checkFromIndexSize(off, len, b.length)
      if closed then throw new IOException("stream is closed")
      if eof then return -1
      if len == 0 then return 0
      val n = Net.read(exchange, b, off, len, readTimeout)
      if n == -1 then
        eof = true
        Net.close(exchange)
      if n >= -1 then n
      else
        val error = Net.failure(exchange)
        if closed then throw new IOException("stream is closed")
        Net.errorKind(error) match
          case Net.Timeout if !httpClient => throw new SocketTimeoutException("Read timed out")
          case Net.Reset                  => throw new SocketException("Connection reset")
          case _                          => throw new IOException(Net.errorMessage(error))

    override def available(): Int = 0

    override def close(): Unit =
      if !closed then
        closed = true
        Net.close(exchange)
