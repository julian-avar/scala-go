package java.nio.charset

import java.nio.{ByteBuffer, CharBuffer}

/** java.nio.charset.CharsetDecoder, with the JDK's configuration (error actions, replacement)
 *  and state machine. The standard charsets' coders work on arrays (`decodeArray`, which
 *  java.io's readers also use); buffers without an accessible array are copied. Programs cannot
 *  define their own charsets.
 */
abstract class CharsetDecoder protected (cs: Charset, averageChars: Float, maxChars: Float):
  private var replacementString = "�"
  private var malformedAction = CodingErrorAction.REPORT
  private var unmappableAction = CodingErrorAction.REPORT
  private var state = Coding.StateReset

  final def charset(): Charset = cs
  final def averageCharsPerByte(): Float = averageChars
  final def maxCharsPerByte(): Float = maxChars

  final def replacement(): String = replacementString
  final def replaceWith(newReplacement: String): CharsetDecoder =
    if newReplacement == null then throw new IllegalArgumentException("Null replacement")
    if newReplacement.isEmpty() then throw new IllegalArgumentException("Empty replacement")
    if newReplacement.length() > maxChars then throw new IllegalArgumentException("Replacement too long")
    replacementString = newReplacement
    this

  final def malformedInputAction(): CodingErrorAction = malformedAction
  final def onMalformedInput(newAction: CodingErrorAction): CharsetDecoder =
    if newAction == null then throw new IllegalArgumentException("Null action")
    malformedAction = newAction
    this

  final def unmappableCharacterAction(): CodingErrorAction = unmappableAction
  final def onUnmappableCharacter(newAction: CodingErrorAction): CharsetDecoder =
    if newAction == null then throw new IllegalArgumentException("Null action")
    unmappableAction = newAction
    this

  final def reset(): CharsetDecoder =
    state = Coding.StateReset
    this

  def isAutoDetecting(): Boolean = false
  def isCharsetDetected(): Boolean = throw new UnsupportedOperationException()
  def detectedCharset(): Charset = throw new UnsupportedOperationException()

  // ---- Buffers --------------------------------------------------------------------------------

  /** Decodes as much of `in` into `out` as possible (see the JDK's documentation). */
  final def decode(in: ByteBuffer, out: CharBuffer, endOfInput: Boolean): CoderResult =
    if state != Coding.StateReset && state != Coding.StateCoding && !(endOfInput && state == Coding.StateEnd) then
      Coding.illegalState(state, if endOfInput then Coding.StateEnd else Coding.StateCoding)
    state = if endOfInput then Coding.StateEnd else Coding.StateCoding
    if out.isReadOnly() then throw new java.nio.ReadOnlyBufferException()
    val inArray = in.hasArray()
    val src = if inArray then in.array() else Coding.remainingBytes(in)
    val srcStart = if inArray then in.arrayOffset() + in.position() else 0
    val outArray = out.hasArray()
    val dst = if outArray then out.array() else new Array[Char](out.remaining())
    val dstStart = if outArray then out.arrayOffset() + out.position() else 0
    inPos = srcStart
    outPos = dstStart
    val status = decodeArray(src, srcStart + in.remaining(), dst, dstStart + out.remaining(), endOfInput)
    in.position(in.position() + inPos - srcStart)
    if !outArray then out.put(dst, 0, outPos)
    else out.position(out.position() + outPos - dstStart)
    Coding.result(status, errorLength, errorUnmappable)

  final def flush(out: CharBuffer): CoderResult =
    if state != Coding.StateEnd && state != Coding.StateFlushed then Coding.illegalState(state, Coding.StateFlushed)
    state = Coding.StateFlushed
    CoderResult.UNDERFLOW

  /** Decodes the remaining bytes, reporting errors as exceptions unless the actions say otherwise. */
  final def decode(in: ByteBuffer): CharBuffer =
    reset()
    state = Coding.StateEnd
    val inArray = in.hasArray()
    val src = if inArray then in.array() else Coding.remainingBytes(in)
    val start = if inArray then in.arrayOffset() + in.position() else 0
    // Replacements are at most maxCharsPerByte chars long, so the output cannot overflow.
    val chars = new Array[Char]((in.remaining() * maxChars).toInt)
    inPos = start
    outPos = 0
    val status = decodeArray(src, start + in.remaining(), chars, chars.length, true)
    in.position(in.position() + inPos - start)
    if status == Coding.Error then Coding.result(status, errorLength, errorUnmappable).throwException()
    state = Coding.StateFlushed
    CharBuffer.wrap(chars, 0, outPos)

  // ---- The coders' part -----------------------------------------------------------------------

  /** Position in the input of the last `decodeArray` call. */
  private[java] var inPos = 0
  /** Position in the output of the last `decodeArray` call. */
  private[java] var outPos = 0
  /** The length of the input in error, when `decodeArray` returns `Coding.Error`. */
  private[java] var errorLength = 0
  private[java] var errorUnmappable = false

  /** Decodes `in[inPos, inEnd)` into `out[outPos, outEnd)`, advancing `inPos` and `outPos`.
   *  Returns `Coding.Overflow` when it stopped because the output is full, `Coding.Underflow`
   *  when it needs more input (an incomplete sequence at the end of the input stays unread,
   *  unless `endOfInput`), and `Coding.Error` at malformed input that is to be reported, with
   *  `inPos` at its start. Other malformed input is replaced or ignored.
   */
  private[java] def decodeArray(in: Array[Byte], inEnd: Int, out: Array[Char], outEnd: Int, endOfInput: Boolean): Int

  /** Handles `length` malformed bytes at `inPos`: returns `Coding.Continue`, or the status for
   *  `decodeArray` to return (an error, or an overflow if the replacement does not fit).
   */
  protected[charset] final def malformed(length: Int, out: Array[Char], outEnd: Int): Int =
    if malformedAction == CodingErrorAction.REPORT then
      errorLength = length
      errorUnmappable = false
      return Coding.Error
    if malformedAction == CodingErrorAction.REPLACE then
      val r = replacementString
      if outPos + r.length() > outEnd then return Coding.Overflow
      r.getChars(0, r.length(), out, outPos)
      outPos += r.length()
    inPos += length
    Coding.Continue

  /** Decodes all of `in[from, to)` (with `Coding.Error` reported as an exception): for
   *  `Charset.decode`, `new String` and `Files`.
   */
  private[java] final def decodeAll(in: Array[Byte], from: Int, to: Int): String =
    val out = new Array[Char](Math.max(1, ((to - from) * maxChars).toInt))
    inPos = from
    outPos = 0
    if decodeArray(in, to, out, out.length, true) == Coding.Error then
      Coding.result(Coding.Error, errorLength, errorUnmappable).throwException()
    new String(out, 0, outPos)

