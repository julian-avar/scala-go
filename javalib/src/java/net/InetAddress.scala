package java.net

import scalago.runtime.{Net, OS}

/** An IP address and possibly its host name: an [[Inet4Address]] or an [[Inet6Address]]. Names
 *  are resolved by Go's resolver (the system's configuration: /etc/hosts, DNS), without the
 *  JDK's cache.
 */
class InetAddress private[net] (private var hostName: String, private[net] val address: Array[Byte])
    extends java.io.Serializable:

  /** The raw address: 4 bytes for IPv4, 16 for IPv6. */
  def getAddress(): Array[Byte] = address.clone()

  def getHostAddress(): String = ""

  /** The host name: the one this address was created with, or the result of a reverse lookup
   *  (or the address itself if there is none).
   */
  def getHostName(): String =
    if hostName == null then hostName = getCanonicalHostName()
    hostName

  def getCanonicalHostName(): String =
    val name = Net.reverseLookup(address)
    if name == null then getHostAddress() else name

  def isAnyLocalAddress(): Boolean = false
  def isLoopbackAddress(): Boolean = false
  def isLinkLocalAddress(): Boolean = false
  def isSiteLocalAddress(): Boolean = false
  def isMulticastAddress(): Boolean = false
  def isMCGlobal(): Boolean = false
  def isMCNodeLocal(): Boolean = false
  def isMCLinkLocal(): Boolean = false
  def isMCSiteLocal(): Boolean = false
  def isMCOrgLocal(): Boolean = false

  /** Whether a TCP connection to the echo port is accepted or refused within the timeout (what
   *  the JDK does without the privilege to send ICMP echo requests).
   */
  def isReachable(timeout: Int): Boolean =
    if timeout < 0 then throw new IllegalArgumentException("timeout can't be negative")
    val result = Net.connect(address, 7, null, 0, timeout)
    if !OS.isError(result) then
      Net.close(result)
      true
    else Net.errorKind(result) == Net.Refused

  private[net] def originalHostName: String = hostName

  override def toString(): String = (if hostName != null then hostName else "") + "/" + getHostAddress()

object InetAddress:
  def getByName(host: String): InetAddress = getAllByName(host)(0)

  def getAllByName(host: String): Array[InetAddress] =
    if host == null || host.isEmpty then return Array(getLoopbackAddress())
    var name = host
    var ipv6Expected = false
    if name.charAt(0) == '[' then
      if name.length > 2 && name.charAt(name.length - 1) == ']' then
        name = name.substring(1, name.length - 1)
        ipv6Expected = true
      else throw invalidIPv6Literal(name, brackets = false)
    if Character.digit(name.charAt(0), 16) != -1 || name.charAt(0) == ':' then
      val literal = parseLiteral(name, ipv6Expected)
      if literal != null then return Array(literal)
    else if ipv6Expected then throw invalidIPv6Literal(name, brackets = true)
    lookup(name)

  /** An IP literal, or null if `host` is not one (and is to be resolved). */
  private def parseLiteral(host: String, ipv6Expected: Boolean): InetAddress =
    val v4 = Inet4Address.parse(host)
    if v4 != null then
      if ipv6Expected then throw invalidIPv6Literal(host, brackets = true)
      return new Inet4Address(null, v4)
    var scopeId = 0
    var scopeName: String = null
    var text = host
    val percent = host.indexOf('%')
    if percent != -1 then
      text = host.substring(0, percent)
      val zone = host.substring(percent + 1)
      if !zone.isEmpty && zone.forall(c => c >= '0' && c <= '9') then scopeId = Integer.parseInt(zone)
      else scopeName = zone
    val v6 = Inet6Address.parse(text)
    if v6 == null then
      if host.contains(":") || ipv6Expected then throw invalidIPv6Literal(host, ipv6Expected)
      null
    else if v6.length == 4 then
      if percent != -1 then throw new UnknownHostException(host + ": invalid IPv4-mapped address")
      new Inet4Address(null, v6)
    else if scopeName != null then throw new UnknownHostException("no such interface " + scopeName)
    else new Inet6Address(null, v6, scopeId)

  private def invalidIPv6Literal(host: String, brackets: Boolean): UnknownHostException =
    new UnknownHostException((if brackets then "[" + host + "]" else host) + ": invalid IPv6 address literal")

  private def lookup(host: String): Array[InetAddress] =
    val result = Net.lookup(host)
    if OS.isError(result) then throw new UnknownHostException(Net.errorMessage(result))
    result.asInstanceOf[Array[Array[Byte]]].map(a => make(host, a))

  /** An address of the right class for 4 or 16 bytes (IPv4-mapped IPv6 addresses are IPv4). */
  private[net] def make(host: String, addr: Array[Byte]): InetAddress =
    if addr.length == 4 then new Inet4Address(host, addr.clone())
    else if Inet6Address.isIPv4Mapped(addr) then new Inet4Address(host, java.util.Arrays.copyOfRange(addr, 12, 16))
    else new Inet6Address(host, addr.clone(), 0)

  def getByAddress(addr: Array[Byte]): InetAddress = getByAddress(null, addr)

  def getByAddress(host: String, addr: Array[Byte]): InetAddress =
    var name = host
    if name != null && !name.isEmpty && name.charAt(0) == '[' && name.charAt(name.length - 1) == ']' then
      name = name.substring(1, name.length - 1)
    if addr == null || (addr.length != 4 && addr.length != 16) then
      throw new UnknownHostException("addr is of illegal length")
    make(name, addr)

  def getLocalHost(): InetAddress =
    val name = Net.hostname()
    if name == "localhost" then return getLoopbackAddress()
    val result = Net.lookup(name)
    if OS.isError(result) then throw new UnknownHostException(name + ": " + Net.errorMessage(result))
    make(name, result.asInstanceOf[Array[Array[Byte]]](0))

  def getLoopbackAddress(): InetAddress = new Inet4Address("localhost", Array[Byte](127, 0, 0, 1))

  /** The wildcard address, 0.0.0.0. */
  private[net] def anyLocal: InetAddress = new Inet4Address("0.0.0.0", new Array[Byte](4))

