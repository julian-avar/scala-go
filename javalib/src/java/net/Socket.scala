package java.net

import java.io.{InputStream, IOException, OutputStream}

import scalago.runtime.{Net, OS}

/** A TCP client socket, on a Go connection (`scalago.runtime.Net`). Reads honour the timeout
 *  (`setSoTimeout`), and closing the socket from another thread ends a blocked read with a
 *  `SocketException`, as on the JDK.
 */
class Socket private[net] (handle0: Object, remote: InetAddress) extends java.io.Closeable:
  @volatile private var handle: Object = handle0 // the connection, once connected
  private var remoteAddress: InetAddress = remote
  private var bindpoint: InetSocketAddress = null
  @volatile private var closed = false
  @volatile private var inputShutdown = false
  @volatile private var outputShutdown = false
  @volatile private var soTimeout = 0
  private var tcpNoDelay = false
  private var keepAlive = false
  private var linger = -1
  private var receiveBufferSize = 0
  private var sendBufferSize = 0
  private var reuseAddress = false
  private var trafficClass = 0
  private var oobInline = false
  private var in: InputStream = null
  private var out: OutputStream = null

  def this() = this(null, null)

  def this(proxy: Proxy) =
    this(null, null)
    if proxy == null then throw new IllegalArgumentException("Invalid Proxy")
    if proxy.`type`() != Proxy.Type.DIRECT then
      throw new UnsupportedOperationException("scala-go: sockets connect directly, not through proxies")

  def this(address: InetAddress, port: Int) =
    this(null, null)
    connectOrClose(new InetSocketAddress(address, port), null)

  def this(host: String, port: Int) =
    this(null, null)
    connectOrClose(Socket.endpoint(host, port), null)

  def this(address: InetAddress, port: Int, localAddr: InetAddress, localPort: Int) =
    this(null, null)
    connectOrClose(new InetSocketAddress(address, port), new InetSocketAddress(localAddr, localPort))

  def this(host: String, port: Int, localAddr: InetAddress, localPort: Int) =
    this(null, null)
    connectOrClose(Socket.endpoint(host, port), new InetSocketAddress(localAddr, localPort))

  private def connectOrClose(endpoint: SocketAddress, local: SocketAddress): Unit =
    try
      if local != null then bind(local)
      connect(endpoint)
    catch
      case e: IOException =>
        close()
        throw e

  def connect(endpoint: SocketAddress): Unit = connect(endpoint, 0)

  def connect(endpoint: SocketAddress, timeout: Int): Unit =
    if endpoint == null then throw new IllegalArgumentException("connect: The address can't be null")
    if timeout < 0 then throw new IllegalArgumentException("connect: timeout can't be negative")
    if isClosed() then throw new SocketException("Socket is closed")
    if isConnected() then throw new SocketException("already connected")
    val epoint = endpoint match
      case a: InetSocketAddress => a
      case _                    => throw new IllegalArgumentException("Unsupported address type")
    if epoint.isUnresolved() then throw new UnknownHostException(epoint.getHostName())
    val local = bindpoint
    val result = Net.connect(epoint.getAddress().address, epoint.getPort(),
      if local == null || local.getAddress().isAnyLocalAddress() then null else local.getAddress().address,
      if local == null then 0 else local.getPort(), timeout)
    if OS.isError(result) then
      close()
      throw Socket.connectFailure(result)
    synchronized {
      if closed then
        Net.close(result)
        throw new SocketException("Socket closed")
      handle = result
      remoteAddress = epoint.getAddress()
    }
    applyOptions()

  private def applyOptions(): Unit =
    if tcpNoDelay then setNetOption(Net.NoDelay, 1)
    if keepAlive then setNetOption(Net.KeepAlive, 1)
    if linger >= 0 then setNetOption(Net.Linger, linger)
    if receiveBufferSize > 0 then setNetOption(Net.ReceiveBuffer, receiveBufferSize)
    if sendBufferSize > 0 then setNetOption(Net.SendBuffer, sendBufferSize)

  private def setNetOption(option: Int, value: Int): Unit =
    val h = handle
    if h != null then
      val error = Net.setOption(h, option, value)
      if error != null then throw new SocketException(Net.errorMessage(error))

  def bind(bindpoint: SocketAddress): Unit =
    if isClosed() then throw new SocketException("Socket is closed")
    if isBound() then throw new SocketException("Already bound")
    this.bindpoint = bindpoint match
      case null                 => new InetSocketAddress(0)
      case a: InetSocketAddress =>
        if a.isUnresolved() then throw new SocketException("Unresolved address")
        a
      case _ => throw new IllegalArgumentException("Unsupported address type")

  def getInetAddress(): InetAddress = if isConnected() then remoteAddress else null

  def getLocalAddress(): InetAddress =
    val h = handle
    if h != null && !closed then InetAddress.make(null, Net.address(h, true))
    else if bindpoint != null && !closed then bindpoint.getAddress()
    else InetAddress.anyLocal

  def getPort(): Int = if isConnected() then Net.port(handle, false) else 0

  def getLocalPort(): Int =
    val h = handle
    if h != null then Net.port(h, true)
    else if bindpoint != null then bindpoint.getPort()
    else -1

  def getRemoteSocketAddress(): SocketAddress =
    if isConnected() then new InetSocketAddress(getInetAddress(), getPort()) else null

  def getLocalSocketAddress(): SocketAddress =
    if !isBound() then null else new InetSocketAddress(getLocalAddress(), getLocalPort())

  def getInputStream(): InputStream = synchronized {
    if isClosed() then throw new SocketException("Socket is closed")
    if !isConnected() then throw new SocketException("Socket is not connected")
    if isInputShutdown() then throw new SocketException("Socket input is shutdown")
    if in == null then in = new Socket.SocketInputStream(this)
    in
  }

  def getOutputStream(): OutputStream = synchronized {
    if isClosed() then throw new SocketException("Socket is closed")
    if !isConnected() then throw new SocketException("Socket is not connected")
    if isOutputShutdown() then throw new SocketException("Socket output is shutdown")
    if out == null then out = new Socket.SocketOutputStream(this)
    out
  }

  private[net] def read(b: Array[Byte], off: Int, len: Int): Int =
    java.util.Objects.checkFromIndexSize(off, len, b.length)
    if closed then throw new SocketException("Socket closed")
    if inputShutdown then return -1
    if len == 0 then return 0
    val n = Net.read(handle, b, off, len, soTimeout)
    if n >= -1 then n
    else
      val error = Net.failure(handle)
      Net.errorKind(error) match
        case Net.Timeout => throw new SocketTimeoutException("Read timed out")
        case Net.Closed  => throw new SocketException("Socket closed")
        case Net.Reset   => throw new SocketException("Connection reset")
        case _           => if inputShutdown then -1 else throw new SocketException(Net.errorMessage(error))

  private[net] def write(b: Array[Byte], off: Int, len: Int): Unit =
    java.util.Objects.checkFromIndexSize(off, len, b.length)
    if closed then throw new SocketException("Socket closed")
    if outputShutdown then throw new SocketException("Socket output is shutdown")
    if len > 0 then
      val error = Net.write(handle, b, off, len)
      if error != null then
        if Net.errorKind(error) == Net.Closed then throw new SocketException("Socket closed")
        throw new SocketException(Net.errorMessage(error))

  def setTcpNoDelay(on: Boolean): Unit =
    checkOpen()
    tcpNoDelay = on
    setNetOption(Net.NoDelay, if on then 1 else 0)

  def getTcpNoDelay(): Boolean =
    checkOpen()
    tcpNoDelay

  def setSoLinger(on: Boolean, linger: Int): Unit =
    checkOpen()
    if on && linger < 0 then throw new IllegalArgumentException("invalid value for SO_LINGER")
    this.linger = if on then linger.min(65535) else -1
    setNetOption(Net.Linger, this.linger)

  def getSoLinger(): Int =
    checkOpen()
    linger

  def sendUrgentData(data: Int): Unit = throw new SocketException("Urgent data not supported")

  def setOOBInline(on: Boolean): Unit =
    checkOpen()
    oobInline = on

  def getOOBInline(): Boolean =
    checkOpen()
    oobInline

  def setSoTimeout(timeout: Int): Unit =
    checkOpen()
    if timeout < 0 then throw new IllegalArgumentException("timeout can't be negative")
    soTimeout = timeout

  def getSoTimeout(): Int =
    checkOpen()
    soTimeout

  def setSendBufferSize(size: Int): Unit =
    if size <= 0 then throw new IllegalArgumentException("negative send size")
    checkOpen()
    sendBufferSize = size
    setNetOption(Net.SendBuffer, size)

  def getSendBufferSize(): Int =
    checkOpen()
    val h = handle
    val actual = if h != null then Net.bufferSize(h, true) else 0
    if actual > 0 then actual else if sendBufferSize > 0 then sendBufferSize else 65536

  def setReceiveBufferSize(size: Int): Unit =
    if size <= 0 then throw new IllegalArgumentException("invalid receive size")
    checkOpen()
    receiveBufferSize = size
    setNetOption(Net.ReceiveBuffer, size)

  def getReceiveBufferSize(): Int =
    checkOpen()
    val h = handle
    val actual = if h != null then Net.bufferSize(h, false) else 0
    if actual > 0 then actual else if receiveBufferSize > 0 then receiveBufferSize else 65536

  def setKeepAlive(on: Boolean): Unit =
    checkOpen()
    keepAlive = on
    setNetOption(Net.KeepAlive, if on then 1 else 0)

  def getKeepAlive(): Boolean =
    checkOpen()
    keepAlive

  def setTrafficClass(tc: Int): Unit =
    if tc < 0 || tc > 255 then throw new IllegalArgumentException("tc is not in range 0 -- 255")
    checkOpen()
    trafficClass = tc

  def getTrafficClass(): Int = trafficClass

  def setReuseAddress(on: Boolean): Unit =
    checkOpen()
    reuseAddress = on

  def getReuseAddress(): Boolean =
    checkOpen()
    reuseAddress

  def setPerformancePreferences(connectionTime: Int, latency: Int, bandwidth: Int): Unit = ()

  def setOption[T](name: SocketOption[T], value: T): Socket =
    java.util.Objects.requireNonNull(name)
    def int = value.asInstanceOf[java.lang.Integer].intValue
    def bool = value.asInstanceOf[java.lang.Boolean].booleanValue
    name match
      case StandardSocketOptions.TCP_NODELAY  => setTcpNoDelay(bool)
      case StandardSocketOptions.SO_KEEPALIVE => setKeepAlive(bool)
      case StandardSocketOptions.SO_LINGER    => setSoLinger(int >= 0, int)
      case StandardSocketOptions.SO_RCVBUF    => setReceiveBufferSize(int)
      case StandardSocketOptions.SO_SNDBUF    => setSendBufferSize(int)
      case StandardSocketOptions.SO_REUSEADDR => setReuseAddress(bool)
      case StandardSocketOptions.IP_TOS       => setTrafficClass(int)
      case _ => throw new UnsupportedOperationException("'" + name + "' not supported")
    this

  def getOption[T](name: SocketOption[T]): T =
    java.util.Objects.requireNonNull(name)
    val value: Any = name match
      case StandardSocketOptions.TCP_NODELAY  => getTcpNoDelay()
      case StandardSocketOptions.SO_KEEPALIVE => getKeepAlive()
      case StandardSocketOptions.SO_LINGER    => getSoLinger()
      case StandardSocketOptions.SO_RCVBUF    => getReceiveBufferSize()
      case StandardSocketOptions.SO_SNDBUF    => getSendBufferSize()
      case StandardSocketOptions.SO_REUSEADDR => getReuseAddress()
      case StandardSocketOptions.IP_TOS       => getTrafficClass()
      case _ => throw new UnsupportedOperationException("'" + name + "' not supported")
    value.asInstanceOf[T]

  def supportedOptions(): java.util.Set[SocketOption[?]] =
    val set = new java.util.HashSet[SocketOption[?]]()
    for o <- List(StandardSocketOptions.TCP_NODELAY, StandardSocketOptions.SO_KEEPALIVE, StandardSocketOptions.SO_LINGER,
        StandardSocketOptions.SO_RCVBUF, StandardSocketOptions.SO_SNDBUF, StandardSocketOptions.SO_REUSEADDR,
        StandardSocketOptions.IP_TOS)
    do set.add(o)
    java.util.Collections.unmodifiableSet(set)

  private def checkOpen(): Unit = if isClosed() then throw new SocketException("Socket is closed")

  def shutdownInput(): Unit =
    checkOpen()
    if !isConnected() then throw new SocketException("Socket is not connected")
    if isInputShutdown() then throw new SocketException("Socket input is already shutdown")
    inputShutdown = true
    val error = Net.shutdown(handle, false)
    if error != null then throw new SocketException(Net.errorMessage(error))

  def shutdownOutput(): Unit =
    checkOpen()
    if !isConnected() then throw new SocketException("Socket is not connected")
    if isOutputShutdown() then throw new SocketException("Socket output is already shutdown")
    outputShutdown = true
    val error = Net.shutdown(handle, true)
    if error != null then throw new SocketException(Net.errorMessage(error))

  /** Closes the socket; a read or write blocked in another thread fails with "Socket closed". */
  def close(): Unit =
    val h = synchronized {
      if closed then null
      else
        closed = true
        handle
    }
    if h != null then Net.close(h)

  def isConnected(): Boolean = handle != null
  def isBound(): Boolean = handle != null || bindpoint != null
  def isClosed(): Boolean = closed
  def isInputShutdown(): Boolean = inputShutdown
  def isOutputShutdown(): Boolean = outputShutdown

  override def toString(): String =
    if isConnected() then s"Socket[addr=${getInetAddress()},port=${getPort()},localport=${getLocalPort()}]"
    else "Socket[unconnected]"

