package scalago.runtime

/** Cryptographic primitives for java.util.UUID, on Go's crypto packages (see runtime/rt/util.go). */
object Crypto:
  private def native: Nothing = throw new UnsupportedOperationException("scala-go native")

  /** `n` bytes from a cryptographically secure source (Go's crypto/rand). */
  def secureRandomBytes(n: Int): Array[Byte] = native
  /** The MD5 digest of `data`. */
  def md5(data: Array[Byte]): Array[Byte] = native
