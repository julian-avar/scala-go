package java.nio.charset

/** java.nio.charset.CharsetDecoder: the configuration (error actions, replacement) is the JDK's.
 *  java.nio buffers are not implemented, so decoding works on arrays, through the package-private
 *  `decode` that java.io's readers use.
 */
abstract class CharsetDecoder protected (cs: Charset, averageChars: Float, maxChars: Float):
  private var replacementString = "�"
  private var malformedAction = CodingErrorAction.REPORT
  private var unmappableAction = CodingErrorAction.REPORT

  final def charset(): Charset = cs
  final def averageCharsPerByte(): Float = averageChars
  final def maxCharsPerByte(): Float = maxChars

  final def replacement(): String = replacementString
  final def replaceWith(newReplacement: String): CharsetDecoder =
    if newReplacement == null then throw new IllegalArgumentException("Null replacement")
    if newReplacement.isEmpty() then throw new IllegalArgumentException("Empty replacement")
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

  final def reset(): CharsetDecoder = this

  /** Position in the input of the last `decode` call. */
  private[java] var inPos = 0
  /** Position in the output of the last `decode` call. */
  private[java] var outPos = 0

  /** Decodes `in[inPos, inEnd)` into `out[outPos, outEnd)`, advancing `inPos` and `outPos`.
   *  Returns true when it stopped because the output is full, false when it needs more input
   *  (an incomplete sequence at the end of the input stays unread, unless `endOfInput`). Malformed
   *  input is replaced, ignored or reported (MalformedInputException), per the configured action.
   */
  private[java] def decode(in: Array[Byte], inEnd: Int, out: Array[Char], outEnd: Int, endOfInput: Boolean): Boolean

  /** Handles `length` malformed bytes at `inPos`. Returns false if the replacement does not fit. */
  protected[charset] final def malformed(length: Int, out: Array[Char], outEnd: Int): Boolean =
    if malformedAction == CodingErrorAction.REPORT then throw new MalformedInputException(length)
    if malformedAction == CodingErrorAction.REPLACE then
      val r = replacementString
      if outPos + r.length() > outEnd then return false
      r.getChars(0, r.length(), out, outPos)
      outPos += r.length()
    inPos += length
    true
