package scalago.runtime

/** The native side of java.util.UUID, java.security.MessageDigest and SecureRandom, and
 *  javax.crypto.Mac (see runtime/rt/crypto.go), on Go's crypto packages. A digest is a handle
 *  for a message digest or an HMAC in progress.
 */
object Crypto:
  private def native: Nothing = throw new UnsupportedOperationException("scala-go native")

  /** `n` bytes from a cryptographically secure source (Go's crypto/rand). */
  def secureRandomBytes(n: Int): Array[Byte] = native
  /** The MD5 digest of `data`. */
  def md5(data: Array[Byte]): Array[Byte] = native

  /** A digest with a standard algorithm name ("SHA-256"), or null if Go has none. */
  def newDigest(algorithm: String): Object = native
  /** An HMAC with a digest algorithm ("SHA-256") and a key, or null. */
  def newMac(algorithm: String, key: Array[Byte]): Object = native
  def update(digest: Object, b: Array[Byte], off: Int, len: Int): Unit = native
  /** Completes the digest and resets it. */
  def digest(digest: Object): Array[Byte] = native
  def reset(digest: Object): Unit = native
  def digestLength(digest: Object): Int = native
  def copy(digest: Object): Object = native
