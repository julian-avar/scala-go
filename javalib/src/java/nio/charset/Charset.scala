package java.nio.charset

import scala.annotation.static

/** java.nio.charset.Charset. The supported charsets are UTF-8 (the default), ISO-8859-1 and
 *  US-ASCII.
 */
abstract class Charset protected (canonicalName: String, aliasNames: Array[String])
    extends Object
    with Comparable[Charset]:
  final def name(): String = canonicalName
  def displayName(): String = canonicalName
  final def isRegistered(): Boolean = true
  def canEncode(): Boolean = true
  def contains(cs: Charset): Boolean
  def newDecoder(): CharsetDecoder
  def newEncoder(): CharsetEncoder

  /** Encodes, replacing what cannot be encoded. */
  final def encode(cb: java.nio.CharBuffer): java.nio.ByteBuffer =
    newEncoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE).encode(cb)

  final def encode(str: String): java.nio.ByteBuffer = encode(java.nio.CharBuffer.wrap(str))

  /** Decodes, replacing malformed input. */
  final def decode(bb: java.nio.ByteBuffer): java.nio.CharBuffer =
    newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE).decode(bb)

  final def aliases(): java.util.Set[String] =
    val set = new java.util.HashSet[String]()
    for a <- aliasNames do set.add(a)
    java.util.Collections.unmodifiableSet(set)

  private[charset] def matches(name: String): Boolean =
    canonicalName.equalsIgnoreCase(name) || aliasNames.exists(_.equalsIgnoreCase(name))

  /** The name `InputStreamReader.getEncoding` returns (the historical one: "UTF8", ...). */
  private[java] def historicalName: String

  final def compareTo(that: Charset): Int = name().compareToIgnoreCase(that.name())
  override final def equals(that: Any): Boolean = that match
    case that: Charset => name() == that.name()
    case _             => false
  override final def hashCode(): Int = name().hashCode()
  override final def toString(): String = name()

object Charset:
  @static def defaultCharset(): Charset = StandardCharsets.UTF_8

  @static def forName(charsetName: String): Charset =
    val cs = lookup(charsetName)
    if cs == null then throw new UnsupportedCharsetException(charsetName)
    cs

  @static def isSupported(charsetName: String): Boolean = lookup(charsetName) != null

  @static def availableCharsets(): java.util.SortedMap[String, Charset] =
    val byName: java.util.Comparator[String] = (a, b) => a.compareToIgnoreCase(b)
    val map = new java.util.TreeMap[String, Charset](byName)
    for cs <- List(StandardCharsets.ISO_8859_1, StandardCharsets.US_ASCII, StandardCharsets.UTF_8) do map.put(cs.name(), cs)
    java.util.Collections.unmodifiableSortedMap(map)

  private def lookup(name: String): Charset =
    if name == null then throw new IllegalArgumentException("Null charset name")
    checkName(name)
    if StandardCharsets.UTF_8.matches(name) then StandardCharsets.UTF_8
    else if StandardCharsets.ISO_8859_1.matches(name) then StandardCharsets.ISO_8859_1
    else if StandardCharsets.US_ASCII.matches(name) then StandardCharsets.US_ASCII
    else null

  /** The JDK's rule: letters, digits and `-+:_.`, starting with a letter or digit. */
  private def checkName(name: String): Unit =
    def legal(c: Char, first: Boolean) =
      (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ||
        (!first && (c == '-' || c == '+' || c == ':' || c == '_' || c == '.'))
    if name.isEmpty() then throw new IllegalCharsetNameException(name)
    var i = 0
    while i < name.length() do
      if !legal(name.charAt(i), i == 0) then throw new IllegalCharsetNameException(name)
      i += 1
