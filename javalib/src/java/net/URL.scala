package java.net

/** java.net.URL, parsed as the JDK's URL stream handlers parse it. The protocols are http,
 *  https and file (which `openConnection` supports), and jar, ftp and mailto (which it does not).
 *  Unlike the JDK, `equals` and `hashCode` compare host names without resolving them.
 */
final class URL private (
    private var protocol: String,
    private var host: String,
    private var port: Int,
    private var authority: String,
    private var userInfo: String,
    private var path: String,
    private var query: String,
    private var ref: String
) extends java.io.Serializable:

  def this(protocol: String, host: String, port: Int, file: String) =
    this(URL.lower(protocol), null, port, null, null, null, null, null)
    var h = host
    if h != null && h.indexOf(':') >= 0 && !h.startsWith("[") then h = "[" + h + "]"
    if port < -1 then throw new MalformedURLException("Invalid port number :" + port)
    this.host = h
    authority = if port == -1 then h else h + ":" + port
    val hash = file.indexOf('#')
    ref = if hash < 0 then null else file.substring(hash + 1)
    val f = if hash < 0 then file else file.substring(0, hash)
    val q = f.lastIndexOf('?')
    if q != -1 then
      query = f.substring(q + 1)
      path = f.substring(0, q)
    else path = f
    URL.checkProtocol(this.protocol)

  def this(protocol: String, host: String, file: String) = this(protocol, host, -1, file)

  def this(context: URL, spec: String) =
    this(null, null, -1, null, null, null, null, null)
    try parse(context, spec)
    catch
      case e: MalformedURLException => throw e
      case e: Exception =>
        val x = new MalformedURLException(e.getMessage())
        x.initCause(e)
        throw x

  def this(spec: String) = this(null: URL, spec)

  private def parse(context: URL, original: String): Unit =
    var spec = original
    var limit = spec.length
    var start = 0
    while limit > 0 && spec.charAt(limit - 1) <= ' ' do limit -= 1
    while start < limit && spec.charAt(start) <= ' ' do start += 1
    if spec.regionMatches(true, start, "url:", 0, 4) then start += 4
    val aRef = start < spec.length && spec.charAt(start) == '#'
    var newProtocol: String = null
    if !aRef then
      var i = start
      var done = false
      while !done && i < limit && spec.charAt(i) != '/' do
        if spec.charAt(i) == ':' then
          val s = URL.lower(spec.substring(start, i))
          if URL.isValidProtocol(s) then
            newProtocol = s
            start = i + 1
          done = true
        else i += 1
    protocol = newProtocol
    var isRelative = false
    if context != null && (newProtocol == null || newProtocol.equalsIgnoreCase(context.protocol)) then
      // A hierarchical context: a matching scheme in the spec is ignored (RFC 2396, 5.2.3).
      if context.path != null && context.path.startsWith("/") then newProtocol = null
      if newProtocol == null then
        protocol = context.protocol
        authority = context.authority
        userInfo = context.userInfo
        host = context.host
        port = context.port
        path = context.path
        isRelative = true
    if protocol == null then throw new MalformedURLException("no protocol: " + original)
    URL.checkProtocol(protocol)
    val hash = spec.indexOf('#', start)
    if hash >= 0 then
      ref = spec.substring(hash + 1, limit)
      limit = hash
    if isRelative && start == limit then
      query = context.query
      if ref == null then ref = context.ref
    parseURL(spec, start, limit)

  /** URLStreamHandler.parseURL: the authority, path and query of `spec(start until limit)`. */
  private def parseURL(spec0: String, start0: Int, limit0: Int): Unit =
    var spec = spec0
    var start = start0
    var limit = limit0
    var isRelPath = false
    var queryOnly = false
    if start < limit then
      val queryStart = spec.indexOf('?')
      queryOnly = queryStart == start
      if queryStart != -1 && queryStart < limit then
        query = spec.substring(queryStart + 1, limit)
        if limit > queryStart then limit = queryStart
        spec = spec.substring(0, queryStart)
    val isUNCName = start <= limit - 4 && spec.startsWith("////", start)
    if !isUNCName && start <= limit - 2 && spec.startsWith("//", start) then
      start += 2
      var i = spec.indexOf('/', start)
      if i < 0 || i > limit then
        i = spec.indexOf('?', start)
        if i < 0 || i > limit then i = limit
      authority = spec.substring(start, i)
      host = authority
      val at = authority.indexOf('@')
      if at != -1 then
        if at != authority.lastIndexOf('@') then
          userInfo = null // not server-based
          host = null
        else
          userInfo = authority.substring(0, at)
          host = authority.substring(at + 1)
      else userInfo = null
      if host != null then
        if host.length > 0 && host.charAt(0) == '[' then
          val close = host.indexOf(']')
          if close > 2 then
            val nhost = host
            host = nhost.substring(0, close + 1)
            if Inet6Address.parse(host.substring(1, close)) == null then
              throw new IllegalArgumentException("Invalid host: " + host)
            port = -1
            if nhost.length > close + 1 then
              if nhost.charAt(close + 1) == ':' then
                if nhost.length > close + 2 then port = URL.parsePort(nhost, close + 2)
              else throw new IllegalArgumentException("Invalid authority field: " + authority)
          else throw new IllegalArgumentException("Invalid authority field: " + authority)
        else
          val colon = host.indexOf(':')
          port = -1
          if colon >= 0 then
            if host.length > colon + 1 then port = URL.parsePort(host, colon + 1)
            host = host.substring(0, colon)
      else host = ""
      if port < -1 then throw new IllegalArgumentException("Invalid port number :" + port)
      start = i
      // With an authority, the path comes from the spec only (RFC 2396, 5.2.4).
      if authority != null && !authority.isEmpty then path = ""
    if host == null then host = ""
    if start < limit then
      if spec.charAt(start) == '/' then path = spec.substring(start, limit)
      else if path != null && !path.isEmpty then
        isRelPath = true
        val slash = path.lastIndexOf('/')
        val separator = if slash == -1 && authority != null then "/" else ""
        path = path.substring(0, slash + 1) + separator + spec.substring(start, limit)
      else
        path = spec.substring(start, limit)
        if authority != null then path = "/" + path
    else if queryOnly && path != null then
      val slash = path.lastIndexOf('/').max(0)
      path = path.substring(0, slash) + "/"
    if path == null then path = ""
    if isRelPath then path = URL.removeDots(path)

  def getProtocol(): String = protocol
  def getHost(): String = host
  def getPort(): Int = port
  def getDefaultPort(): Int = URL.defaultPort(protocol)
  def getAuthority(): String = authority
  def getUserInfo(): String = userInfo
  def getPath(): String = path
  def getQuery(): String = query
  def getFile(): String = if query == null then path else path + "?" + query
  def getRef(): String = ref

  def toExternalForm(): String =
    protocol + ":" + (if authority != null && !authority.isEmpty then "//" + authority else "") +
      (if path != null then path else "") + (if query != null then "?" + query else "") +
      (if ref != null then "#" + ref else "")

  override def toString(): String = toExternalForm()

  def toURI(): URI = new URI(toString())

  /** Whether the URLs are equal, apart from their fragments. */
  def sameFile(other: URL): Boolean =
    protocol.equalsIgnoreCase(other.protocol) && java.util.Objects.equals(getFile(), other.getFile()) &&
      (if port == -1 then getDefaultPort() else port) ==
      (if other.port == -1 then other.getDefaultPort() else other.port) &&
      (if host == null || other.host == null then host == other.host else host.equalsIgnoreCase(other.host))

  override def equals(obj: Any): Boolean = obj match
    case that: URL => java.util.Objects.equals(ref, that.ref) && sameFile(that)
    case _         => false

  override def hashCode(): Int =
    var h = protocol.hashCode
    if host != null then h += host.toLowerCase().hashCode
    h += getFile().hashCode
    h += (if port == -1 then getDefaultPort() else port)
    if ref != null then h += ref.hashCode
    h

  def openConnection(): URLConnection = protocol match
    case "http" | "https" => new HttpURLConnectionImpl(this)
    case "file"           => new FileURLConnection(this)
    case _ => throw new java.io.IOException("scala-go: " + protocol + " URLs cannot be opened")

  def openConnection(proxy: Proxy): URLConnection =
    if proxy == null then throw new IllegalArgumentException("proxy can not be null")
    if proxy.`type`() != Proxy.Type.DIRECT then
      throw new UnsupportedOperationException("scala-go: connections go direct, not through proxies")
    openConnection()

  def openStream(): java.io.InputStream = openConnection().getInputStream()

