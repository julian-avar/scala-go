package scalago.go

import java.nio.charset.StandardCharsets.UTF_8

/** A Go string: immutable UTF-8 bytes.
 *
 *  Scala's `String` is `java.lang.String` (UTF-16) under scala-go too, so programs behave as on
 *  the JVM. `GoString` is the opt-in alternative, like Scala Native's `CString`: on Go it *is* a
 *  Go `string` (no conversion when talking to Go code or doing I/O); on the JVM it is backed by a
 *  byte array, so the same code runs and can be tested there.
 *
 *  {{{
 *  import scalago.go.*
 *
 *  val g = "Grüße".toGoString   // or GoString("Grüße")
 *  g.byteLength                 // 7
 *  g.toJavaString               // "Grüße"
 *  g.sameAs(GoString("Grüße"))  // true
 *  }}}
 *
 *  Conversions between the two representations copy and transcode. Compare contents with
 *  `sameAs`: `==` compares references on the JVM.
 */
opaque type GoString = AnyRef

object GoString:
  /** Encodes as UTF-8. Unpaired surrogates become U+FFFD. */
  def apply(s: String): GoString = s.getBytes(UTF_8)

  /** The empty string. */
  def empty: GoString = apply("")

  extension (g: GoString)
    /** Decodes UTF-8 into a `String`. */
    def toJavaString: String = new String(bytes(g), UTF_8)

    /** Length in bytes (not characters). */
    def byteLength: Int = bytes(g).length

    /** The byte at `index` (Go's `s[index]`). */
    def byteAt(index: Int): Byte = bytes(g)(index)

    /** The two strings' bytes, one after the other (Go's `s + other`). */
    def concat(other: GoString): GoString =
      val a = bytes(g)
      val b = bytes(other)
      val out = new Array[Byte](a.length + b.length)
      System.arraycopy(a, 0, out, 0, a.length)
      System.arraycopy(b, 0, out, a.length, b.length)
      out

    /** Content equality (`==` on an opaque type compares references on the JVM). */
    def sameAs(other: GoString): Boolean = java.util.Arrays.equals(bytes(g), bytes(other))

  private def bytes(g: GoString): Array[Byte] = g.asInstanceOf[Array[Byte]]

/** Encodes a Scala string as a [[GoString]] (UTF-8), as `GoString(s)` does. */
extension (s: String) def toGoString: GoString = GoString(s)
