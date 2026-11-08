package javax.crypto.spec

import java.security.spec.KeySpec
import javax.crypto.SecretKey

class SecretKeySpec(key: Array[Byte], offset: Int, len: Int, algorithm: String) extends KeySpec with SecretKey:
  if key == null || algorithm == null then throw new IllegalArgumentException("Missing argument")
  if key.length == 0 then throw new IllegalArgumentException("Empty key")
  if offset < 0 then throw new ArrayIndexOutOfBoundsException("offset is negative")
  if len < 0 then throw new ArrayIndexOutOfBoundsException("len is negative")
  if key.length - offset < len then throw new IllegalArgumentException("Invalid offset/length combination")

  private val bytes = java.util.Arrays.copyOfRange(key, offset, offset + len)

  def this(key: Array[Byte], algorithm: String) = this(key, 0, if key == null then 0 else key.length, algorithm)

  def getAlgorithm(): String = algorithm
  def getFormat(): String = "RAW"
  def getEncoded(): Array[Byte] = bytes.clone()

  override def hashCode(): Int =
    val h = java.util.Arrays.hashCode(bytes)
    if algorithm.equalsIgnoreCase("TripleDES") then h ^ "desede".hashCode()
    else h ^ algorithm.toLowerCase(java.util.Locale.ENGLISH).hashCode()

  override def equals(obj: Any): Boolean = obj match
    case that: SecretKey =>
      that.getAlgorithm().equalsIgnoreCase(algorithm) && java.security.MessageDigest.isEqual(bytes, that.getEncoded())
    case _ => false