/** The status codes of the array coders, and the state machine of the buffer API. */
private[java] object Coding:
  final val Continue = -1
  final val Underflow = 0
  final val Overflow = 1
  final val Error = 2

  final val StateReset = 0
  final val StateCoding = 1
  final val StateEnd = 2
  final val StateFlushed = 3

  private val stateNames = Array("RESET", "CODING", "CODING_END", "FLUSHED")

  def illegalState(from: Int, to: Int): Nothing =
    throw new IllegalStateException("Current state = " + stateNames(from) + ", new state = " + stateNames(to))

  def result(status: Int, errorLength: Int, unmappable: Boolean): CoderResult = status match
    case Underflow => CoderResult.UNDERFLOW
    case Overflow  => CoderResult.OVERFLOW
    case _ =>
      if unmappable then CoderResult.unmappableForLength(errorLength) else CoderResult.malformedForLength(errorLength)

  /** A copy of the remaining bytes of a buffer without an accessible array. */
  def remainingBytes(b: ByteBuffer): Array[Byte] =
    val a = new Array[Byte](b.remaining())
    b.duplicate().get(a)
    a

  def remainingChars(b: CharBuffer): Array[Char] =
    val a = new Array[Char](b.remaining())
    b.duplicate().get(a)
    a

/** java.nio.charset.CoderResult. */
final class CoderResult private (kind: Int, len: Int):
  def isUnderflow(): Boolean = kind == 0
  def isOverflow(): Boolean = kind == 1
  def isError(): Boolean = kind >= 2
  def isMalformed(): Boolean = kind == 2
  def isUnmappable(): Boolean = kind == 3

  def length(): Int =
    if !isError() then throw new UnsupportedOperationException()
    len

  def throwException(): Unit = kind match
    case 0 => throw new java.nio.BufferUnderflowException()
    case 1 => throw new java.nio.BufferOverflowException()
    case 2 => throw new MalformedInputException(len)
    case _ => throw new UnmappableCharacterException(len)

  override def toString(): String =
    val name = kind match
      case 0 => "UNDERFLOW"
      case 1 => "OVERFLOW"
      case 2 => "MALFORMED"
      case _ => "UNMAPPABLE"
    if isError() then name + "[" + len + "]" else name

object CoderResult:
  val UNDERFLOW: CoderResult = new CoderResult(0, 0)
  val OVERFLOW: CoderResult = new CoderResult(1, 0)

  private val malformed = Array.tabulate(4)(i => new CoderResult(2, i + 1))
  private val unmappable = Array.tabulate(4)(i => new CoderResult(3, i + 1))

  def malformedForLength(length: Int): CoderResult =
    if length <= 0 then throw new IllegalArgumentException("Non-positive length")
    if length <= 4 then malformed(length - 1) else new CoderResult(2, length)

  def unmappableForLength(length: Int): CoderResult =
    if length <= 0 then throw new IllegalArgumentException("Non-positive length")
    if length <= 4 then unmappable(length - 1) else new CoderResult(3, length)
