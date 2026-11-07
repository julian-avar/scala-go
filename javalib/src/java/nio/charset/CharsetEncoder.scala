package java.nio.charset

/** java.nio.charset.CharsetEncoder: the configuration (error actions, replacement) is the JDK's.
 *  java.nio buffers are not implemented, so encoding works on arrays, through the package-private
 *  `encode` that java.io's writers use.
 */
abstract class CharsetEncoder protected (cs: Charset, averageBytes: Float, maxBytes: Float):
  private var replacementBytes = Array[Byte]('?'.toByte)
  private var malformedAction = CodingErrorAction.REPORT
  private var unmappableAction = CodingErrorAction.REPORT

  final def charset(): Charset = cs
  final def averageBytesPerChar(): Float = averageBytes
  final def maxBytesPerChar(): Float = maxBytes

  final def replacement(): Array[Byte] = replacementBytes.clone()
  final def replaceWith(newReplacement: Array[Byte]): CharsetEncoder =
    if newReplacement == null then throw new IllegalArgumentException("Null replacement")
    if newReplacement.length == 0 then throw new IllegalArgumentException("Empty replacement")
    replacementBytes = newReplacement.clone()
    this

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

  final def reset(): CharsetEncoder = this

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

  /** Position in the input of the last `encode` call. */
  private[java] var inPos = 0
  /** Position in the output of the last `encode` call. */
  private[java] var outPos = 0

  /** Encodes `in[inPos, inEnd)` into `out[outPos, outEnd)`, advancing `inPos` and `outPos`.
   *  Returns true when it stopped because the output is full, false when it needs more input (a
   *  high surrogate at the end of the input stays unread, unless `endOfInput`). Unpaired
   *  surrogates (malformed) and unmappable characters are replaced, ignored or reported, per the
   *  configured actions.
   */
  private[java] def encode(in: Array[Char], inEnd: Int, out: Array[Byte], outEnd: Int, endOfInput: Boolean): Boolean

  /** Handles `length` malformed (or unmappable) chars at `inPos`. Returns false if the
   *  replacement does not fit.
   */
  protected[charset] final def malformed(length: Int, unmappable: Boolean, out: Array[Byte], outEnd: Int): Boolean =
    val action = if unmappable then unmappableAction else malformedAction
    if action == CodingErrorAction.REPORT then
      throw (if unmappable then new UnmappableCharacterException(length) else new MalformedInputException(length))
    if action == CodingErrorAction.REPLACE then
      val r = replacementBytes
      if outPos + r.length > outEnd then return false
      System.arraycopy(r, 0, out, outPos, r.length)
      outPos += r.length
    inPos += length
    true
