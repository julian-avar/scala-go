package java.security

class GeneralSecurityException(message: String, cause: Throwable) extends Exception(message, cause):
  def this(message: String) = this(message, null)
  def this(cause: Throwable) = this(if cause == null then null else cause.toString, cause)
  def this() = this(null, null)

class NoSuchAlgorithmException(message: String, cause: Throwable) extends GeneralSecurityException(message, cause):
  def this(message: String) = this(message, null)
  def this(cause: Throwable) = this(if cause == null then null else cause.toString, cause)
  def this() = this(null, null)

class NoSuchProviderException(message: String) extends GeneralSecurityException(message):
  def this() = this(null)

class DigestException(message: String, cause: Throwable) extends GeneralSecurityException(message, cause):
  def this(message: String) = this(message, null)
  def this(cause: Throwable) = this(if cause == null then null else cause.toString, cause)
  def this() = this(null, null)

class KeyException(message: String, cause: Throwable) extends GeneralSecurityException(message, cause):
  def this(message: String) = this(message, null)
  def this(cause: Throwable) = this(if cause == null then null else cause.toString, cause)
  def this() = this(null, null)

class InvalidKeyException(message: String, cause: Throwable) extends KeyException(message, cause):
  def this(message: String) = this(message, null)
  def this(cause: Throwable) = this(if cause == null then null else cause.toString, cause)
  def this() = this(null, null)

class InvalidAlgorithmParameterException(message: String, cause: Throwable)
    extends GeneralSecurityException(message, cause):
  def this(message: String) = this(message, null)
  def this(cause: Throwable) = this(if cause == null then null else cause.toString, cause)
  def this() = this(null, null)

class InvalidParameterException(message: String) extends IllegalArgumentException(message):
  def this() = this(null)

class ProviderException(message: String, cause: Throwable) extends RuntimeException(message, cause):
  def this(message: String) = this(message, null)
  def this(cause: Throwable) = this(if cause == null then null else cause.toString, cause)
  def this() = this(null, null)