object Socket:
  private def endpoint(host: String, port: Int): InetSocketAddress =
    if host != null then new InetSocketAddress(host, port)
    else new InetSocketAddress(InetAddress.getByName(null), port)

  /** The JDK's exception for a failed connection. */
  private[net] def connectFailure(error: Object): IOException =
    val message = Net.errorMessage(error)
    Net.errorKind(error) match
      case Net.Timeout | Net.ConnectTimeout                => new SocketTimeoutException("Connect timed out")
      case Net.Refused                                     => new ConnectException("Connection refused")
      case Net.HostUnreachable                             => new NoRouteToHostException(message)
      case Net.AddressInUse | Net.AddressNotAvailable      => new BindException(message)
      case Net.UnknownHost                                 => new UnknownHostException(message)
      case _                                               => new SocketException(message)

  private final class SocketInputStream(socket: Socket) extends InputStream:
    override def read(): Int =
      val b = new Array[Byte](1)
      var n = 0
      while n == 0 do n = socket.read(b, 0, 1)
      if n == -1 then -1 else b(0) & 0xff
    override def read(b: Array[Byte], off: Int, len: Int): Int = socket.read(b, off, len)
    override def available(): Int =
      if socket.isClosed() then throw new SocketException("Socket closed")
      0
    override def close(): Unit = socket.close()

  private final class SocketOutputStream(socket: Socket) extends OutputStream:
    override def write(b: Int): Unit = socket.write(Array(b.toByte), 0, 1)
    override def write(b: Array[Byte], off: Int, len: Int): Unit = socket.write(b, off, len)
    override def close(): Unit = socket.close()
