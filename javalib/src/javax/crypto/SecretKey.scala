package javax.crypto

trait SecretKey extends java.security.Key

class ShortBufferException(message: String) extends java.security.GeneralSecurityException(message):
  def this() = this(null)
