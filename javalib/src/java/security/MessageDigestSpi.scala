package java.security

import java.nio.ByteBuffer

abstract class MessageDigestSpi:
  protected def engineGetDigestLength(): Int = 0
  protected def engineUpdate(input: Byte): Unit
  protected def engineUpdate(input: Array[Byte], offset: Int, len: Int): Unit

  protected def engineUpdate(input: ByteBuffer): Unit =
    if input.hasRemaining() then
      if input.hasArray() then
        engineUpdate(input.array(), input.arrayOffset() + input.position(), input.remaining())
        input.position(input.limit())
      else
        val chunk = new Array[Byte](Math.min(input.remaining(), 4096))
        while input.hasRemaining() do
          val n = Math.min(input.remaining(), chunk.length)
          input.get(chunk, 0, n)
          engineUpdate(chunk, 0, n)

  protected def engineDigest(): Array[Byte]

  protected def engineDigest(buf: Array[Byte], offset: Int, len: Int): Int =
    val digest = engineDigest()
    if len < digest.length then throw new DigestException("partial digests not returned")
    if buf.length - offset < digest.length then
      throw new DigestException("insufficient space in the output buffer to store the digest")
    System.arraycopy(digest, 0, buf, offset, digest.length)
    digest.length

  protected def engineReset(): Unit

  override def clone(): Object =
    if this.isInstanceOf[Cloneable] then super.clone() else throw new CloneNotSupportedException()
