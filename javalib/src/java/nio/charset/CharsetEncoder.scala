package java.nio.charset

import java.nio.{ByteBuffer, CharBuffer}

/** java.nio.charset.CharsetEncoder, with the JDK's configuration (error actions, replacement)
 *  and state machine. As [[CharsetDecoder]], the standard charsets' coders work on arrays
 *  (`encodeArray`, which java.io's writers also use).
 */
abstract class CharsetEncoder protected (cs: Charset, averageBytes: Float, maxBytes: Float):
  private var replacementBytes = Array[Byte]('?'.toByte)
  private var malformedAction = CodingErrorAction.REPORT
  private var unmappableAction = CodingErrorAction.REPORT
  private var state = Coding.StateReset

  final def charset(): Charset = cs
  final def averageBytesPerChar(): Float = averageBytes
  final def maxBytesPerChar(): Float = maxBytes

  final def replacement(): Array[Byte] = replacementBytes.clone()
  final def replaceWith(newReplacement: Array[Byte]): CharsetEncoder =
    if newReplacement == null then throw new IllegalArgumentException("Null replacement")
    if newReplacement.length == 0 then throw new IllegalArgumentException("Empty replacement")
    if newReplacement.length > maxBytes then throw new IllegalArgumentException("Replacement too long")
    if !isLegalReplacement(newReplacement) then throw new IllegalArgumentException("Illegal replacement")
    replacementBytes = newReplacement.clone()
    this

  /** Whether the bytes decode without error in this charset. */
  def isLegalReplacement(repl: Array[Byte]): Boolean =
    val d = cs.newDecoder()
    d.inPos = 0
    d.outPos = 0
    d.decodeArray(repl, repl.length, new Array[Char](repl.length), repl.length, true) != Coding.Error &&
      d.inPos == repl.length

  final def malformedInputAction(): CodingErrorAction = malformedAction
  final def onMalformedInput(newAction: CodingErrorAction): CharsetEncoder =
    if newAction == null then throw new IllegalArgumentException("Null action")
    malformedAction = newAction
    this

  final def unmappableCharacterAction(): CodingErrorAction = unmappableAction
  final def onUnmappableCharacter(newAction: CodingErrorAction): CharsetEncoder =
    if newAction == null then throw new IllegalArgumentException("Null action")
    unmappableAction = newAction
    this

  final def reset(): CharsetEncoder =
    state = Coding.StateReset
    this

  def canEncode(c: Char): Boolean
  def canEncode(cs: CharSequence): Boolean =
    var i = 0
    while i < cs.length() do
      val c = cs.charAt(i)
      if Character.isHighSurrogate(c) && i + 1 < cs.length() && Character.isLowSurrogate(cs.charAt(i + 1)) then
        if !canEncodePair then return false
        i += 2
      else if !canEncode(c) then return false
      else i += 1
    true

  /** Whether supplementary characters (surrogate pairs) are encodable. */
  protected[charset] def canEncodePair: Boolean

  // ---- Buffers --------------------------------------------------------------------------------

  /** Encodes as much of `in` into `out` as possible (see the JDK's documentation). */
  final def encode(in: CharBuffer, out: ByteBuffer, endOfInput: Boolean): CoderResult =
    if state != Coding.StateReset && state != Coding.StateCoding && !(endOfInput && state == Coding.StateEnd) then
      Coding.illegalState(state, if endOfInput then Coding.StateEnd else Coding.StateCoding)
    state = if endOfInput then Coding.StateEnd else Coding.StateCoding
    if out.isReadOnly() then throw new java.nio.ReadOnlyBufferException()
    val inArray = in.hasArray()
    val src = if inArray then in.array() else Coding.remainingChars(in)
    val srcStart = if inArray then in.arrayOffset() + in.position() else 0
    val dst = out.array()
    val dstStart = out.arrayOffset() + out.position()
    inPos = srcStart
    outPos = dstStart
    val status = encodeArray(src, srcStart + in.remaining(), dst, dstStart + out.remaining(), endOfInput)
    in.position(in.position() + inPos - srcStart)
    out.position(out.position() + outPos - dstStart)
    Coding.result(status, errorLength, errorUnmappable)

  final def flush(out: ByteBuffer): CoderResult =
    if state != Coding.StateEnd && state != Coding.StateFlushed then Coding.illegalState(state, Coding.StateFlushed)
    state = Coding.StateFlushed
    CoderResult.UNDERFLOW

  /** Encodes the remaining chars, reporting errors as exceptions unless the actions say otherwise. */
  final def encode(in: CharBuffer): ByteBuffer =
    reset()
    state = Coding.StateEnd
    val inArray = in.hasArray()
    val src = if inArray then in.array() else Coding.remainingChars(in)
    val start = if inArray then in.arrayOffset() + in.position() else 0
    val n = in.remaining()
    var out = new Array[Byte](Math.max(16, (n * averageBytes).toInt + 1))
    inPos = start
    outPos = 0
    var status = encodeArray(src, start + n, out, out.length, true)
    while status == Coding.Overflow do
      out = java.util.Arrays.copyOf(out, 2 * out.length)
      status = encodeArray(src, start + n, out, out.length, true)
    in.position(in.position() + inPos - start)
    if status == Coding.Error then Coding.result(status, errorLength, errorUnmappable).throwException()
    state = Coding.StateFlushed
    ByteBuffer.wrap(out, 0, outPos)

  // ---- The coders' part -----------------------------------------------------------------------

  /** Position in the input of the last `encodeArray` call. */
  private[java] var inPos = 0
  /** Position in the output of the last `encodeArray` call. */
  private[java] var outPos = 0
  /** The length of the input in error, when `encodeArray` returns `Coding.Error`. */
  private[java] var errorLength = 0
  private[java] var errorUnmappable = false

  /** Encodes `in[inPos, inEnd)` into `out[outPos, outEnd)`, advancing `inPos` and `outPos`, as
   *  `CharsetDecoder.decodeArray` (a high surrogate at the end of the input stays unread, unless
   *  `endOfInput`). Unpaired surrogates (malformed) and unmappable characters are replaced,
   *  ignored or reported, per the configured actions.
   */
  private[java] def encodeArray(in: Array[Char], inEnd: Int, out: Array[Byte], outEnd: Int, endOfInput: Boolean): Int

  /** Handles `length` malformed (or unmappable) chars at `inPos`: returns `Coding.Continue`, or
   *  the status for `encodeArray` to return.
   */
  protected[charset] final def malformed(length: Int, unmappable: Boolean, out: Array[Byte], outEnd: Int): Int =
    val action = if unmappable then unmappableAction else malformedAction
    if action == CodingErrorAction.REPORT then
      errorLength = length
      errorUnmappable = unmappable
      return Coding.Error
    if action == CodingErrorAction.REPLACE then
      val r = replacementBytes
      if outPos + r.length > outEnd then return Coding.Overflow
      System.arraycopy(r, 0, out, outPos, r.length)
      outPos += r.length
    inPos += length
    Coding.Continue

  /** Encodes all of `in[from, to)` (with `Coding.Error` reported as an exception): for
   *  `Charset.encode` and `Files`.
   */
  private[java] final def encodeAll(in: Array[Char], from: Int, to: Int): Array[Byte] =
    val out = new Array[Byte](Math.max(1, ((to - from) * maxBytes).toInt))
    inPos = from
    outPos = 0
    if encodeArray(in, to, out, out.length, true) == Coding.Error then
      Coding.result(Coding.Error, errorLength, errorUnmappable).throwException()
    java.util.Arrays.copyOf(out, outPos)
