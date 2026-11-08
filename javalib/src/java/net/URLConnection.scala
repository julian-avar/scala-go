package java.net

import java.io.{InputStream, OutputStream}
import java.util.{ArrayList, Collections, List as JList, Map as JMap, TreeMap}

/** A connection to the resource of a URL: request properties, timeouts, and the response's
 *  header fields, which subclasses provide through `getHeaderField(String)` and
 *  `getHeaderFields()`.
 */
abstract class URLConnection protected (protected var url: URL):
  protected var doInput: Boolean = true
  protected var doOutput: Boolean = false
  protected var allowUserInteraction: Boolean = false
  protected var useCaches: Boolean = URLConnection.defaultUseCaches
  protected var ifModifiedSince: Long = 0L
  protected var connected: Boolean = false
  private var connectTimeout = 0
  private var readTimeout = 0
  /** The request properties, in the order they were set (names keep their case). */
  private val requests = new ArrayList[Array[String]]()

  def connect(): Unit

  def getURL(): URL = url

  def setConnectTimeout(timeout: Int): Unit =
    if timeout < 0 then throw new IllegalArgumentException("timeouts can't be negative")
    connectTimeout = timeout

  def getConnectTimeout(): Int = connectTimeout

  def setReadTimeout(timeout: Int): Unit =
    if timeout < 0 then throw new IllegalArgumentException("timeouts can't be negative")
    readTimeout = timeout

  def getReadTimeout(): Int = readTimeout

  def getContentLength(): Int =
    val l = getContentLengthLong()
    if l > Int.MaxValue then -1 else l.toInt

  def getContentLengthLong(): Long = getHeaderFieldLong("content-length", -1)
  def getContentType(): String = getHeaderField("content-type")
  def getContentEncoding(): String = getHeaderField("content-encoding")
  def getExpiration(): Long = getHeaderFieldDate("expires", 0)
  def getDate(): Long = getHeaderFieldDate("date", 0)
  def getLastModified(): Long = getHeaderFieldDate("last-modified", 0)

  def getHeaderField(name: String): String = null
  def getHeaderFields(): JMap[String, JList[String]] = Collections.emptyMap()

  def getHeaderFieldInt(name: String, default: Int): Int =
    try Integer.parseInt(getHeaderField(name))
    catch case _: Exception => default

  def getHeaderFieldLong(name: String, default: Long): Long =
    try java.lang.Long.parseLong(getHeaderField(name))
    catch case _: Exception => default

  /** An HTTP date field (RFC 1123, as servers send them) in milliseconds. */
  def getHeaderFieldDate(name: String, default: Long): Long =
    val value = getHeaderField(name)
    try
      java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
        .toInstant.toEpochMilli
    catch case _: Exception => default

  def getHeaderFieldKey(n: Int): String = null
  def getHeaderField(n: Int): String = null

  def getContent(): Object = throw new UnknownServiceException("scala-go: no content handlers")

  def getInputStream(): InputStream = throw new UnknownServiceException("protocol doesn't support input")
  def getOutputStream(): OutputStream = throw new UnknownServiceException("protocol doesn't support output")

  override def toString(): String = getClass().getName() + ":" + url

  private def checkNotConnected(): Unit = if connected then throw new IllegalStateException("Already connected")

  def setDoInput(doinput: Boolean): Unit =
    checkNotConnected()
    doInput = doinput

  def getDoInput(): Boolean = doInput

  def setDoOutput(dooutput: Boolean): Unit =
    checkNotConnected()
    doOutput = dooutput

  def getDoOutput(): Boolean = doOutput

  def setAllowUserInteraction(allow: Boolean): Unit =
    checkNotConnected()
    allowUserInteraction = allow

  def getAllowUserInteraction(): Boolean = allowUserInteraction

  def setUseCaches(usecaches: Boolean): Unit =
    checkNotConnected()
    useCaches = usecaches

  def getUseCaches(): Boolean = useCaches

  def setIfModifiedSince(ifmodifiedsince: Long): Unit =
    checkNotConnected()
    ifModifiedSince = ifmodifiedsince

  def getIfModifiedSince(): Long = ifModifiedSince

  def setDefaultUseCaches(defaultusecaches: Boolean): Unit = URLConnection.defaultUseCaches = defaultusecaches
  def getDefaultUseCaches(): Boolean = URLConnection.defaultUseCaches

  /** Sets a request property, replacing the values it had. */
  def setRequestProperty(key: String, value: String): Unit =
    checkNotConnected()
    if key == null then throw new NullPointerException("key is null")
    requests.removeIf(_(0).equalsIgnoreCase(key))
    requests.add(Array(key, value))

  def addRequestProperty(key: String, value: String): Unit =
    checkNotConnected()
    if key == null then throw new NullPointerException("key is null")
    requests.add(Array(key, value))

  def getRequestProperty(key: String): String =
    checkNotConnected()
    if key == null then null
    else
      var value: String = null
      requests.forEach(r => if value == null && r(0).equalsIgnoreCase(key) then value = r(1))
      value

  def getRequestProperties(): JMap[String, JList[String]] =
    checkNotConnected()
    URLConnection.fieldMap(requestList)

  /** The request properties, in order. */
  private[net] def requestList: JList[Array[String]] = requests

