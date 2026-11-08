package java.security

import java.io.{FilterInputStream, FilterOutputStream, InputStream, OutputStream}

class DigestInputStream(stream: InputStream, protected var digest: MessageDigest) extends FilterInputStream(stream):
  private var on = true

  def getMessageDigest(): MessageDigest = digest
  def setMessageDigest(digest: MessageDigest): Unit = this.digest = digest
  def on(on: Boolean): Unit = this.on = on

  override def read(): Int =
    val b = in.read()
    if on && b != -1 then digest.update(b.toByte)
    b

  override def read(b: Array[Byte], off: Int, len: Int): Int =
    val n = in.read(b, off, len)
    if on && n != -1 then digest.update(b, off, n)
    n

  override def toString(): String = s"[Digest Input Stream] $digest"

class DigestOutputStream(stream: OutputStream, protected var digest: MessageDigest) extends FilterOutputStream(stream):
  private var on = true

  def getMessageDigest(): MessageDigest = digest
  def setMessageDigest(digest: MessageDigest): Unit = this.digest = digest
  def on(on: Boolean): Unit = this.on = on

  override def write(b: Int): Unit =
    if on then digest.update(b.toByte)
    out.write(b)

  override def write(b: Array[Byte], off: Int, len: Int): Unit =
    if on then digest.update(b, off, len)
    out.write(b, off, len)

  override def toString(): String = s"[Digest Output Stream] $digest"
