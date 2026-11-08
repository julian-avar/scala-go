package java.net

abstract class SocketAddress extends java.io.Serializable

/** An IP address (or an unresolved host name) and a port. */
class InetSocketAddress private (target: Object, port: Int) extends SocketAddress:
  /** The address, or null if unresolved. */
  private val addr = target match
    case a: InetAddress => a
    case _              => null
  /** The host name of an unresolved address. */
  private val hostname = target match
    case s: String => s
    case _         => null

  def this(addr: InetAddress, port: Int) =
    this((if addr == null then InetAddress.anyLocal else addr): Object, InetSocketAddress.checkPort(port))

  def this(port: Int) = this(null: InetAddress, port)

  /** Resolves the host name; the address is unresolved if that fails. */
  def this(hostname: String, port: Int) =
    this(InetSocketAddress.resolve(hostname), InetSocketAddress.checkPort(port))

  final def getPort(): Int = port
  final def getAddress(): InetAddress = addr
  final def isUnresolved(): Boolean = addr == null

  /** The host name, looked up in reverse if this address was made from an address literal. */
  final def getHostName(): String =
    if hostname != null then hostname else addr.getHostName()

  /** The host name or the address's text, without a reverse lookup. */
  final def getHostString(): String =
    if hostname != null then hostname
    else if addr.originalHostName != null then addr.originalHostName
    else addr.getHostAddress()

  override def toString(): String =
    val formatted =
      if isUnresolved() then hostname + "/<unresolved>"
      else
        val s = addr.toString()
        if addr.isInstanceOf[Inet6Address] then
          val i = s.lastIndexOf('/')
          s.substring(0, i + 1) + "[" + s.substring(i + 1) + "]"
        else s
    formatted + ":" + port

  override def equals(obj: Any): Boolean = obj match
    case that: InetSocketAddress =>
      port == that.getPort() && (
        if addr != null then addr == that.getAddress()
        else that.getAddress() == null && hostname.equalsIgnoreCase(that.getHostString())
      )
    case _ => false

  override def hashCode(): Int =
    if addr != null then addr.hashCode() + port
    else hostname.toLowerCase().hashCode() + port

object InetSocketAddress:
  private def checkPort(port: Int): Int =
    if port < 0 || port > 0xffff then throw new IllegalArgumentException("port out of range:" + port)
    port

  private def checkHost(hostname: String): String =
    if hostname == null then throw new IllegalArgumentException("hostname can't be null")
    hostname

  /** The address of the host, or its name if it cannot be resolved. */
  private def resolve(hostname: String): Object =
    checkHost(hostname)
    try InetAddress.getByName(hostname)
    catch case _: UnknownHostException => hostname

  def createUnresolved(host: String, port: Int): InetSocketAddress =
    new InetSocketAddress(checkHost(host): Object, checkPort(port))
