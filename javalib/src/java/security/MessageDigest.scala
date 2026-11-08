package java.security

import java.nio.ByteBuffer

import scalago.runtime.Crypto

/** Message digests. `getInstance` gives the algorithms of the JDK's SUN provider that Go's crypto
 *  packages have (MD5, SHA-1 and SHA-2), computed by Go; programs can subclass it as on the JDK.
 */
abstract class MessageDigest protected (algorithm: String) extends MessageDigestSpi:
  private[security] var inProgress = false

  def update(input: Byte): Unit =
    engineUpdate(input)
    inProgress = true

  def update(input: Array[Byte], offset: Int, len: Int): Unit =
    if input == null then throw new IllegalArgumentException("No input buffer given")
    if input.length - offset < len then throw new IllegalArgumentException("Input buffer too short")
    engineUpdate(input, offset, len)
    inProgress = true

  def update(input: Array[Byte]): Unit =
    engineUpdate(input, 0, input.length)
    inProgress = true

  final def update(input: ByteBuffer): Unit =
    if input == null then throw new NullPointerException()
    engineUpdate(input)
    inProgress = true

  def digest(): Array[Byte] =
    val result = engineDigest()
    inProgress = false
    result

  def digest(buf: Array[Byte], offset: Int, len: Int): Int =
    if buf == null then throw new IllegalArgumentException("No output buffer given")
    if buf.length - offset < len then
      throw new IllegalArgumentException("Output buffer too small for specified offset and length")
    val n = engineDigest(buf, offset, len)
    inProgress = false
    n

  def digest(input: Array[Byte]): Array[Byte] =
    update(input)
    digest()

  def reset(): Unit =
    engineReset()
    inProgress = false

  final def getAlgorithm(): String = algorithm

  final def getDigestLength(): Int =
    val n = engineGetDigestLength()
    if n != 0 then n
    else
      try this.clone().asInstanceOf[MessageDigest].digest().length
      catch case _: CloneNotSupportedException => 0

  private[security] def providerName: String = "(no provider)"

  override def toString(): String =
    s"$algorithm Message Digest from $providerName, ${if inProgress then "<in progress>" else "<initialized>"}\n"

object MessageDigest:
  /** The JDK's standard names, aliases and object identifiers of the algorithms Go has, upper
   *  case, to the standard names.
   */
  private[security] val algorithms: java.util.HashMap[String, String] =
    val m = new java.util.HashMap[String, String]()
    def add(name: String, oid: String, aliases: String*): Unit =
      for n <- name +: s"OID.$oid" +: oid +: aliases do m.put(n.toUpperCase, name)
    add("MD5", "1.2.840.113549.2.5")
    add("SHA-1", "1.3.14.3.2.26", "SHA", "SHA1")
    add("SHA-224", "2.16.840.1.101.3.4.2.4", "SHA224")
    add("SHA-256", "2.16.840.1.101.3.4.2.1", "SHA256")
    add("SHA-384", "2.16.840.1.101.3.4.2.2", "SHA384")
    add("SHA-512", "2.16.840.1.101.3.4.2.3", "SHA512")
    add("SHA-512/224", "2.16.840.1.101.3.4.2.5", "SHA512/224")
    add("SHA-512/256", "2.16.840.1.101.3.4.2.6", "SHA512/256")
    m

  def getInstance(algorithm: String): MessageDigest =
    if algorithm == null then throw new NullPointerException("null algorithm name")
    val name = algorithms.get(algorithm.toUpperCase)
    if name == null then throw new NoSuchAlgorithmException(s"$algorithm MessageDigest not available")
    new NativeMessageDigest(algorithm, name, Crypto.newDigest(name))

  def getInstance(algorithm: String, provider: String): MessageDigest =
    if provider == null || provider.isEmpty then throw new IllegalArgumentException("missing provider")
    if provider != "SUN" then throw new NoSuchProviderException(s"no such provider: $provider")
    getInstance(algorithm)

  /** Compares in time independent of where the digests differ, as the JDK. */
  def isEqual(digesta: Array[Byte], digestb: Array[Byte]): Boolean =
    if digesta eq digestb then true
    else if digesta == null || digestb == null || digesta.length != digestb.length then false
    else
      var diff = 0
      var i = 0
      while i < digesta.length do
        diff |= digesta(i) ^ digestb(i)
        i += 1
      diff == 0

/** A digest computed by Go (`scalago.runtime.Crypto`), named `algorithm` by the program. */
private final class NativeMessageDigest(algorithm: String, standardName: String, handle: Object)
    extends MessageDigest(algorithm) with Cloneable:
  private val one = new Array[Byte](1)

  override protected def engineGetDigestLength(): Int = Crypto.digestLength(handle)
  override protected def engineUpdate(input: Byte): Unit =
    one(0) = input
    Crypto.update(handle, one, 0, 1)
  override protected def engineUpdate(input: Array[Byte], offset: Int, len: Int): Unit =
    if offset < 0 || len < 0 || offset > input.length - len then throw new ArrayIndexOutOfBoundsException()
    Crypto.update(handle, input, offset, len)
  override protected def engineDigest(): Array[Byte] = Crypto.digest(handle)
  override protected def engineDigest(buf: Array[Byte], offset: Int, len: Int): Int =
    val length = Crypto.digestLength(handle)
    if len < length then throw new DigestException(s"Length must be at least $length for ${standardName}digests")
    if offset < 0 || len < 0 || offset > buf.length - len then throw new DigestException("Buffer too short to store digest")
    System.arraycopy(Crypto.digest(handle), 0, buf, offset, length)
    length
  override protected def engineReset(): Unit = Crypto.reset(handle)
  override private[security] def providerName: String = "SUN"

  override def clone(): Object =
    val copy = new NativeMessageDigest(algorithm, standardName, Crypto.copy(handle))
    copy.inProgress = inProgress
    copy