final class Inet4Address private[net] (hostName: String, addr: Array[Byte]) extends InetAddress(hostName, addr):
  private def int: Int =
    ((address(0) & 0xff) << 24) | ((address(1) & 0xff) << 16) | ((address(2) & 0xff) << 8) | (address(3) & 0xff)

  override def getHostAddress(): String =
    s"${address(0) & 0xff}.${address(1) & 0xff}.${address(2) & 0xff}.${address(3) & 0xff}"

  override def isAnyLocalAddress(): Boolean = int == 0
  override def isLoopbackAddress(): Boolean = address(0) == 127
  override def isLinkLocalAddress(): Boolean = (address(0) & 0xff) == 169 && (address(1) & 0xff) == 254
  override def isSiteLocalAddress(): Boolean =
    val (a, b) = (address(0) & 0xff, address(1) & 0xff)
    a == 10 || (a == 172 && (b & 0xf0) == 16) || (a == 192 && b == 168)
  override def isMulticastAddress(): Boolean = (address(0) & 0xf0) == 0xe0
  override def isMCGlobal(): Boolean =
    val (a, b, c) = (address(0) & 0xff, address(1) & 0xff, address(2) & 0xff)
    a >= 224 && a <= 238 && !(a == 224 && b == 0 && c == 0)
  override def isMCLinkLocal(): Boolean = (address(0) & 0xff) == 224 && address(1) == 0 && address(2) == 0
  override def isMCSiteLocal(): Boolean = (address(0) & 0xff) == 239 && (address(1) & 0xff) == 255
  override def isMCOrgLocal(): Boolean =
    (address(0) & 0xff) == 239 && (address(1) & 0xff) >= 192 && (address(1) & 0xff) <= 195

  override def hashCode(): Int = int
  override def equals(obj: Any): Boolean = obj match
    case that: Inet4Address => int == that.int
    case _                  => false

object Inet4Address:
  /** The address of a dotted-decimal literal (with the C library's shorter forms: "127.1" is
   *  127.0.0.1), or null.
   */
  private[net] def parse(src: String): Array[Byte] =
    val n = src.length
    if n == 0 || n > 15 then return null
    val res = new Array[Byte](4)
    var value = 0L
    var currByte = 0
    var newOctet = true
    for i <- 0 until n do
      val c = src.charAt(i)
      if c == '.' then
        if newOctet || value > 0xff || currByte == 3 then return null
        res(currByte) = value.toByte
        currByte += 1
        value = 0
        newOctet = true
      else
        val digit = Character.digit(c, 10)
        if digit < 0 then return null
        value = value * 10 + digit
        newOctet = false
    if newOctet || value >= (1L << ((4 - currByte) * 8)) then return null
    // The last part fills the remaining bytes.
    for i <- currByte until 4 do res(i) = (value >> ((3 - i) * 8)).toByte
    res

