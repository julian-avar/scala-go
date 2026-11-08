// Adapted from Scala Native's javalib (Apache-2.0, see javalib/LICENSE-scala-native.md).

package java.net

import java.io.{IOException, InterruptedIOException}

class URISyntaxException(input: String, reason: String, index: Int) extends Exception(reason) {

  def this(input: String, reason: String) = this(input, reason, -1)

  if (input == null || reason == null)
    throw new NullPointerException()

  if (index < -1)
    throw new IllegalArgumentException()

  def getIndex(): Int = index
  def getInput(): String = input
  def getReason(): String = reason

  override def getMessage(): String =
    reason + (if (index > -1) " at index " + index else "") + ": " + input
}

class MalformedURLException(msg: String) extends IOException(msg) {
  def this() = this(null)
}

class ProtocolException(host: String) extends IOException(host) {
  def this() = this(null)
}

class UnknownHostException(host: String) extends IOException(host) {
  def this() = this(null)
}

class UnknownServiceException(msg: String) extends IOException(msg) {
  def this() = this(null)
}

class SocketException(msg: String, cause: Throwable) extends IOException(msg, cause) {
  def this(msg: String) = this(msg, null)
  def this(cause: Throwable) = this(if (cause == null) null else cause.toString, cause)
  def this() = this(null, null)
}

class BindException(msg: String) extends SocketException(msg) {
  def this() = this(null)
}

class ConnectException(msg: String) extends SocketException(msg) {
  def this() = this(null)
}

class NoRouteToHostException(msg: String) extends SocketException(msg) {
  def this() = this(null)
}

class PortUnreachableException(msg: String) extends SocketException(msg) {
  def this() = this(null)
}

class SocketTimeoutException(msg: String) extends InterruptedIOException(msg) {
  def this() = this(null)
}

class HttpRetryException(detail: String, code: Int, location: String) extends IOException(detail) {
  def this(detail: String, code: Int) = this(detail, code, null)
  def responseCode(): Int = code
  def getReason(): String = detail
  def getLocation(): String = location
}
