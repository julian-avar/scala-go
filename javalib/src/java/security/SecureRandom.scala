package java.security

import scalago.runtime.Crypto

/** Random bytes from the operating system's secure source (Go's crypto/rand), whatever the
 *  algorithm. Seeds add nothing to it, so unlike the JDK's SHA1PRNG and DRBG, seeding never makes
 *  the output reproducible.
 */
class SecureRandom private (algorithm: String) extends java.util.Random(0L):
  def this() = this("NativePRNG")
  def this(seed: Array[Byte]) =
    this("NativePRNG")
    setSeed(seed)

  def getAlgorithm(): String = algorithm

  def setSeed(seed: Array[Byte]): Unit =
    if seed == null then throw new NullPointerException()

  /** Called by `Random`'s constructor on the JDK too: ignored. */
  override def setSeed(seed: Long): Unit = ()

  override def nextBytes(bytes: Array[Byte]): Unit =
    val random = Crypto.secureRandomBytes(bytes.length)
    System.arraycopy(random, 0, bytes, 0, bytes.length)

  override protected final def next(numBits: Int): Int =
    val numBytes = (numBits + 7) / 8
    val b = Crypto.secureRandomBytes(numBytes)
    var next = 0
    var i = 0
    while i < numBytes do
      next = (next << 8) + (b(i) & 0xff)
      i += 1
    next >>> (numBytes * 8 - numBits)

  def generateSeed(numBytes: Int): Array[Byte] =
    if numBytes < 0 then throw new IllegalArgumentException(s"numBytes cannot be negative")
    Crypto.secureRandomBytes(numBytes)

  def reseed(): Unit = ()

object SecureRandom:
  private val algorithms =
    Array("NativePRNG", "NativePRNGBlocking", "NativePRNGNonBlocking", "SHA1PRNG", "DRBG")

  def getInstance(algorithm: String): SecureRandom =
    if algorithm == null then throw new NullPointerException("null algorithm name")
    algorithms.find(_.equalsIgnoreCase(algorithm)) match
      case Some(_) => new SecureRandom(algorithm)
      case None    => throw new NoSuchAlgorithmException(s"$algorithm SecureRandom not available")

  def getInstance(algorithm: String, provider: String): SecureRandom =
    if provider == null || provider.isEmpty then throw new IllegalArgumentException("missing provider")
    if provider != "SUN" then throw new NoSuchProviderException(s"no such provider: $provider")
    getInstance(algorithm)

  def getInstanceStrong(): SecureRandom = new SecureRandom("NativePRNGBlocking")

  def getSeed(numBytes: Int): Array[Byte] = new SecureRandom().generateSeed(numBytes)