final class Inet6Address private[net] (hostName: String, addr: Array[Byte], scopeId: Int)
    extends InetAddress(hostName, addr):

  def getScopeId(): Int = scopeId

  def isIPv4CompatibleAddress(): Boolean = (0 until 12).forall(address(_) == 0)

  override def getHostAddress(): String =
    val sb = new java.lang.StringBuilder(39)
    for i <- 0 until 8 do
      if i > 0 then sb.append(':')
      sb.append(Integer.toHexString(((address(2 * i) & 0xff) << 8) | (address(2 * i + 1) & 0xff)))
    if scopeId != 0 then sb.append('%').append(scopeId)
    sb.toString

  override def isAnyLocalAddress(): Boolean = address.forall(_ == 0)
  override def isLoopbackAddress(): Boolean = (0 until 15).forall(address(_) == 0) && address(15) == 1
  override def isLinkLocalAddress(): Boolean = (address(0) & 0xff) == 0xfe && (address(1) & 0xc0) == 0x80
  override def isSiteLocalAddress(): Boolean = (address(0) & 0xff) == 0xfe && (address(1) & 0xc0) == 0xc0
  override def isMulticastAddress(): Boolean = (address(0) & 0xff) == 0xff
  override def isMCGlobal(): Boolean = isMulticastAddress() && (address(1) & 0x0f) == 0x0e
  override def isMCNodeLocal(): Boolean = isMulticastAddress() && (address(1) & 0x0f) == 0x01
  override def isMCLinkLocal(): Boolean = isMulticastAddress() && (address(1) & 0x0f) == 0x02
  override def isMCSiteLocal(): Boolean = isMulticastAddress() && (address(1) & 0x0f) == 0x05
  override def isMCOrgLocal(): Boolean = isMulticastAddress() && (address(1) & 0x0f) == 0x08

  /** The JDK's: the sum of the address's four big-endian words, from signed bytes. */
  override def hashCode(): Int =
    var hash = 0
    var i = 0
    while i < 16 do
      var component = 0
      for _ <- 0 until 4 do
        component = (component << 8) + address(i)
        i += 1
      hash += component
    hash

  override def equals(obj: Any): Boolean = obj match
    case that: Inet6Address => java.util.Arrays.equals(address, that.address)
    case _                  => false

object Inet6Address:
  def getByAddress(host: String, addr: Array[Byte], scopeId: Int): Inet6Address =
    if addr == null || addr.length != 16 then throw new UnknownHostException("addr is of illegal length")
    new Inet6Address(host, addr.clone(), scopeId.max(0))

  private[net] def isIPv4Mapped(addr: Array[Byte]): Boolean =
    addr.length == 16 && (0 until 10).forall(addr(_) == 0) && addr(10) == -1 && addr(11) == -1

  /** The address of an IPv6 literal (without brackets or zone): 16 bytes, or 4 for an
   *  IPv4-mapped address; null if `src` is not one.
   */
  private[net] def parse(src: String): Array[Byte] =
    val n = src.length
    if n < 2 then return null
    val dst = new Array[Byte](16)
    var i = 0
    if src.charAt(0) == ':' then
      if src.charAt(1) != ':' then return null
      i = 1
    var j = 0 // next byte of dst
    var colonPos = -1 // where "::" is
    var curtok = i
    var sawDigits = false
    var value = 0
    while i < n do
      val c = src.charAt(i)
      i += 1
      val digit = Character.digit(c, 16)
      if digit != -1 then
        value = (value << 4) | digit
        if value > 0xffff then return null
        sawDigits = true
      else if c == ':' then
        curtok = i
        if !sawDigits then
          if colonPos != -1 then return null
          colonPos = j
        else
          if i == n || j + 2 > 16 then return null
          dst(j) = (value >> 8).toByte
          dst(j + 1) = value.toByte
          j += 2
          sawDigits = false
          value = 0
      else if c == '.' && j + 4 <= 16 then
        val v4 = Inet4Address.parse(src.substring(curtok, n))
        if v4 == null || src.substring(curtok, n).count(_ == '.') != 3 then return null
        System.arraycopy(v4, 0, dst, j, 4)
        j += 4
        sawDigits = false
        i = n
      else return null
    if sawDigits then
      if j + 2 > 16 then return null
      dst(j) = (value >> 8).toByte
      dst(j + 1) = value.toByte
      j += 2
    if colonPos != -1 then
      if j == 16 then return null
      val shift = j - colonPos
      for k <- 1 to shift do
        dst(16 - k) = dst(colonPos + shift - k)
        dst(colonPos + shift - k) = 0
      j = 16
    if j != 16 then return null
    if isIPv4Mapped(dst) then java.util.Arrays.copyOfRange(dst, 12, 16) else dst
