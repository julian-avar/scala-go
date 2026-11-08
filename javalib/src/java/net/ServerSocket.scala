package java.net

import java.io.IOException

import scalago.runtime.{Net, OS}

/** A listening TCP socket, on a Go listener (`scalago.runtime.Net`). `accept` honours the
 *  timeout (`setSoTimeout`), and closing the socket from another thread ends a blocked accept
 *  with a `SocketException`, as on the JDK. Listening on the wildcard address accepts IPv4 and
 *  IPv6 connections.
 */
class ServerSocket extends java.io.Closeable:
  @volatile private var handle: Object = null // the listener, once bound
  private var bindAddress: InetAddress = null
  @volatile private var closed = false
  @volatile private var soTimeout = 0
  private var reuseAddress = true

  def this(port: Int, backlog: Int, bindAddr: InetAddress) =
    this()
    if port < 0 || port > 0xffff then throw new IllegalArgumentException("Port value out of range: " + port)
    try bind(new InetSocketAddress(bindAddr, port), backlog)
    catch
      case e: IOException =>
        close()
        throw e

  def this(port: Int, backlog: Int) = this(port, backlog, null)
  def this(port: Int) = this(port, 50, null)

  def bind(endpoint: SocketAddress): Unit = bind(endpoint, 50)

  def bind(endpoint: SocketAddress, backlog: Int): Unit =
    if isClosed() then throw new SocketException("Socket is closed")
    if isBound() then throw new SocketException("Already bound")
    val epoint = endpoint match
      case null                 => new InetSocketAddress(0)
      case a: InetSocketAddress => a
      case _                    => throw new IllegalArgumentException("Unsupported address type")
    if epoint.isUnresolved() then throw new SocketException("Unresolved address")
    val addr = epoint.getAddress()
    val result = Net.listen(if addr.isAnyLocalAddress() then null else addr.address, epoint.getPort(), backlog)
    if OS.isError(result) then throw new BindException(Net.errorMessage(result))
    synchronized {
      if closed then
        Net.close(result)
        throw new SocketException("Socket is closed")
      handle = result
      bindAddress = if addr.isAnyLocalAddress() then InetAddress.anyLocal else addr
    }

  def getInetAddress(): InetAddress = if isBound() then bindAddress else null

  def getLocalPort(): Int =
    val h = handle
    if h == null then -1 else Net.port(h, true)

  def getLocalSocketAddress(): SocketAddress =
    if isBound() then new InetSocketAddress(getInetAddress(), getLocalPort()) else null

  /** Waits for a connection; "Accept timed out" after the timeout, if one is set. */
  def accept(): Socket =
    if isClosed() then throw new SocketException("Socket is closed")
    if !isBound() then throw new SocketException("Socket is not bound yet")
    val result = Net.accept(handle, soTimeout)
    if OS.isError(result) then
      Net.errorKind(result) match
        case Net.Timeout => throw new SocketTimeoutException("Accept timed out")
        case Net.Closed  => throw new SocketException("Socket closed")
        case _           => throw new SocketException(Net.errorMessage(result))
    new Socket(result, InetAddress.make(null, Net.address(result, false)))

  /** Closes the socket; an accept blocked in another thread fails with "Socket closed". */
  def close(): Unit =
    val h = synchronized {
      if closed then null
      else
        closed = true
        handle
    }
    if h != null then Net.close(h)

  def isBound(): Boolean = handle != null
  def isClosed(): Boolean = closed

  def setSoTimeout(timeout: Int): Unit =
    if isClosed() then throw new SocketException("Socket is closed")
    if timeout < 0 then throw new IllegalArgumentException("timeout < 0")
    soTimeout = timeout

  def getSoTimeout(): Int =
    if isClosed() then throw new SocketException("Socket is closed")
    soTimeout

  /** Recorded only: Go's listeners always reuse addresses (SO_REUSEADDR), as the JDK's do by
   *  default.
   */
  def setReuseAddress(on: Boolean): Unit =
    if isClosed() then throw new SocketException("Socket is closed")
    reuseAddress = on

  def getReuseAddress(): Boolean =
    if isClosed() then throw new SocketException("Socket is closed")
    reuseAddress

  def setReceiveBufferSize(size: Int): Unit =
    if size <= 0 then throw new IllegalArgumentException("negative receive size")
    if isClosed() then throw new SocketException("Socket is closed")

  def getReceiveBufferSize(): Int =
    if isClosed() then throw new SocketException("Socket is closed")
    val h = handle
    val actual = if h != null then Net.bufferSize(h, false) else 0
    if actual > 0 then actual else 65536

  def setPerformancePreferences(connectionTime: Int, latency: Int, bandwidth: Int): Unit = ()

  override def toString(): String =
    if !isBound() then "ServerSocket[unbound]"
    else s"ServerSocket[addr=${getInetAddress()},localport=${getLocalPort()}]"