object URL:
  /** The URL of an absolute URI. */
  def of(uri: URI, handler: URLStreamHandler): URL =
    if handler != null then throw new UnsupportedOperationException("scala-go: URL stream handlers")
    uri.toURL()

  private def lower(s: String): String = if s == null then null else s.toLowerCase(java.util.Locale.ROOT)

  private val knownProtocols = Set("http", "https", "file", "jar", "ftp", "mailto")

  private def checkProtocol(protocol: String): Unit =
    if !knownProtocols.contains(protocol) then throw new MalformedURLException("unknown protocol: " + protocol)

  private def isValidProtocol(s: String): Boolean =
    s.length >= 1 && Character.isLetter(s.charAt(0)) &&
      (1 until s.length).forall(i => Character.isLetterOrDigit(s.charAt(i)) || ".+-".indexOf(s.charAt(i)) >= 0)

  private def defaultPort(protocol: String): Int = protocol match
    case "http"  => 80
    case "https" => 443
    case "ftp"   => 21
    case _       => -1

  /** Integer.parseInt(s, from, s.length, 10), with its message. */
  private def parsePort(s: String, from: Int): Int =
    try Integer.parseInt(s.substring(from))
    catch
      case _: NumberFormatException =>
        var i = from
        if i < s.length && (s.charAt(i) == '-' || s.charAt(i) == '+') then i += 1
        while i < s.length && Character.digit(s.charAt(i), 10) >= 0 do i += 1
        throw new NumberFormatException("Error at index " + (i - from) + " in: \"" + s.substring(from) + "\"")

  /** Removes "/./" and "segment/../" from a relative path, as the JDK's handlers do. */
  private def removeDots(p: String): String =
    var path = p
    var i = path.indexOf("/./")
    while i >= 0 do
      path = path.substring(0, i) + path.substring(i + 2)
      i = path.indexOf("/./")
    i = 0
    i = path.indexOf("/../", i)
    while i >= 0 do
      val previous = if i > 0 then path.lastIndexOf('/', i - 1) else -1
      if previous >= 0 && path.indexOf("/../", previous) != 0 then
        path = path.substring(0, previous) + path.substring(i + 3)
        i = path.indexOf("/../", 0)
      else i = path.indexOf("/../", i + 3)
    var trailing = true
    while trailing && path.endsWith("/..") do
      val dots = path.indexOf("/..")
      val previous = path.lastIndexOf('/', dots - 1)
      if previous >= 0 then path = path.substring(0, previous + 1) else trailing = false
    if path.startsWith("./") && path.length > 2 then path = path.substring(2)
    if path.endsWith("/.") then path = path.substring(0, path.length - 1)
    path

/** Programs cannot provide protocol handlers; this class only exists for signatures. */
abstract class URLStreamHandler:
  protected def openConnection(u: URL): URLConnection
