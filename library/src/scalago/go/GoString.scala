package scalago.go

import java.nio.charset.StandardCharsets.UTF_8

/** A Go string: immutable UTF-8 bytes.
 *
 *  Scala's `String` is `java.lang.String` (UTF-16) under scala-go too, so programs behave as on
 *  the JVM. `GoString` is the opt-in alternative, like Scala Native's `CString`: on Go it *is* a
 *  Go `string` (no conversion when talking to Go code or doing I/O); on the JVM it is backed by a
 *  byte array, so the same code runs and can be tested there. On both, `==` and `hashCode`
 *  compare contents and `toString` decodes, as for a Go string.
 *
 *  Conversions between the two representations copy and transcode.
 */
opaque type GoString = AnyRef

object GoString:
  /** Encodes as UTF-8. Unpaired surrogates become U+FFFD. */
  def apply(s: String): GoString = JvmGoString(wellFormed(s).getBytes(UTF_8))

  def empty: GoString = apply("")

  extension (g: GoString)
    /** Decodes UTF-8 into a `String`. */
    def toJavaString: String = new String(bytes(g), UTF_8)

    /** Length in bytes (not characters). */
    def byteLength: Int = bytes(g).length

    def byteAt(index: Int): Byte = bytes(g)(index)

    def concat(other: GoString): GoString =
      val a = bytes(g)
      val b = bytes(other)
      val out = new Array[Byte](a.length + b.length)
      System.arraycopy(a, 0, out, 0, a.length)
      System.arraycopy(b, 0, out, a.length, b.length)
      JvmGoString(out)

    /** Content equality, like `==`. */
    def sameAs(other: GoString): Boolean = g == other

  /** Go's order of strings: by bytes, as Go's `<` (for valid UTF-8, by code points, whereas
   *  `String`s compare UTF-16 code units).
   */
  given Ordering[GoString] = (a, b) => compareBytes(a, b)

  /** -1, 0 or 1. Not an extension method `compare`: its erased signature would be that of
   *  `Comparator.compare`, which the linker then reaches for any comparator.
   */
  private[go] def compareBytes(a: GoString, b: GoString): Int =
    Integer.signum(java.util.Arrays.compareUnsigned(bytes(a), bytes(b)))

  private def bytes(g: GoString): Array[Byte] = g.asInstanceOf[JvmGoString].bytes

  /** `s` with each unpaired surrogate replaced by U+FFFD, as Go converts UTF-16 (the JDK's
   *  encoder would write `?`).
   */
  private def wellFormed(s: String): String =
    def paired(i: Int): Boolean =
      val c = s.charAt(i)
      if Character.isHighSurrogate(c) then i + 1 < s.length && Character.isLowSurrogate(s.charAt(i + 1))
      else !Character.isLowSurrogate(c) || (i > 0 && Character.isHighSurrogate(s.charAt(i - 1)))
    if (0 until s.length).forall(paired) then s
    else String((0 until s.length).map(i => if paired(i) then s.charAt(i) else '\uFFFD').toArray)

  /** A `GoString` on the JVM: compared, hashed and shown by content, as Go strings are on Go. */
  private final class JvmGoString(val bytes: Array[Byte]):
    override def equals(other: Any): Boolean = other match
      case o: JvmGoString => java.util.Arrays.equals(bytes, o.bytes)
      case _              => false
    override def hashCode: Int = java.util.Arrays.hashCode(bytes)
    override def toString: String = new String(bytes, UTF_8)

/** Encodes as UTF-8, like `GoString(s)`. */
extension (s: String) def toGoString: GoString = GoString(s)
