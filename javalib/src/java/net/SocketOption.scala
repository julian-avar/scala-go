package java.net

import scala.annotation.static

trait SocketOption[T]:
  def name(): String
  def `type`(): Class[T]

/** The standard socket options; scala-go's sockets support those of TCP. */
object StandardSocketOptions:
  private final class Std[T](optionName: String, tpe: Class[T]) extends SocketOption[T]:
    def name(): String = optionName
    def `type`(): Class[T] = tpe
    override def toString(): String = optionName

  private val bool = classOf[java.lang.Boolean]
  private val int = classOf[java.lang.Integer]

  val SO_BROADCAST: SocketOption[java.lang.Boolean] = new Std("SO_BROADCAST", bool)
  val SO_KEEPALIVE: SocketOption[java.lang.Boolean] = new Std("SO_KEEPALIVE", bool)
  val SO_SNDBUF: SocketOption[java.lang.Integer] = new Std("SO_SNDBUF", int)
  val SO_RCVBUF: SocketOption[java.lang.Integer] = new Std("SO_RCVBUF", int)
  val SO_REUSEADDR: SocketOption[java.lang.Boolean] = new Std("SO_REUSEADDR", bool)
  val SO_REUSEPORT: SocketOption[java.lang.Boolean] = new Std("SO_REUSEPORT", bool)
  val SO_LINGER: SocketOption[java.lang.Integer] = new Std("SO_LINGER", int)
  val IP_TOS: SocketOption[java.lang.Integer] = new Std("IP_TOS", int)
  val IP_MULTICAST_TTL: SocketOption[java.lang.Integer] = new Std("IP_MULTICAST_TTL", int)
  val IP_MULTICAST_LOOP: SocketOption[java.lang.Boolean] = new Std("IP_MULTICAST_LOOP", bool)
  val TCP_NODELAY: SocketOption[java.lang.Boolean] = new Std("TCP_NODELAY", bool)

/** A proxy setting. Connections only go direct ([[Proxy.NO_PROXY]]). */
class Proxy(tpe: Proxy.Type, sa: SocketAddress):
  if tpe == Proxy.Type.DIRECT || !sa.isInstanceOf[InetSocketAddress] then
    if tpe != Proxy.Type.DIRECT || sa != null then
      throw new IllegalArgumentException("type " + tpe + " is not compatible with address " + sa)

  def `type`(): Proxy.Type = tpe
  def address(): SocketAddress = sa

  override def toString(): String = if tpe == Proxy.Type.DIRECT then "DIRECT" else tpe.toString + " @ " + sa

  override def equals(obj: Any): Boolean = obj match
    case p: Proxy => p.`type`() == tpe && java.util.Objects.equals(p.address(), sa)
    case _        => false

  override def hashCode(): Int = if sa == null then tpe.hashCode() else tpe.hashCode() + sa.hashCode()

object Proxy:
  final class Type private (name: String, ordinal: Int) extends scalago.javalib.lang.Enum[Type](name, ordinal)

  object Type:
    @static val DIRECT: Type = new Type("DIRECT", 0)
    @static val HTTP: Type = new Type("HTTP", 1)
    @static val SOCKS: Type = new Type("SOCKS", 2)

    @static def values(): Array[Type] = Array(DIRECT, HTTP, SOCKS)

    @static def valueOf(name: String): Type =
      if name == null then throw new NullPointerException("Name is null")
      values().find(_.name() == name).getOrElse(
        throw new IllegalArgumentException("No enum constant java.net.Proxy.Type." + name))

  val NO_PROXY: Proxy = new Proxy(Type.DIRECT, null)
