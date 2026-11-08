package javax.net.ssl

import java.io.IOException

class SSLException(message: String, cause: Throwable) extends IOException(message, cause):
  def this(message: String) = this(message, null)
  def this(cause: Throwable) = this(if cause == null then null else cause.toString, cause)

class SSLHandshakeException(message: String, cause: Throwable) extends SSLException(message, cause):
  def this(message: String) = this(message, null)

class SSLPeerUnverifiedException(message: String) extends SSLException(message)

class SSLProtocolException(message: String) extends SSLException(message)
