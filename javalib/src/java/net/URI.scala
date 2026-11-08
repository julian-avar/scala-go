package java.net

import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.{CodingErrorAction, StandardCharsets}

/** java.net.URI: RFC 2396 references, with the JDK's deviations (RFC 2732 IPv6 literals, empty
 *  authorities and paths, scope ids) and its parser's messages and error indices. Components
 *  are kept raw (with their escapes); the getters without `Raw` decode them as UTF-8.
 */
final class URI private () extends Comparable[URI] with java.io.Serializable:
  import URI.*

  // Components; a null path means an opaque URI.
  private var scheme: String = null
  private var fragment: String = null
  private var authority: String = null // registry- or server-based
  private var userInfo: String = null // server-based authorities only
  private var host: String = null // likewise; IPv6 literals keep their brackets
  private var port: Int = -1
  private var path: String = null
  private var query: String = null
  private var schemeSpecificPart: String = null
  private var string: String = null // the original string, or one built from the components
  private var hash: Int = 0

  def this(str: String) =
    this()
    new Parser(str, this).parse(requireServerAuthority = false)

  def this(scheme: String, userInfo: String, host: String, port: Int, path: String, query: String, fragment: String) =
    this()
    val s = URI.build(scheme, null, null, userInfo, host, port, path, query, fragment)
    URI.checkPath(s, scheme, path)
    new Parser(s, this).parse(requireServerAuthority = true)

  def this(scheme: String, authority: String, path: String, query: String, fragment: String) =
    this()
    val s = URI.build(scheme, null, authority, null, null, -1, path, query, fragment)
    URI.checkPath(s, scheme, path)
    new Parser(s, this).parse(requireServerAuthority = false)

  def this(scheme: String, host: String, path: String, fragment: String) =
    this(scheme, null, host, -1, path, null, fragment)

  def this(scheme: String, ssp: String, fragment: String) =
    this()
    new Parser(URI.build(scheme, ssp, null, null, null, -1, null, null, fragment), this)
      .parse(requireServerAuthority = false)

  /** Parses a registry-based authority as a server-based one, or fails as the JDK does. */
  def parseServerAuthority(): URI =
    if host == null && authority != null then
      defineString()
      new Parser(string, this).parse(requireServerAuthority = true)
    this

  def normalize(): URI =
    if isOpaque() || path == null || path.isEmpty then return this
    val np = URI.normalizePath(path, removeLeading = false)
    if np eq path then this
    else
      val v = copyHierarchy()
      v.path = np
      v

  def resolve(uri: URI): URI = URI.resolve(this, uri)
  def resolve(str: String): URI = resolve(URI.create(str))

  def relativize(uri: URI): URI =
    val child = uri
    if child.isOpaque() || isOpaque() then return child
    if !URI.equalIgnoringCase(scheme, child.scheme) || !URI.equal(authority, child.authority) then return child
    var bp = URI.normalizePath(path, removeLeading = false)
    val cp = URI.normalizePath(child.path, removeLeading = false)
    if bp != cp then
      if !bp.endsWith("/") then bp = bp + "/"
      if !cp.startsWith(bp) then return child
    val v = new URI()
    v.path = cp.substring(bp.length)
    v.query = child.query
    v.fragment = child.fragment
    v

  def toURL(): URL =
    if !isAbsolute() then throw new IllegalArgumentException("URI is not absolute")
    new URL(toString())

  def getScheme(): String = scheme
  def isAbsolute(): Boolean = scheme != null
  def isOpaque(): Boolean = path == null

  def getRawSchemeSpecificPart(): String =
    if schemeSpecificPart == null then
      val sb = new java.lang.StringBuilder()
      appendHierarchy(sb)
      schemeSpecificPart = sb.toString
    schemeSpecificPart

  def getSchemeSpecificPart(): String = URI.decode(getRawSchemeSpecificPart())
  def getRawAuthority(): String = authority
  def getAuthority(): String = URI.decode(authority)
  def getRawUserInfo(): String = userInfo
  def getUserInfo(): String = URI.decode(userInfo)
  def getHost(): String = host
  def getPort(): Int = port
  def getRawPath(): String = path
  def getPath(): String = URI.decode(path)
  def getRawQuery(): String = query
  def getQuery(): String = URI.decode(query, ignorePercentInBrackets = false)
  def getRawFragment(): String = fragment
  def getFragment(): String = URI.decode(fragment, ignorePercentInBrackets = false)

  override def equals(ob: Any): Boolean = ob match
    case that: URI =>
      (this eq that) || {
        isOpaque() == that.isOpaque() && URI.equalIgnoringCase(scheme, that.scheme) &&
        URI.equal(fragment, that.fragment) && {
          if isOpaque() then URI.equal(schemeSpecificPart, that.schemeSpecificPart)
          else
            URI.equal(path, that.path) && URI.equal(query, that.query) && (
              (authority eq that.authority) ||
              (if host != null then
                 URI.equal(userInfo, that.userInfo) && URI.equalIgnoringCase(host, that.host) && port == that.port
               else if authority != null then URI.equal(authority, that.authority)
               else authority eq that.authority)
            )
        }
      }
    case _ => false

  override def hashCode(): Int =
    var h = hash
    if h == 0 then
      h = URI.hashIgnoringCase(0, scheme)
      h = URI.hash(h, fragment)
      if isOpaque() then h = URI.hash(h, schemeSpecificPart)
      else
        h = URI.hash(h, path)
        h = URI.hash(h, query)
        if host != null then
          h = URI.hash(h, userInfo)
          h = URI.hashIgnoringCase(h, host)
          h += 1949 * port
        else h = URI.hash(h, authority)
      hash = h
    h

  def compareTo(that: URI): Int =
    var c = URI.compareIgnoringCase(scheme, that.scheme)
    if c != 0 then return c
    if isOpaque() then
      if that.isOpaque() then
        c = URI.compare(schemeSpecificPart, that.schemeSpecificPart)
        if c != 0 then c else URI.compare(fragment, that.fragment)
      else 1
    else if that.isOpaque() then -1
    else
      if host != null && that.host != null then
        c = URI.compare(userInfo, that.userInfo)
        if c != 0 then return c
        c = URI.compareIgnoringCase(host, that.host)
        if c != 0 then return c
        c = port - that.port
        if c != 0 then return c
      else
        c = URI.compare(authority, that.authority)
        if c != 0 then return c
      c = URI.compare(path, that.path)
      if c != 0 then return c
      c = URI.compare(query, that.query)
      if c != 0 then c else URI.compare(fragment, that.fragment)

  override def toString(): String =
    defineString()
    string

  def toASCIIString(): String = URI.encodeNonASCII(toString())

  private def copyHierarchy(): URI =
    val v = new URI()
    v.scheme = scheme
    v.fragment = fragment
    v.authority = authority
    v.userInfo = userInfo
    v.host = host
    v.port = port
    v.path = path
    v.query = query
    v

  private def appendHierarchy(sb: java.lang.StringBuilder): Unit =
    if host != null then
      sb.append("//")
      if userInfo != null then sb.append(userInfo).append('@')
      val brackets = host.indexOf(':') >= 0 && !host.startsWith("[") && !host.endsWith("]")
      if brackets then sb.append('[')
      sb.append(host)
      if brackets then sb.append(']')
      if port != -1 then sb.append(':').append(port)
    else if authority != null then sb.append("//").append(authority)
    if path != null then sb.append(path)
    if query != null then sb.append('?').append(query)

  private def defineString(): Unit =
    if string == null then
      val sb = new java.lang.StringBuilder()
      if scheme != null then sb.append(scheme).append(':')
      if isOpaque() then sb.append(schemeSpecificPart) else appendHierarchy(sb)
      if fragment != null then sb.append('#').append(fragment)
      string = sb.toString

  /** The RFC 2396 parser, with the JDK's deviations, filling in `uri`'s components. */
  private class Parser(input: String, uri: URI):
    private val n = input.length
    private var ipv6ByteCount = 0

    private def fail(reason: String): Nothing = throw new URISyntaxException(input, reason)
    private def fail(reason: String, p: Int): Nothing = throw new URISyntaxException(input, reason, p)
    private def failExpecting(expected: String, p: Int): Nothing = fail("Expected " + expected, p)

    private def at(start: Int, end: Int, c: Char): Boolean = start < end && input.charAt(start) == c
    private def at(start: Int, end: Int, s: String): Boolean =
      start + s.length <= end && input.startsWith(s, start)

    /** `start + 1` if the char at `start` is `c`, else `start`. */
    private def scan(start: Int, end: Int, c: Char): Int =
      if start < end && input.charAt(start) == c then start + 1 else start

    /** The index of the first char of `stop` at or after `start`, or -1 if a char of `err`
     *  comes first, or `end`.
     */
    private def scan(start: Int, end: Int, err: String, stop: String): Int =
      var p = start
      while p < end do
        val c = input.charAt(p)
        if err.indexOf(c) >= 0 then return -1
        if stop.indexOf(c) >= 0 then return p
        p += 1
      p

    private def scan(start: Int, end: Int, stop: String): Int = scan(start, end, "", stop)

    /** Past an escape pair or a visible non-ASCII char at `start`, or `start`. */
    private def scanEscape(start: Int, end: Int, c: Char): Int =
      if c == '%' then
        if start + 3 <= end && URI.matches(input.charAt(start + 1), L_HEX, H_HEX) &&
          URI.matches(input.charAt(start + 2), L_HEX, H_HEX)
        then start + 3
        else fail("Malformed escape pair", start)
      else if c > 128 && !Character.isSpaceChar(c) && !Character.isISOControl(c) then start + 1
      else start

    /** Past the chars of the class (and escapes, if the class allows them). */
    private def scan(start: Int, end: Int, low: Long, high: Long): Int =
      var p = start
      var continue = true
      while continue && p < end do
        val c = input.charAt(p)
        if URI.matches(c, low, high) then p += 1
        else if (low & L_ESCAPED) != 0 then
          val q = scanEscape(p, end, c)
          if q > p then p = q else continue = false
        else continue = false
      p

    private def checkChars(start: Int, end: Int, low: Long, high: Long, what: String): Unit =
      val p = scan(start, end, low, high)
      if p < end then fail("Illegal character in " + what, p)

    private def checkChar(p: Int, low: Long, high: Long, what: String): Unit = checkChars(p, p + 1, low, high, what)

    def parse(requireServerAuthority: Boolean): Unit =
      uri.string = input
      var ssp = 0
      var p = scan(0, n, "/?#", ":")
      if p >= 0 && at(p, n, ':') then
        if p == 0 then failExpecting("scheme name", 0)
        checkChar(0, L_ALPHA, H_ALPHA, "scheme name")
        checkChars(1, p, L_SCHEME, H_SCHEME, "scheme name")
        uri.scheme = input.substring(0, p)
        p += 1
        ssp = p
        if at(p, n, '/') then p = parseHierarchical(p, requireServerAuthority)
        else
          val q = scan(p, n, "#")
          if q <= p then failExpecting("scheme-specific part", p)
          checkChars(p, q, L_URIC, H_URIC, "opaque part")
          p = q
      else p = parseHierarchical(0, requireServerAuthority)
      uri.schemeSpecificPart = input.substring(ssp, p)
      if at(p, n, '#') then
        checkChars(p + 1, n, L_URIC, H_URIC, "fragment")
        uri.fragment = input.substring(p + 1, n)
        p = n
      if p < n then fail("end of URI", p)

    private def parseHierarchical(start: Int, requireServerAuthority: Boolean): Int =
      var p = start
      if at(p, n, '/') && at(p + 1, n, '/') then
        p += 2
        val q = scan(p, n, "/?#")
        if q > p then p = parseAuthority(p, q, requireServerAuthority)
        else if q >= n then failExpecting("authority", p)
        // An empty authority before a path, query or fragment is allowed (a deviation).
      val q = scan(p, n, "?#")
      checkChars(p, q, L_PATH, H_PATH, "path")
      uri.path = input.substring(p, q)
      p = q
      if at(p, n, '?') then
        p += 1
        val q = scan(p, n, "#")
        checkChars(p, q, L_URIC, H_URIC, "query")
        uri.query = input.substring(p, q)
        p = q
      p

    private def parseAuthority(start: Int, end: Int, requireServerAuthority: Boolean): Int =
      val serverChars =
        if scan(start, end, "]") > start then scan(start, end, L_SERVER_PERCENT, H_SERVER_PERCENT) == end
        else scan(start, end, L_SERVER, H_SERVER) == end
      val regEnd = scan(start, end, L_REG_NAME, H_REG_NAME)
      val regChars = regEnd == end
      if regChars && !serverChars then
        uri.authority = input.substring(start, end)
        return end
      var q = start
      var failure: URISyntaxException = null
      if serverChars then
        try
          q = parseServer(start, end)
          if q < end then failExpecting("end of authority", q)
          uri.authority = input.substring(start, end)
        catch
          case x: URISyntaxException =>
            uri.userInfo = null
            uri.host = null
            uri.port = -1
            if requireServerAuthority then throw x
            failure = x
            q = start
      if q < end then
        if regChars then uri.authority = input.substring(start, end)
        else if failure != null then throw failure
        else fail("Illegal character in authority", regEnd)
      end

    private def parseServer(start: Int, end: Int): Int =
      var p = start
      var q = scan(p, end, "/?#", "@")
      if q >= p && at(q, end, '@') then
        checkChars(p, q, L_USERINFO, H_USERINFO, "user info")
        uri.userInfo = input.substring(p, q)
        p = q + 1
      if at(p, end, '[') then
        p += 1
        q = scan(p, end, "/?#", "]")
        if q > p && at(q, end, ']') then
          val r = scan(p, q, "%")
          if r > p then
            parseIPv6Reference(p, r)
            if r + 1 == q then fail("scope id expected")
            checkChars(r + 1, q, L_SCOPE_ID, H_SCOPE_ID, "scope id")
          else parseIPv6Reference(p, q)
          uri.host = input.substring(p - 1, q + 1)
          p = q + 1
        else failExpecting("closing bracket for IPv6 address", q)
      else
        q = parseIPv4Address(p, end)
        if q <= p then q = parseHostname(p, end)
        p = q
      if at(p, end, ':') then
        p += 1
        q = scan(p, end, "/")
        if q > p then
          checkChars(p, q, L_DIGIT, H_DIGIT, "port number")
          try uri.port = Integer.parseInt(input.substring(p, q))
          catch case _: NumberFormatException => fail("Malformed port number", p)
          p = q
      if p < end then failExpecting("port number", p)
      p

    /** Past a decimal number of at most 255, or `start`. */
    private def scanByte(start: Int, end: Int): Int =
      val q = scan(start, end, L_DIGIT, H_DIGIT)
      if q <= start then q
      else if Integer.parseInt(input.substring(start, q)) > 255 then start
      else q

    private def scanIPv4Address(start: Int, end: Int, strict: Boolean): Int =
      var p = start
      val m = scan(p, end, L_DIGIT | L_DOT, H_DIGIT | H_DOT)
      if m <= p || (strict && m != end) then return -1
      var q = p
      var parts = 0
      var ok = true
      // Four bytes separated by dots, each at most 255.
      while ok && parts < 7 do
        q = if parts % 2 == 0 then scanByte(p, m) else scan(p, m, '.')
        if q <= p then ok = false
        else
          p = q
          parts += 1
      if ok && q >= m then return q
      fail("Malformed IPv4 address", q)

    private def takeIPv4Address(start: Int, end: Int, expected: String): Int =
      val p = scanIPv4Address(start, end, strict = true)
      if p <= start then failExpecting(expected, start)
      p

    private def parseIPv4Address(start: Int, end: Int): Int =
      var p =
        try scanIPv4Address(start, end, strict = false)
        catch
          case _: URISyntaxException    => -1
          case _: NumberFormatException => -1
      if p > start && p < end && input.charAt(p) != ':' then p = -1
      if p > start then uri.host = input.substring(start, p)
      p

    private def parseHostname(start: Int, end: Int): Int =
      var p = start
      var lastLabel = -1
      var continue = true
      while continue do
        var q = scan(p, end, L_ALPHANUM, H_ALPHANUM)
        if q <= p then continue = false
        else
          lastLabel = p
          p = q
          q = scan(p, end, L_ALPHANUM | L_DASH, H_ALPHANUM | H_DASH)
          if q > p then
            if input.charAt(q - 1) == '-' then fail("Illegal character in hostname", q - 1)
            p = q
          q = scan(p, end, '.')
          if q <= p then continue = false
          else
            p = q
            continue = p < end
      if p < end && !at(p, end, ':') then fail("Illegal character in hostname", p)
      if lastLabel < 0 then failExpecting("hostname", start)
      // The last label of a qualified name starts with a letter.
      if lastLabel > start && !URI.matches(input.charAt(lastLabel), L_ALPHA, H_ALPHA) then
        fail("Illegal character in hostname", lastLabel)
      uri.host = input.substring(start, p)
      p

    private def parseIPv6Reference(start: Int, end: Int): Int =
      var p = start
      var compressedZeros = false
      val q = scanHexSeq(p, end)
      if q > p then
        p = q
        if at(p, end, "::") then
          compressedZeros = true
          p = scanHexPost(p + 2, end)
        else if at(p, end, ':') then
          p = takeIPv4Address(p + 1, end, "IPv4 address")
          ipv6ByteCount += 4
      else if at(p, end, "::") then
        compressedZeros = true
        p = scanHexPost(p + 2, end)
      if p < end then fail("Malformed IPv6 address", start)
      if ipv6ByteCount > 16 then fail("IPv6 address too long", start)
      if !compressedZeros && ipv6ByteCount < 16 then fail("IPv6 address too short", start)
      if compressedZeros && ipv6ByteCount == 16 then fail("Malformed IPv6 address", start)
      p

    private def scanHexPost(start: Int, end: Int): Int =
      if start == end then return start
      val q = scanHexSeq(start, end)
      if q > start then
        if at(q, end, ':') then
          val r = takeIPv4Address(q + 1, end, "hex digits or IPv4 address")
          ipv6ByteCount += 4
          r
        else q
      else
        val r = takeIPv4Address(start, end, "hex digits or IPv4 address")
        ipv6ByteCount += 4
        r

    /** Past a sequence of hex groups separated by single colons, or -1. */
    private def scanHexSeq(start: Int, end: Int): Int =
      var p = start
      var q = scan(p, end, L_HEX, H_HEX)
      if q <= p || at(q, end, '.') then return -1
      if q > p + 4 then fail("IPv6 hexadecimal digit sequence too long", p)
      ipv6ByteCount += 2
      p = q
      var continue = true
      while continue && p < end do
        if !at(p, end, ':') || at(p + 1, end, ':') then continue = false
        else
          p += 1
          q = scan(p, end, L_HEX, H_HEX)
          if q <= p then failExpecting("digits for an IPv6 address", p)
          if at(q, end, '.') then
            p -= 1
            continue = false
          else
            if q > p + 4 then fail("IPv6 hexadecimal digit sequence too long", p)
            ipv6ByteCount += 2
            p = q
      p
  end Parser

