package javax.crypto

import java.nio.ByteBuffer
import java.security.{InvalidAlgorithmParameterException, InvalidKeyException, Key, NoSuchAlgorithmException}
import java.security.spec.AlgorithmParameterSpec

import scalago.runtime.Crypto

/** HMACs with the hash functions of `MessageDigest`, computed by Go. */
final class Mac private (algorithm: String, digestAlgorithm: String, private var handle: Object) extends Cloneable:
  private val one = new Array[Byte](1)

  final def getAlgorithm(): String = algorithm

  final def getMacLength(): Int =
    if handle != null then Crypto.digestLength(handle)
    else Crypto.digestLength(Crypto.newDigest(digestAlgorithm))

  final def init(key: Key): Unit =
    if !key.isInstanceOf[SecretKey] then throw new InvalidKeyException("Secret key expected")
    val bytes = key.getEncoded()
    if bytes == null then throw new InvalidKeyException("Missing key data")
    handle = Crypto.newMac(digestAlgorithm, bytes)

  final def init(key: Key, params: AlgorithmParameterSpec): Unit =
    if params != null then throw new InvalidAlgorithmParameterException("HMAC does not use parameters")
    init(key)

  private def initialized: Object =
    if handle == null then throw new IllegalStateException("MAC not initialized")
    handle

  final def update(input: Byte): Unit =
    one(0) = input
    Crypto.update(initialized, one, 0, 1)

  final def update(input: Array[Byte]): Unit =
    val h = initialized
    if input != null then Crypto.update(h, input, 0, input.length)

  final def update(input: Array[Byte], offset: Int, len: Int): Unit =
    val h = initialized
    if input != null then
      if offset < 0 || len < 0 || len > input.length - offset then throw new IllegalArgumentException("Bad arguments")
      Crypto.update(h, input, offset, len)

  final def update(input: ByteBuffer): Unit =
    val h = initialized
    if input == null then throw new IllegalArgumentException("Buffer must not be null")
    if input.hasArray() then
      Crypto.update(h, input.array(), input.arrayOffset() + input.position(), input.remaining())
      input.position(input.limit())
    else
      val bytes = new Array[Byte](input.remaining())
      input.get(bytes)
      Crypto.update(h, bytes, 0, bytes.length)

  final def doFinal(): Array[Byte] = Crypto.digest(initialized)

  final def doFinal(output: Array[Byte], outOffset: Int): Unit =
    val h = initialized
    val length = Crypto.digestLength(h)
    if output == null || output.length - outOffset < length then
      throw new ShortBufferException("Cannot store MAC in output buffer")
    System.arraycopy(Crypto.digest(h), 0, output, outOffset, length)

  final def doFinal(input: Array[Byte]): Array[Byte] =
    update(input)
    doFinal()

  final def reset(): Unit = if handle != null then Crypto.reset(handle)

  override def clone(): Object =
    new Mac(algorithm, digestAlgorithm, if handle == null then null else Crypto.copy(handle))

object Mac:
  private val algorithms: java.util.HashMap[String, String] =
    val m = new java.util.HashMap[String, String]()
    def add(digest: String, oid: String): Unit =
      for n <- List("Hmac" + digest.replace("-", ""), s"OID.$oid", oid) do m.put(n.toUpperCase, digest)
    add("MD5", "1.3.6.1.5.5.8.1.1")
    add("SHA-1", "1.2.840.113549.2.7")
    add("SHA-224", "1.2.840.113549.2.8")
    add("SHA-256", "1.2.840.113549.2.9")
    add("SHA-384", "1.2.840.113549.2.10")
    add("SHA-512", "1.2.840.113549.2.11")
    add("SHA-512/224", "1.2.840.113549.2.12")
    add("SHA-512/256", "1.2.840.113549.2.13")
    m

  final def getInstance(algorithm: String): Mac =
    if algorithm == null then throw new NullPointerException("null algorithm name")
    val digest = algorithms.get(algorithm.toUpperCase)
    if digest == null then throw new NoSuchAlgorithmException(s"Algorithm $algorithm not available")
    new Mac(algorithm, digest, null)