object URLConnection:
  @volatile private var defaultUseCaches = true

  def guessContentTypeFromName(fname: String): String =
    val dot = fname.lastIndexOf('.')
    if dot < 0 then null
    else
      fname.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) match
        case "txt" | "text" | "java" | "c" | "h" => "text/plain"
        case "html" | "htm"                      => "text/html"
        case "xml"                               => "application/xml"
        case "json"                              => "application/json"
        case "css"                               => "text/css"
        case "js"                                => "text/javascript"
        case "png"                               => "image/png"
        case "jpg" | "jpeg"                      => "image/jpeg"
        case "gif"                               => "image/gif"
        case "pdf"                               => "application/pdf"
        case "zip"                               => "application/zip"
        case _                                   => null

  def getDefaultUseCaches(protocol: String): Boolean = defaultUseCaches
  def setDefaultUseCaches(protocol: String, defaultVal: Boolean): Unit = defaultUseCaches = defaultVal

  /** Name/value pairs as a map from names to their values (the null name sorts first). */
  private[net] def fieldMap(fields: JList[Array[String]]): JMap[String, JList[String]] =
    val map = new TreeMap[String, JList[String]](java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder[String]()))
    fields.forEach { f =>
      var values = map.get(f(0))
      if values == null then
        values = new ArrayList[String]()
        map.put(f(0), values)
      values.add(f(1))
    }
    map.replaceAll((_, v) => Collections.unmodifiableList(v))
    Collections.unmodifiableMap(map)

/** A file: URL: the file's contents, or a directory's entries one per line. */
private[net] final class FileURLConnection(u: URL) extends URLConnection(u):
  private lazy val file = new java.io.File(URLDecoder.decode(u.getPath().replace("+", "%2B"), "UTF-8"))
  private var in: InputStream = null

  def connect(): Unit =
    if !connected then
      in =
        if file.isDirectory() then
          val names = file.list()
          java.util.Arrays.sort(names.asInstanceOf[Array[Object]])
          val sb = new java.lang.StringBuilder()
          for n <- names do sb.append(n).append('\n')
          new java.io.ByteArrayInputStream(sb.toString.getBytes("UTF-8"))
        else new java.io.FileInputStream(file)
      connected = true

  override def getInputStream(): InputStream =
    connect()
    in

  override def getHeaderField(name: String): String =
    try connect()
    catch case _: java.io.IOException => return null
    name.toLowerCase(java.util.Locale.ROOT) match
      case "content-length" => String.valueOf(if file.isDirectory() then 0L else file.length())
      case "content-type" =>
        if file.isDirectory() then "text/plain"
        else
          val t = URLConnection.guessContentTypeFromName(file.getName())
          if t == null then "content/unknown" else t
      case _ => null

  override def getLastModified(): Long = file.lastModified()