object URI:
  def create(str: String): URI =
    try new URI(str)
    catch case x: URISyntaxException => throw new IllegalArgumentException(x.getMessage(), x)

  // ---- Character classes (RFC 2396), as masks of the chars below and above 64 ----------------

  private def lowMask(chars: String): Long =
    var m = 0L
    for c <- chars if c < 64 do m |= 1L << c
    m

  private def highMask(chars: String): Long =
    var m = 0L
    for c <- chars if c >= 64 && c < 128 do m |= 1L << (c - 64)
    m

  private def lowMask(first: Char, last: Char): Long =
    var m = 0L
    for c <- first.max(0.toChar) to last.min(63.toChar) do m |= 1L << c
    m

  private def highMask(first: Char, last: Char): Long =
    var m = 0L
    for c <- first.max(64.toChar) to last.min(127.toChar) do m |= 1L << (c - 64)
    m

  private def matches(c: Char, low: Long, high: Long): Boolean =
    if c == 0 then false
    else if c < 64 then ((1L << c) & low) != 0
    else if c < 128 then ((1L << (c - 64)) & high) != 0
    else false

  private val L_DIGIT = lowMask('0', '9')
  private val H_DIGIT = 0L
  private val L_ALPHA = 0L
  private val H_ALPHA = highMask('a', 'z') | highMask('A', 'Z')
  private val L_ALPHANUM = L_DIGIT | L_ALPHA
  private val H_ALPHANUM = H_DIGIT | H_ALPHA
  private val L_HEX = L_DIGIT
  private val H_HEX = highMask('A', 'F') | highMask('a', 'f')
  private val L_MARK = lowMask("-_.!~*'()")
  private val H_MARK = highMask("-_.!~*'()")
  private val L_UNRESERVED = L_ALPHANUM | L_MARK
  private val H_UNRESERVED = H_ALPHANUM | H_MARK
  private val L_RESERVED = lowMask(";/?:@&=+$,[]")
  private val H_RESERVED = highMask(";/?:@&=+$,[]")
  /** Not a char: escapes and visible non-ASCII chars are allowed. */
  private val L_ESCAPED = 1L
  private val H_ESCAPED = 0L
  private val L_URIC = L_RESERVED | L_UNRESERVED | L_ESCAPED
  private val H_URIC = H_RESERVED | H_UNRESERVED | H_ESCAPED
  private val L_PCHAR = L_UNRESERVED | L_ESCAPED | lowMask(":@&=+$,")
  private val H_PCHAR = H_UNRESERVED | H_ESCAPED | highMask(":@&=+$,")
  private val L_PATH = L_PCHAR | lowMask(";/")
  private val H_PATH = H_PCHAR | highMask(";/")
  private val L_DASH = lowMask("-")
  private val H_DASH = highMask("-")
  private val L_DOT = lowMask(".")
  private val H_DOT = highMask(".")
  private val L_USERINFO = L_UNRESERVED | L_ESCAPED | lowMask(";:&=+$,")
  private val H_USERINFO = H_UNRESERVED | H_ESCAPED | highMask(";:&=+$,")
  private val L_REG_NAME = L_UNRESERVED | L_ESCAPED | lowMask("$,;:@&=+")
  private val H_REG_NAME = H_UNRESERVED | H_ESCAPED | highMask("$,;:@&=+")
  private val L_SERVER = L_USERINFO | L_ALPHANUM | L_DASH | lowMask(".:@[]")
  private val H_SERVER = H_USERINFO | H_ALPHANUM | H_DASH | highMask(".:@[]")
  private val L_SERVER_PERCENT = L_SERVER | lowMask("%")
  private val H_SERVER_PERCENT = H_SERVER | highMask("%")
  private val L_SCHEME = L_ALPHA | L_DIGIT | lowMask("+-.")
  private val H_SCHEME = H_ALPHA | H_DIGIT | highMask("+-.")
  private val L_SCOPE_ID = L_ALPHANUM | lowMask("_.")
  private val H_SCOPE_ID = H_ALPHANUM | highMask("_.")

  // ---- Building URIs from components ----------------------------------------------------------

  private def checkPath(s: String, scheme: String, path: String): Unit =
    if scheme != null && path != null && !path.isEmpty && path.charAt(0) != '/' then
      throw new URISyntaxException(s, "Relative path in absolute URI")

  /** The string of a URI's components, quoting what they must not contain. */
  private def build(scheme: String, opaquePart: String, authority: String, userInfo: String, host: String, port: Int,
      path: String, query: String, fragment: String): String =
    val sb = new java.lang.StringBuilder()
    if scheme != null then sb.append(scheme).append(':')
    if opaquePart != null then
      // A literal IPv6 address at the start is not quoted.
      if opaquePart.startsWith("//[") then
        val end = opaquePart.indexOf(']')
        if end != -1 && opaquePart.indexOf(':') != -1 then
          sb.append(opaquePart, 0, end + 1)
          sb.append(quote(opaquePart.substring(end + 1), L_URIC, H_URIC))
      else sb.append(quote(opaquePart, L_URIC, H_URIC))
    else
      if host != null then
        sb.append("//")
        if userInfo != null then sb.append(quote(userInfo, L_USERINFO, H_USERINFO)).append('@')
        val brackets = host.indexOf(':') >= 0 && !host.startsWith("[") && !host.endsWith("]")
        if brackets then sb.append('[')
        sb.append(host)
        if brackets then sb.append(']')
        if port != -1 then sb.append(':').append(port)
      else if authority != null then
        sb.append("//")
        val end = if authority.startsWith("[") then authority.indexOf(']') else -1
        if end != -1 && authority.indexOf(':') != -1 then
          sb.append(authority, 0, end + 1)
          sb.append(quote(authority.substring(end + 1), L_REG_NAME | L_SERVER, H_REG_NAME | H_SERVER))
        else sb.append(quote(authority, L_REG_NAME | L_SERVER, H_REG_NAME | H_SERVER))
      if path != null then sb.append(quote(path, L_PATH, H_PATH))
      if query != null then sb.append('?').append(quote(query, L_URIC, H_URIC))
    if fragment != null then sb.append('#').append(quote(fragment, L_URIC, H_URIC))
    sb.toString

  private val hexDigits = "0123456789ABCDEF"

  private def appendEscape(sb: java.lang.StringBuilder, b: Byte): Unit =
    sb.append('%').append(hexDigits.charAt((b >> 4) & 0x0f)).append(hexDigits.charAt(b & 0x0f))

  private def appendEncoded(sb: java.lang.StringBuilder, s: String): Unit =
    for b <- s.getBytes(StandardCharsets.UTF_8) do
      if (b & 0xff) >= 0x80 then appendEscape(sb, b) else sb.append((b & 0xff).toChar)

  /** Escapes the ASCII chars that are not in the class, and, if it allows escapes, non-ASCII
   *  space and control chars.
   */
  private def quote(s: String, low: Long, high: Long): String =
    val allowNonASCII = (low & L_ESCAPED) != 0
    var sb: java.lang.StringBuilder = null
    for i <- 0 until s.length do
      val c = s.charAt(i)
      val escape =
        if c < '\u0080' then !matches(c, low, high)
        else allowNonASCII && (Character.isSpaceChar(c) || Character.isISOControl(c))
      if escape then
        if sb == null then sb = new java.lang.StringBuilder().append(s, 0, i)
        if c < '\u0080' then appendEscape(sb, c.toByte) else appendEncoded(sb, String.valueOf(c))
      else if sb != null then sb.append(c)
    if sb == null then s else sb.toString

  /** Escapes the non-ASCII chars, as UTF-8 (`toASCIIString`). */
  private def encodeNonASCII(s: String): String =
    var i = 0
    while i < s.length && s.charAt(i) < '\u0080' do i += 1
    if i == s.length then s
    else
      val sb = new java.lang.StringBuilder()
      for b <- s.getBytes(StandardCharsets.UTF_8) do
        if (b & 0xff) >= 0x80 then appendEscape(sb, b) else sb.append((b & 0xff).toChar)
      sb.toString

  private def hexValue(c: Char): Int =
    if c >= '0' && c <= '9' then c - '0'
    else if c >= 'a' && c <= 'f' then c - 'a' + 10
    else c - 'A' + 10

  /** Decodes escapes as UTF-8, replacing malformed input; escapes within brackets (scope ids of
   *  IPv6 literals) stay as they are unless told otherwise.
   */
  private def decode(s: String, ignorePercentInBrackets: Boolean = true): String =
    if s == null || s.indexOf('%') < 0 then return s
    val n = s.length
    val sb = new java.lang.StringBuilder(n)
    val bytes = ByteBuffer.allocate(n)
    val chars = CharBuffer.allocate(n)
    val decoder = StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPLACE)
      .onUnmappableCharacter(CodingErrorAction.REPLACE)
    var inBrackets = false
    var i = 0
    while i < n do
      val c = s.charAt(i)
      if c == '[' then inBrackets = true
      else if inBrackets && c == ']' then inBrackets = false
      if c != '%' || (inBrackets && ignorePercentInBrackets) then
        sb.append(c)
        i += 1
      else
        bytes.clear()
        while i < n && s.charAt(i) == '%' do
          bytes.put(((hexValue(s.charAt(i + 1)) << 4) | hexValue(s.charAt(i + 2))).toByte)
          i += 3
        bytes.flip()
        chars.clear()
        decoder.reset()
        decoder.decode(bytes, chars, true)
        decoder.flush(chars)
        sb.append(chars.flip().toString)
    sb.toString

  // ---- Normalization and resolution (RFC 2396, 5.2) -------------------------------------------

  /** Removes "." segments, "segment/.." pairs and redundant slashes; a leading ".." stays unless
   *  `removeLeading`. A relative result whose first segment has a colon gets a "./" prefix, so
   *  it does not read as a scheme. Returns `ps` itself if it is normal.
   */
  private def normalizePath(ps: String, removeLeading: Boolean): String =
    val ns = needsNormalization(ps)
    if ns < 0 then return ps
    val path = ps.toCharArray
    val segs = new Array[Int](ns)
    split(path, segs)
    removeDots(path, segs, removeLeading)
    maybeAddLeadingDot(path, segs)
    val s = new String(path, 0, join(path, segs))
    if s == ps then ps else s

  /** The number of segments of a path that may need normalization, or -1. */
  private def needsNormalization(path: String): Int =
    var normal = true
    var ns = 0
    val end = path.length - 1
    var p = 0
    while p <= end && path.charAt(p) == '/' do p += 1
    if p > 1 then normal = false
    while p <= end do
      if path.charAt(p) == '.' && (p == end || path.charAt(p + 1) == '/' ||
          (path.charAt(p + 1) == '.' && (p + 1 == end || path.charAt(p + 2) == '/')))
      then normal = false
      ns += 1
      var inSegment = true
      while inSegment && p <= end do
        p += 1
        if path.charAt(p - 1) == '/' then
          while p <= end && path.charAt(p) == '/' do
            normal = false
            p += 1
          inSegment = false
    if normal then -1 else ns

  /** Replaces the slashes with '\u0000' and records where each segment starts. */
  private def split(path: Array[Char], segs: Array[Int]): Unit =
    val end = path.length - 1
    var p = 0
    var i = 0
    while p <= end && path(p) == '/' do
      path(p) = '\u0000'
      p += 1
    while p <= end do
      segs(i) = p
      i += 1
      p += 1
      var inSegment = true
      while inSegment && p <= end do
        p += 1
        if path(p - 1) == '/' then
          path(p - 1) = '\u0000'
          while p <= end && path(p) == '/' do
            path(p) = '\u0000'
            p += 1
          inSegment = false

  /** Joins the segments that remain (not -1), with slashes; the length of the result. */
  private def join(path: Array[Char], segs: Array[Int]): Int =
    val end = path.length - 1
    var p = 0
    if path(p) == '\u0000' then
      path(p) = '/' // an absolute path
      p += 1
    for q0 <- segs if q0 != -1 do
      var q = q0
      if p == q then
        while p <= end && path(p) != '\u0000' do p += 1
        if p <= end then
          path(p) = '/' // a trailing slash
          p += 1
      else
        while q <= end && path(q) != '\u0000' do
          path(p) = path(q)
          p += 1
          q += 1
        if q <= end then
          path(p) = '/'
          p += 1
    p

  private def removeDots(path: Array[Char], segs: Array[Int], removeLeading: Boolean): Unit =
    val ns = segs.length
    val end = path.length - 1
    var i = 0
    var done = false
    while !done && i < ns do
      var dots = 0
      var found = false
      while !found && i < ns do
        val p = segs(i)
        if path(p) == '.' then
          if p == end || path(p + 1) == '\u0000' then
            dots = 1
            found = true
          else if path(p + 1) == '.' && (p + 1 == end || path(p + 2) == '\u0000') then
            dots = 2
            found = true
        if !found then i += 1
      if !found then done = true
      else
        if dots == 1 then segs(i) = -1
        else
          var j = i - 1
          while j >= 0 && segs(j) == -1 do j -= 1
          if j >= 0 then
            val q = segs(j)
            if !(path(q) == '.' && path(q + 1) == '.' && path(q + 2) == '\u0000') then
              segs(i) = -1
              segs(j) = -1
          else if removeLeading then segs(i) = -1
        i += 1

  private def maybeAddLeadingDot(path: Array[Char], segs: Array[Int]): Unit =
    if path(0) == '\u0000' then return // absolute
    val ns = segs.length
    var f = 0
    while f < ns && segs(f) < 0 do f += 1
    if f >= ns || f == 0 then return
    var p = segs(f)
    while p < path.length && path(p) != ':' && path(p) != '\u0000' do p += 1
    if p >= path.length || path(p) == '\u0000' then return
    // The first segment is free: make it ".".
    path(0) = '.'
    path(1) = '\u0000'
    segs(0) = 0

  private def resolvePath(base: String, child: String, absolute: Boolean): String =
    val i = base.lastIndexOf('/')
    val path =
      if child.isEmpty then (if i >= 0 then base.substring(0, i + 1) else "")
      else if i >= 0 || !absolute then base.substring(0, i + 1) + child
      else "/" + child
    normalizePath(path, removeLeading = false)

  private def resolve(base: URI, child: URI): URI =
    if child.isOpaque() || base.isOpaque() then return child
    // A reference to the current document: a lone fragment.
    if child.scheme == null && child.authority == null && child.path.isEmpty && child.fragment != null &&
      child.query == null
    then
      if base.fragment != null && child.fragment == base.fragment then return base
      val ru = base.copyHierarchy()
      ru.fragment = child.fragment
      return ru
    if child.scheme != null then return child
    val ru = new URI()
    ru.scheme = base.scheme
    ru.query = child.query
    ru.fragment = child.fragment
    if child.authority == null then
      ru.authority = base.authority
      ru.host = base.host
      ru.userInfo = base.userInfo
      ru.port = base.port
      val cp = child.path
      ru.path =
        if !cp.isEmpty && cp.charAt(0) == '/' then cp
        else resolvePath(base.path, cp, base.isAbsolute())
    else
      ru.authority = child.authority
      ru.host = child.host
      ru.userInfo = child.userInfo
      ru.port = child.port
      ru.path = child.path
    ru

  // ---- Comparisons: escapes compare without case ----------------------------------------------

  private def toLower(c: Char): Int = if c >= 'A' && c <= 'Z' then c + ('a' - 'A') else c
  private def toUpper(c: Char): Int = if c >= 'a' && c <= 'z' then c - ('a' - 'A') else c

  private def equal(s: String, t: String): Boolean =
    if s eq t then true
    else if s == null || t == null || s.length != t.length then false
    else if s.indexOf('%') < 0 then s == t
    else
      var i = 0
      while i < s.length do
        val c = s.charAt(i)
        val d = t.charAt(i)
        if c != '%' then
          if c != d then return false
          i += 1
        else
          if d != '%' then return false
          if toLower(s.charAt(i + 1)) != toLower(t.charAt(i + 1)) ||
            toLower(s.charAt(i + 2)) != toLower(t.charAt(i + 2))
          then return false
          i += 3
      true

  private def equalIgnoringCase(s: String, t: String): Boolean =
    if s eq t then true
    else if s == null || t == null || s.length != t.length then false
    else (0 until s.length).forall(i => toLower(s.charAt(i)) == toLower(t.charAt(i)))

  private def hash(h: Int, s: String): Int =
    if s == null then h
    else if s.indexOf('%') < 0 then h * 127 + s.hashCode
    else
      var x = 0
      var i = 0
      while i < s.length do
        val c = s.charAt(i)
        x = 31 * x + c
        if c == '%' then
          x = 31 * x + toUpper(s.charAt(i + 1))
          x = 31 * x + toUpper(s.charAt(i + 2))
          i += 2
        i += 1
      h * 127 + x

  private def hashIgnoringCase(h: Int, s: String): Int =
    if s == null then h
    else
      var x = h
      for i <- 0 until s.length do x = 31 * x + toLower(s.charAt(i))
      x

  /** Compares strings, with escapes compared without case. */
  private def compare(s: String, t: String): Int =
    if s eq t then 0
    else if s == null then -1
    else if t == null then 1
    else if s.indexOf('%') < 0 && t.indexOf('%') < 0 then s.compareTo(t)
    else upperEscapes(s).compareTo(upperEscapes(t))

  private def upperEscapes(s: String): String =
    val chars = s.toCharArray
    var i = s.indexOf('%')
    while i >= 0 && i + 2 < chars.length do
      chars(i + 1) = toUpper(chars(i + 1)).toChar
      chars(i + 2) = toUpper(chars(i + 2)).toChar
      i = s.indexOf('%', i + 3)
    new String(chars)

  private def compareIgnoringCase(s: String, t: String): Int =
    if s eq t then 0
    else if s == null then -1
    else if t == null then 1
    else
      val n = s.length.min(t.length)
      var i = 0
      while i < n do
        val c = toLower(s.charAt(i)) - toLower(t.charAt(i))
        if c != 0 then return c
        i += 1
      s.length - t.length
