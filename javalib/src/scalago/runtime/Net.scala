package scalago.runtime

/** The native side of java.net (see runtime/rt/net.go and http.go): name resolution, TCP
 *  connections and listeners on Go's net package, and HTTP exchanges on net/http.
 *
 *  Addresses are byte arrays (4 bytes for IPv4, 16 for IPv6). Connections, listeners and HTTP
 *  exchanges are handles. Operations return their result, or an error that [[OS.isError]]
 *  recognizes; [[errorKind]] classifies it and [[errorMessage]] describes it, and java.net turns
 *  it into the exception the JDK throws. Closing a handle from another thread makes a blocked
 *  read or accept fail with [[Closed]].
 */
object Net:
  private def native: Nothing = throw new UnsupportedOperationException("scala-go native")

  // Error kinds.
  final val OtherError = 0
  final val Timeout = 1
  final val ConnectTimeout = 2
  final val Closed = 3
  final val Refused = 4
  final val Reset = 5
  final val HostUnreachable = 6
  final val NetworkUnreachable = 7
  final val UnknownHost = 8
  final val AddressInUse = 9
  final val AddressNotAvailable = 10
  final val BrokenPipe = 11
  final val TLS = 12
  final val TooManyRedirects = 13
  final val MalformedResponse = 14

  // Socket options.
  final val NoDelay = 0
  final val KeepAlive = 1
  final val Linger = 2
  final val ReceiveBuffer = 3
  final val SendBuffer = 4

  // Redirect policies.
  final val RedirectNever = 0
  /** HttpURLConnection's: within http or within https. */
  final val RedirectSameProtocol = 1
  /** HttpClient's NORMAL: all but from https to http. */
  final val RedirectNormal = 2
  final val RedirectAlways = 3

  /** The host's addresses (`Array[Array[Byte]]`, IPv4 first), or an error. */
  def lookup(host: String): Object = native
  /** The host name of an address, or null. */
  def reverseLookup(address: Array[Byte]): String = native
  /** This machine's name. */
  def hostname(): String = native

  /** Connects to address:port (from localAddress:localPort if either is set), waiting at most
   *  `timeoutMillis` if positive: a connection, or an error.
   */
  def connect(address: Array[Byte], port: Int, localAddress: Array[Byte], localPort: Int, timeoutMillis: Int): Object =
    native
  /** Listens on address:port (null: all addresses; 0: an ephemeral port): a listener, or an error. */
  def listen(address: Array[Byte], port: Int, backlog: Int): Object = native
  /** Waits for a connection (at most `timeoutMillis` if positive): the connection, or an error. */
  def accept(listener: Object, timeoutMillis: Int): Object = native

  /** Reads from a connection or an HTTP exchange's body, waiting at most `timeoutMillis` if
   *  positive: the number of bytes read, -1 at the end, or -2 if it failed ([[failure]]).
   */
  def read(handle: Object, b: Array[Byte], off: Int, len: Int, timeoutMillis: Int): Int = native
  /** The error of the last read of the handle that failed. */
  def failure(handle: Object): Object = native
  /** Writes to a connection: null, or an error. */
  def write(handle: Object, b: Array[Byte], off: Int, len: Int): Object = native
  /** Shuts down a connection's input or output: null, or an error. */
  def shutdown(handle: Object, output: Boolean): Object = native
  /** Closes a connection, listener or HTTP exchange. */
  def close(handle: Object): Unit = native

  /** The local or remote address of a connection or listener. */
  def address(handle: Object, local: Boolean): Array[Byte] = native
  def port(handle: Object, local: Boolean): Int = native
  /** Sets an option of a connection: null, or an error. */
  def setOption(handle: Object, option: Int, value: Int): Object = native
  /** The size of the receive or send buffer of a connection or listener (0 if unknown). */
  def bufferSize(handle: Object, send: Boolean): Int = native

  def errorKind(error: Object): Int = native
  /** The C library's description of a system error ("Connection refused"), or Go's message. */
  def errorMessage(error: Object): String = native

  /** Sends an HTTP request (`headers` alternate names and values; `body` is null for none) and
   *  waits for the response's headers: an exchange, or an error. The body is read with [[read]].
   */
  def httpSend(method: String, url: String, headers: Array[String], body: Array[Byte], connectTimeoutMillis: Int,
      timeoutMillis: Int, redirectPolicy: Int, maxRedirects: Int): Object = native
  def httpStatus(exchange: Object): Int = native
  def httpReason(exchange: Object): String = native
  /** 10, 11 or 20. */
  def httpVersion(exchange: Object): Int = native
  /** The response's header fields, alternating names and values, by name. */
  def httpHeaders(exchange: Object): Array[String] = native
  /** The URL of the response (the last one if redirected). */
  def httpURL(exchange: Object): String = native
