package java.nio.charset

// The coders of the standard charsets, on arrays (see CharsetDecoder.decodeArray and
// CharsetEncoder.encodeArray). Malformed UTF-8 is handled as by the JDK: each maximal prefix of a
// valid sequence is one malformed unit.

private[charset] final class UTF8Decoder(cs: Charset) extends CharsetDecoder(cs, 1.0f, 1.0f):
  private[java] def decodeArray(in: Array[Byte], inEnd: Int, out: Array[Char], outEnd: Int, endOfInput: Boolean): Int =
    while inPos < inEnd do
      if outPos >= outEnd then return Coding.Overflow
      val b1 = in(inPos)
      if b1 >= 0 then
        // ASCII run.
        val n = Math.min(inEnd - inPos, outEnd - outPos)
        var i = 0
        while i < n && in(inPos + i) >= 0 do
          out(outPos + i) = in(inPos + i).toChar
          i += 1
        inPos += i
        outPos += i
      else
        val lead = b1 & 0xff
        // The sequence's length, and the range of its second byte (the others are 80..BF).
        val length =
          if lead >= 0xc2 && lead <= 0xdf then 2
          else if lead >= 0xe0 && lead <= 0xef then 3
          else if lead >= 0xf0 && lead <= 0xf4 then 4
          else 1
        val lo = if lead == 0xe0 then 0xa0 else if lead == 0xf0 then 0x90 else 0x80
        val hi = if lead == 0xed then 0x9f else if lead == 0xf4 then 0x8f else 0xbf
        if length == 1 then
          val r = malformed(1, out, outEnd)
          if r >= 0 then return r
        else
          // Number of valid bytes of the sequence available.
          var valid = 1
          var ok = true
          while ok && valid < length && inPos + valid < inEnd do
            val b = in(inPos + valid) & 0xff
            ok = if valid == 1 then b >= lo && b <= hi else b >= 0x80 && b <= 0xbf
            if ok then valid += 1
          if valid == length then
            val cp = length match
              case 2 => ((lead & 0x1f) << 6) | (in(inPos + 1) & 0x3f)
              case 3 => ((lead & 0x0f) << 12) | ((in(inPos + 1) & 0x3f) << 6) | (in(inPos + 2) & 0x3f)
              case _ =>
                ((lead & 0x07) << 18) | ((in(inPos + 1) & 0x3f) << 12) | ((in(inPos + 2) & 0x3f) << 6) |
                  (in(inPos + 3) & 0x3f)
            if cp >= 0x10000 then
              if outPos + 2 > outEnd then return Coding.Overflow
              out(outPos) = (0xd7c0 + (cp >> 10)).toChar // high surrogate
              out(outPos + 1) = (0xdc00 | (cp & 0x3ff)).toChar
              outPos += 2
            else
              out(outPos) = cp.toChar
              outPos += 1
            inPos += length
          else if ok && !endOfInput then return Coding.Underflow // incomplete: wait for more input
          else
            val r = malformed(valid, out, outEnd)
            if r >= 0 then return r
    Coding.Underflow

private[charset] final class UTF8Encoder(cs: Charset) extends CharsetEncoder(cs, 1.1f, 3.0f):
  def canEncode(c: Char): Boolean = !Character.isSurrogate(c)
  protected[charset] def canEncodePair: Boolean = true

  private[java] def encodeArray(in: Array[Char], inEnd: Int, out: Array[Byte], outEnd: Int, endOfInput: Boolean): Int =
    while inPos < inEnd do
      val c = in(inPos)
      if c < 0x80 then
        if outPos >= outEnd then return Coding.Overflow
        out(outPos) = c.toByte
        outPos += 1
        inPos += 1
      else if c < 0x800 then
        if outPos + 2 > outEnd then return Coding.Overflow
        out(outPos) = (0xc0 | (c >> 6)).toByte
        out(outPos + 1) = (0x80 | (c & 0x3f)).toByte
        outPos += 2
        inPos += 1
      else if Character.isHighSurrogate(c) then
        if inPos + 1 >= inEnd then
          if !endOfInput then return Coding.Underflow // wait for the low surrogate
          val r = malformed(1, false, out, outEnd)
          if r >= 0 then return r
        else if Character.isLowSurrogate(in(inPos + 1)) then
          if outPos + 4 > outEnd then return Coding.Overflow
          val cp = ((c - 0xd800) << 10) + (in(inPos + 1) - 0xdc00) + 0x10000
          out(outPos) = (0xf0 | (cp >> 18)).toByte
          out(outPos + 1) = (0x80 | ((cp >> 12) & 0x3f)).toByte
          out(outPos + 2) = (0x80 | ((cp >> 6) & 0x3f)).toByte
          out(outPos + 3) = (0x80 | (cp & 0x3f)).toByte
          outPos += 4
          inPos += 2
        else
          val r = malformed(1, false, out, outEnd)
          if r >= 0 then return r
      else if Character.isLowSurrogate(c) then
        val r = malformed(1, false, out, outEnd)
        if r >= 0 then return r
      else
        if outPos + 3 > outEnd then return Coding.Overflow
        out(outPos) = (0xe0 | (c >> 12)).toByte
        out(outPos + 1) = (0x80 | ((c >> 6) & 0x3f)).toByte
        out(outPos + 2) = (0x80 | (c & 0x3f)).toByte
        outPos += 3
        inPos += 1
    Coding.Underflow

/** ISO-8859-1 (`max` 0xff) and US-ASCII (`max` 0x7f). */
private[charset] final class Latin1Decoder(cs: Charset, max: Int) extends CharsetDecoder(cs, 1.0f, 1.0f):
  private[java] def decodeArray(in: Array[Byte], inEnd: Int, out: Array[Char], outEnd: Int, endOfInput: Boolean): Int =
    while inPos < inEnd do
      if outPos >= outEnd then return Coding.Overflow
      val b = in(inPos) & 0xff
      if b <= max then
        out(outPos) = b.toChar
        outPos += 1
        inPos += 1
      else
        val r = malformed(1, out, outEnd)
        if r >= 0 then return r
    Coding.Underflow

private[charset] final class Latin1Encoder(cs: Charset, max: Int) extends CharsetEncoder(cs, 1.0f, 1.0f):
  def canEncode(c: Char): Boolean = c <= max
  protected[charset] def canEncodePair: Boolean = false

  private[java] def encodeArray(in: Array[Char], inEnd: Int, out: Array[Byte], outEnd: Int, endOfInput: Boolean): Int =
    while inPos < inEnd do
      val c = in(inPos)
      if c <= max then
        if outPos >= outEnd then return Coding.Overflow
        out(outPos) = c.toByte
        outPos += 1
        inPos += 1
      else if Character.isHighSurrogate(c) then
        if inPos + 1 >= inEnd then
          if !endOfInput then return Coding.Underflow
          val r = malformed(1, false, out, outEnd)
          if r >= 0 then return r
        else if Character.isLowSurrogate(in(inPos + 1)) then
          // a valid pair: unmappable
          val r = malformed(2, true, out, outEnd)
          if r >= 0 then return r
        else
          val r = malformed(1, false, out, outEnd)
          if r >= 0 then return r
      else if Character.isLowSurrogate(c) then
        val r = malformed(1, false, out, outEnd)
        if r >= 0 then return r
      else
        val r = malformed(1, true, out, outEnd)
        if r >= 0 then return r
    Coding.Underflow
