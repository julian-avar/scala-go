// Adapted from Scala Native's javalib (Apache-2.0, see javalib/LICENSE-scala-native.md).
// Ported from Scala.js, commit: 0383e9f, dated: 2021-03-07
//
// scala-go: Scala Native's Formatter and FormatterImpl are one class (Scala 3 only); format
// strings are parsed by scanning their characters, as the JDK parses them, instead of Apache
// Harmony's state machine over a CharBuffer; the date/time conversions ('t') are new; there is
// no locale data, so every locale formats as Locale.ROOT; an IOException ends `format`, as on
// the JDK, instead of being trapped at each write.

package java.util

import java.io._
import java.lang.{
  Boolean => JBoolean, Double => JDouble, StringBuilder => JStringBuilder
}
import java.math.{BigDecimal, BigInteger}
import java.nio.charset.Charset
import java.time.{DateTimeException, Instant, ZoneOffset, ZonedDateTime}
import java.time.temporal.{ChronoField, TemporalAccessor, TemporalQueries}

import scala.annotation.switch

final class Formatter(private var dest: Appendable, formatterLocale: Locale)
    extends Closeable
    with Flushable {
  import Formatter.Flags._
  import Formatter._

  if (dest == null) {
    dest = new JStringBuilder()
  }

  private var closed: Boolean = false
  private var lastIOException: IOException = null

  def this() = this(new JStringBuilder(), Locale.getDefault())
  def this(a: Appendable) = this(a, Locale.getDefault())
  def this(l: Locale) = this(new JStringBuilder(), l)

  def this(os: OutputStream, csn: String, l: Locale) =
    this(new BufferedWriter(new OutputStreamWriter(os, csn)), l)
  def this(os: OutputStream, csn: String) =
    this(os, csn, Locale.getDefault())
  def this(os: OutputStream) =
    this(os, Charset.defaultCharset().name(), Locale.getDefault())

  def this(file: File, csn: String, l: Locale) =
    this(
      {
        var fout: FileOutputStream = null
        try {
          fout = new FileOutputStream(file)
          val writer = new OutputStreamWriter(fout, csn)
          new BufferedWriter(writer)
        } catch {
          case e @ (_: RuntimeException | _: UnsupportedEncodingException) =>
            if (fout != null) {
              try { fout.close() }
              catch {
                case _: IOException => () // silently
              }
            }
            throw e
        }
      },
      l
    )
  def this(file: File, csn: String) =
    this(file, csn, Locale.getDefault())
  def this(file: File) =
    this(new FileOutputStream(file))

  def this(ps: PrintStream) =
    this(
      {
        if (null == ps)
          throw new NullPointerException()
        ps
      },
      Locale.getDefault()
    )

  def this(fileName: String, csn: String, l: Locale) =
    this(new File(fileName), csn, l)
  def this(fileName: String, csn: String) =
    this(new File(fileName), csn)
  def this(fileName: String) =
    this(new File(fileName))

  def close(): Unit = {
    if (!closed) {
      dest match {
        case cl: Closeable =>
          try { cl.close() }
          catch { case th: IOException => lastIOException = th }
        case _ =>
      }
    }
    closed = true
  }

  def flush(): Unit = {
    checkNotClosed()
    dest match {
      case fl: Flushable =>
        try { fl.flush() }
        catch { case th: IOException => lastIOException = th }
      case _ =>
    }
  }

  def format(format: String, args: Array[AnyRef]): Formatter =
    this.format(formatterLocale, format, args)

  /** scala-go: `l` only matters to a `Formattable`, which can ask for it: there is no locale
   *  data, so every locale formats as Locale.ROOT.
   */
  def format(l: Locale, format: String, args: Array[AnyRef]): Formatter = {
    checkNotClosed()
    try {
      formatToDest(format, args)
    } catch {
      case th: IOException => lastIOException = th
    }
    this
  }

  private def formatToDest(format: String, args: Array[AnyRef]): Unit = {
    // scalastyle:off return

    var lastImplicitArgIndex: Int = 0
    var lastArgIndex: Int = 0 // required for < flag

    val fmtLength = format.length()
    var fmtIndex: Int = 0

    while (fmtIndex != fmtLength) {
      // Process a portion without '%'
      val nextPercentIndex = format.indexOf('%', fmtIndex)
      if (nextPercentIndex < 0) {
        dest.append(format, fmtIndex, fmtLength)
        return
      }
      dest.append(format, fmtIndex, nextPercentIndex)

      /* scala-go: parse a format specifier,
       *
       *   %[argument_index$][flags][width][.precision][t]conversion
       *
       * as the JDK's pattern `%(\d+\$)?([-#+ 0,(\<]*)?(\d+)?(\.\d+)?([tT])?([a-zA-Z%])` does.
       * The JDK reports duplicate flags while parsing, and a specifier that does not parse as
       * an unknown conversion: the character after the '%', or '%' at the end.
       *
       * 1. DuplicateFormatFlagsException, UnknownFormatConversionException
       */

      val specifierStart = nextPercentIndex + 1
      var i = specifierStart

      var argIndex = Unset
      val argIndexEnd = skipDigits(format, i)
      if (argIndexEnd != i && charAtOrZero(format, argIndexEnd) == '$') {
        argIndex = parsePositiveInt(format, i, argIndexEnd)
        i = argIndexEnd + 1
      }

      var flagBits = 0
      var flag = flagOf(charAtOrZero(format, i))
      while (flag != 0) {
        if ((flagBits & flag) != 0)
          throw new DuplicateFormatFlagsException(format.charAt(i).toString())
        flagBits |= flag
        i += 1
        flag = flagOf(charAtOrZero(format, i))
      }

      val widthEnd = skipDigits(format, i)
      val width =
        if (widthEnd == i) Unset
        else parsePositiveInt(format, i, widthEnd)
      i = widthEnd

      var precision = Unset
      if (charAtOrZero(format, i) == '.') {
        val precisionEnd = skipDigits(format, i + 1)
        if (precisionEnd != i + 1) { // otherwise the conversion is '.'
          precision = parsePositiveInt(format, i + 1, precisionEnd)
          i = precisionEnd
        }
      }

      val conversion = charAtOrZero(format, i)
      if (!isConversionChar(conversion)) {
        val c =
          if (specifierStart == fmtLength) '%'
          else format.charAt(specifierStart)
        throwUnknownFormatConversionException(c.toString())
      }

      // The conversion after 't' or 'T', or 0
      val dateTimeConversion =
        if ((conversion == 't' || conversion == 'T') &&
            isConversionChar(charAtOrZero(format, i + 1))) {
          i += 1
          format.charAt(i)
        } else '\u0000'
      fmtIndex = i + 1

      // Indexes and sizes that are not representable as an Int
      if (argIndex == 0 || argIndex == Int.MinValue)
        throw new IllegalFormatArgumentIndexException(argIndex)
      if (width == Int.MinValue)
        throwIllegalFormatWidthException(width)
      if (precision == Int.MinValue)
        throwIllegalFormatPrecisionException(precision)

      if (conversion >= 'A' && conversion <= 'Z')
        flagBits |= UpperCase
      val flags = new Flags(flagBits)

      // For error reporting
      def fullFormatSpecifier: String =
        format.substring(nextPercentIndex, fmtIndex)

      /* At this point, we need to branch off for 'n', because it has a
       * completely different error reporting spec. In particular, it must
       * throw an IllegalFormatFlagsException if any flag is specified,
       * although the regular mechanism would throw a
       * FormatFlagsConversionMismatchException.
       *
       * It is also the only conversion that throws
       * IllegalFormatWidthException, so we use this forced special path to
       * also take care of that one.
       *
       * We also treat '%' specially. Although its spec suggests that its
       * validation could be done in the generic way, experimentation suggests
       * that it behaves differently. Anyway, once 'n' has its special path,
       * '%' becomes the only one that does not take an argument, and so it
       * would need a special path later. So we handle it here and get it out
       * of the way. This further allows the generic path to only deal with
       * ASCII letters, which is convenient.
       */

      if (conversion == 'n') {
        if (precision != Unset)
          throwIllegalFormatPrecisionException(precision)
        if (width != Unset)
          throwIllegalFormatWidthException(width)
        if (flags.bits != 0)
          throwIllegalFormatFlagsException(flags)

        dest.append(System.lineSeparator())
      } else if (conversion == '%') {
        if (precision != Unset)
          throwIllegalFormatPrecisionException(precision)
        // scala-go: any flag but '-' is illegal, as on the JDK
        if (flags.hasAnyOf(~LeftAlign))
          throwIllegalFormatFlagsException(flags)
        if (flags.leftAlign && width == Unset)
          throwMissingFormatWidthException(fullFormatSpecifier)

        padAndSendToDestNoZeroPad(flags, width, "%")
      } else {
        // Because of how we parse, we know that `conversion` is an ASCII letter
        val conversionLower =
          if (flags.upperCase) (conversion + ('a' - 'A')).toChar
          else conversion

        if (dateTimeConversion != 0) {
          // scala-go: the JDK's checks, in its order
          if (precision != Unset)
            throwIllegalFormatPrecisionException(precision)
          if (DateTimeConversions.indexOf(dateTimeConversion) < 0)
            throwUnknownFormatConversionException(
              format.substring(fmtIndex - 2, fmtIndex)
            )
          checkFlagsConversionMismatch(
            dateTimeConversion,
            flags,
            ~(LeftAlign | UseLastIndex | UpperCase)
          )
          if (flags.leftAlign && width == Unset)
            throwMissingFormatWidthException(fullFormatSpecifier)
        } else {
          // 2. UnknownFormatConversionException

          val illegalFlags = ConversionsIllegalFlags(conversionLower - 'a')
          if (illegalFlags == -1 || (flags.bits & UpperCase & illegalFlags) != 0)
            throwUnknownFormatConversionException(conversion.toString())

          // 3. MissingFormatWidthException (scala-go: for '0' only where it is legal, as on the JDK)

          val needsWidth = LeftAlign | (ZeroPad & ~illegalFlags)
          if (flags.hasAnyOf(needsWidth) && width == Unset)
            throwMissingFormatWidthException(fullFormatSpecifier)

          // 4. IllegalFormatFlagsException

          checkIllegalFormatFlags(flags)

          // 5. IllegalFormatPrecisionException

          if (precision != Unset && (illegalFlags & Precision) != 0)
            throwIllegalFormatPrecisionException(precision)

          // 6. FormatFlagsConversionMismatchException

          checkFlagsConversionMismatch(conversionLower, flags, illegalFlags)
        }

        /* Finally, get the argument and format it.
         *
         * 7. MissingFormatArgumentException | IllegalFormatConversionException | IllegalFormatCodePointException
         *
         * The first one is handled here, while we extract the argument.
         * The other two are handled in formatArg().
         */

        val actualArgIndex =
          if (flags.useLastIndex) {
            // Explicitly use the last index
            lastArgIndex
          } else if (argIndex == Unset) {
            lastImplicitArgIndex += 1
            lastImplicitArgIndex
          } else {
            argIndex
          }

        // scala-go: a null array is no arguments, each null
        if (actualArgIndex <= 0 || (args != null && actualArgIndex > args.length))
          throwMissingFormatArgumentException(fullFormatSpecifier)

        lastArgIndex = actualArgIndex
        val arg = if (args == null) null else args(actualArgIndex - 1)

        // Format the arg. We handle `null` in a generic way, except for 'b'

        if (arg == null && conversionLower != 'b' && conversionLower != 's') {
          formatNonNumericString(flags, width, precision, "null")
        } else if (dateTimeConversion != 0) {
          formatDateTime(arg, dateTimeConversion, flags, width)
        } else {
          formatArg(arg, conversionLower, flags, width, precision)
        }
      }
    }

    // scalastyle:on return
  }

  private def formatArg(
      arg: Any,
      conversionLower: Char,
      flags: Flags,
      width: Int,
      precision: Int
  ): Unit = {

    @inline def illegalFormatConversion(): Nothing =
      throwIllegalFormatConversionException(conversionLower, arg)

    (conversionLower: @switch) match {
      case 'b' =>
        val str = arg match {
          case arg: JBoolean => arg.toString
          case null          => "false"
          case _             => "true"
        }
        formatNonNumericString(flags, width, precision, str)

      case 'h' =>
        val str = Integer.toHexString(arg.hashCode)
        formatNonNumericString(flags, width, precision, str)

      case 's' =>
        arg match {
          case formattable: Formattable =>
            val formattableFlags = {
              (if (flags.leftAlign) FormattableFlags.LEFT_JUSTIFY else 0) |
                (if (flags.altFormat) FormattableFlags.ALTERNATE else 0) |
                (if (flags.upperCase) FormattableFlags.UPPERCASE else 0)
            }
            formattable.formatTo(this, formattableFlags, width, precision)

          case _ =>
            /* The Formattable case accepts AltFormat, therefore it is not
             * present in the generic `ConversionsIllegalFlags` table. However,
             * it is illegal for any other value, so we must check it now.
             */
            checkFlagsConversionMismatch(conversionLower, flags, AltFormat)

            val str = String.valueOf(arg)
            formatNonNumericString(flags, width, precision, str)
        }

      case 'c' =>
        def checkValidCodePoint(arg: Int): Unit = {
          if (arg < 0 || arg > Character.MAX_CODE_POINT)
            throwIllegalFormatCodePointException(arg)
        }

        val str = arg match {
          case arg: Char =>
            arg.toString()
          case arg: Byte =>
            checkValidCodePoint(arg)
            arg.toChar.toString()
          case arg: Short =>
            checkValidCodePoint(arg)
            arg.toChar.toString()
          case arg: Int =>
            checkValidCodePoint(arg)
            if (arg < Character.MIN_SUPPLEMENTARY_CODE_POINT) {
              new String(Array(arg.toChar))
            } else {
              new String(
                Array(
                  (0xd800 | ((arg >> 10) - (0x10000 >> 10))).toChar,
                  (0xdc00 | (arg & 0x3ff)).toChar
                )
              )
            }
          case _ =>
            illegalFormatConversion()
        }
        formatNonNumericString(flags, width, -1, str)

      case 'd' =>
        val str = arg match {
          case arg: Byte       => arg.toString()
          case arg: Short      => arg.toString()
          case arg: Int        => arg.toString()
          case arg: Long       => arg.toString()
          case arg: BigInteger => arg.toString()
          case _               => illegalFormatConversion()
        }
        formatNumericString(flags, width, str)

      case 'o' | 'x' =>
        // Octal/hex formatting is not localized

        val isOctal = conversionLower == 'o'
        val prefix = {
          if (!flags.altFormat) ""
          else if (isOctal) "0"
          else if (flags.upperCase) "0X"
          else "0x"
        }

        arg match {
          case arg: BigInteger =>
            val radix = if (isOctal) 8 else 16
            formatNumericString(flags, width, arg.toString(radix), prefix)

          case _ =>
            def intToOctalOrHexString(arg: Int): String =
              if (isOctal) java.lang.Integer.toOctalString(arg)
              else java.lang.Integer.toHexString(arg)

            val str = arg match {
              case arg: Byte =>
                intToOctalOrHexString(arg & 0xff)
              case arg: Short =>
                intToOctalOrHexString(arg & 0xffff)
              case arg: Int =>
                intToOctalOrHexString(arg)
              case arg: Long =>
                if (isOctal) java.lang.Long.toOctalString(arg)
                else java.lang.Long.toHexString(arg)
              case _ =>
                illegalFormatConversion()
            }

            /* The Int and Long conversions have extra illegal flags, which are
             * not in the `ConversionsIllegalFlags` table because they are
             * legal for BigIntegers. We must check them now.
             */
            checkFlagsConversionMismatch(
              conversionLower,
              flags,
              PositivePlus | PositiveSpace | NegativeParen
            )

            padAndSendToDest(
              flags,
              width,
              prefix,
              applyNumberUpperCase(flags, str)
            )
        }

      case 'e' | 'f' | 'g' =>
        def formatDecimal(x: Decimal): Unit = {
          /* The alternative format # of 'e', 'f' and 'g' is to force a
           * decimal separator.
           */
          val forceDecimalSep = flags.altFormat
          val actualPrecision =
            if (precision >= 0) precision
            else 6

          val notation = conversionLower match {
            case 'e' =>
              computerizedScientificNotation(
                x,
                digitsAfterDot = actualPrecision,
                forceDecimalSep
              )
            case 'f' =>
              decimalNotation(x, scale = actualPrecision, forceDecimalSep)
            case _ =>
              generalScientificNotation(
                x,
                precision = actualPrecision,
                forceDecimalSep
              )
          }
          formatNumericString(flags, width, notation)
        }

        def formatDouble(arg: Double): Unit = {
          if (JDouble.isNaN(arg) || JDouble.isInfinite(arg))
            formatNaNOrInfinite(flags, width, arg)
          else
            formatDecimal(numberToDecimal(arg))
        }

        arg match {
          case arg: Float =>
            formatDouble(arg.toDouble)
          case arg: Double =>
            formatDouble(arg)
          case arg: BigDecimal =>
            formatDecimal(bigDecimalToDecimal(arg))
          case _ =>
            illegalFormatConversion()
        }

      case 'a' =>
        // Floating point hex formatting is not localized
        arg match {
          case arg: Float =>
            formatHexFloatingPoint(flags, width, precision, arg.toDouble)
          case arg: Double =>
            formatHexFloatingPoint(flags, width, precision, arg)
          case _ =>
            illegalFormatConversion()
        }

      case _ =>
        throw new AssertionError(
          "Unknown conversion '" + conversionLower + "' was not rejected earlier"
        )
    }
  }

  @inline private def checkIllegalFormatFlags(flags: Flags): Unit = {
    if (flags.hasAllOf(LeftAlign | ZeroPad) ||
        flags.hasAllOf(PositivePlus | PositiveSpace)) {
      throwIllegalFormatFlagsException(flags)
    }
  }

  @inline private def checkFlagsConversionMismatch(
      conversionLower: Char,
      flags: Flags,
      illegalFlags: Int
  ): Unit = {
    if (flags.hasAnyOf(illegalFlags)) {
      throwFormatFlagsConversionMismatchException(
        conversionLower,
        flags,
        illegalFlags
      )
    }
  }

  /* Should in theory be a method of `Flags`. See the comment on that class
   * about why we keep it here.
   */
  private def flagsToString(flags: Flags): String = {
    (if (flags.leftAlign) "-" else "") +
      (if (flags.altFormat) "#" else "") +
      (if (flags.positivePlus) "+" else "") +
      (if (flags.positiveSpace) " " else "") +
      (if (flags.zeroPad) "0" else "") +
      (if (flags.useGroupingSeps) "," else "") +
      (if (flags.negativeParen) "(" else "") +
      (if (flags.useLastIndex) "<" else "")
  }

  private def computerizedScientificNotation(
      x: Decimal,
      digitsAfterDot: Int,
      forceDecimalSep: Boolean
  ): String = {

    val rounded = x.round(precision = 1 + digitsAfterDot)

    // Worst case: -d.<digitsAfterPos>e+2147483647
    val builder = new JStringBuilder(15 + digitsAfterDot)

    // Sign
    if (rounded.negative)
      builder.append('-')

    val intStr = rounded.unscaledValue
    val dotPos = 1
    val fractionalDigitCount = intStr.length() - dotPos
    val missingZeros = digitsAfterDot - fractionalDigitCount

    // Significand
    builder.append(intStr, 0, dotPos)
    if (fractionalDigitCount + missingZeros > 0 || forceDecimalSep) {
      builder.append('.')
      builder.append(intStr, dotPos, intStr.length())
      appendZeros(builder, missingZeros)
    }

    // Exponent
    val exponent = fractionalDigitCount - rounded.scale
    builder.append(if (exponent < 0) "e-" else "e+")
    val exponentAbsStr = Math.abs(exponent).toString()
    if (exponentAbsStr.length() == 1)
      builder.append('0')
    builder.append(exponentAbsStr)

    builder.toString()
  }

  private def decimalNotation(
      x: Decimal,
      scale: Int,
      forceDecimalSep: Boolean
  ): String = {

    val rounded = x.setScale(scale)

    val intStr = rounded.unscaledValue
    val intStrLen = intStr.length()

    // Overapproximation of the worst case: -<intStr>.<scale 0's>
    val builder = new JStringBuilder(2 + intStrLen + scale)

    if (rounded.negative)
      builder.append('-')

    if (intStrLen > scale) {
      // There is at least one digit of intStr before the '.'
      // (we always take this branch when scale == 0)
      val dotPos = intStrLen - scale
      builder.append(intStr, 0, dotPos)
      if (scale > 0 || forceDecimalSep) {
        builder.append('.')
        builder.append(intStr, dotPos, intStrLen)
      }
    } else {
      // All the digits of intStr are to the right of the '.'
      builder.append("0.")
      appendZeros(builder, scale - intStrLen)
      builder.append(intStr)
    }

    builder.toString()
  }

  private def generalScientificNotation(
      x: Decimal,
      precision: Int,
      forceDecimalSep: Boolean
  ): String = {
    val p =
      if (precision == 0) 1
      else precision

    /* The JavaDoc says:
     *
     * > After rounding for the precision, the formatting of the resulting
     * > magnitude m depends on its value.
     *
     * so we first round to `p` significant digits before deciding which
     * notation to use, based on the order of magnitude of the result. The
     * order of magnitude is an integer `n` such that
     *
     *   10^n <= abs(x) < 10^(n+1)
     *
     * (except if x is a zero value, in which case it is 0).
     *
     * We also pass `rounded` to the dedicated notation function. Both
     * functions perform rounding of their own, but the rounding methods will
     * detect that no further rounding is necessary, so it is worth it.
     */
    val rounded = x.round(p)
    val orderOfMagnitude = (rounded.precision - 1) - rounded.scale
    if (orderOfMagnitude >= -4 && orderOfMagnitude < p) {
      decimalNotation(
        rounded,
        scale = Math.max(0, p - orderOfMagnitude - 1),
        forceDecimalSep
      )
    } else {
      computerizedScientificNotation(
        rounded,
        digitsAfterDot = p - 1,
        forceDecimalSep
      )
    }
  }

  /** Format an argument for the 'a' conversion.
   *
   *  This conversion requires quite some code, compared to the others, and is
   *  therefore extracted into separate functions.
   *
   *  There is some logic that is duplicated from
   *  `java.lang.Double.toHexString()`. It cannot be factored out because:
   *
   *    - this method deals with subnormals in a very weird way when the
   *      precision is set and is <= 12, and
   *    - the handling of padding is fairly specific to `Formatter`, and would
   *      not lend itself well to be factored with the more straightforward code
   *      in `Double.toHexString()`.
   */
  private def formatHexFloatingPoint(
      flags: Flags,
      width: Int,
      precision: Int,
      arg: Double
  ): Unit = {

    if (JDouble.isNaN(arg) || JDouble.isInfinite(arg)) {
      formatNaNOrInfinite(flags, width, arg)
    } else {
      // Extract the raw bits from the argument

      val ebits = 11 // exponent size
      val mbits = 52 // mantissa size
      val mbitsMask = ((1L << mbits) - 1L)
      val bias = (1 << (ebits - 1)) - 1

      val bits = JDouble.doubleToLongBits(arg)
      val negative = bits < 0
      val explicitMBits = bits & mbitsMask
      val biasedExponent = (bits >>> mbits).toInt & ((1 << ebits) - 1)

      // Compute the actual precision

      val actualPrecision = {
        if (precision == 0) 1 // apparently, this is how it behaves on the JVM
        else if (precision > 12) -1 // important for subnormals
        else precision
      }

      // Sign string

      val signStr = {
        if (negative) "-"
        else if (flags.positivePlus) "+"
        else if (flags.positiveSpace) " "
        else ""
      }

      /* Extract the implicit bit, the mantissa, and the exponent.
       * Also apply the artificial normalization of subnormals when the
       * actualPrecision is in the interval [1, 12].
       */

      val (implicitBitStr, mantissa, exponent) = if (biasedExponent == 0) {
        if (explicitMBits == 0L) {
          // Zero
          ("0", 0L, 0)
        } else {
          // Subnormal
          if (actualPrecision == -1) {
            ("0", explicitMBits, -1022)
          } else {
            // Artificial normalization, required by the 'a' conversion spec
            val leadingZeros =
              java.lang.Long.numberOfLeadingZeros(explicitMBits)
            val shift = (leadingZeros + 1) - (64 - mbits)
            val normalizedMantissa = (explicitMBits << shift) & mbitsMask
            val normalizedExponent = -1022 - shift
            ("1", normalizedMantissa, normalizedExponent)
          }
        }
      } else {
        // Normalized
        ("1", explicitMBits, biasedExponent - bias)
      }

      // Apply the rounding mandated by the precision

      val roundedMantissa = if (actualPrecision == -1) {
        mantissa
      } else {
        val roundingUnit =
          1L << (mbits - (actualPrecision * 4)) // 4 bits per hex character
        val droppedPartMask = roundingUnit - 1
        val halfRoundingUnit = roundingUnit >> 1

        val truncated = mantissa & ~droppedPartMask
        val droppedPart = mantissa & droppedPartMask

        /* The JavaDoc is not clear about what flavor of rounding should be
         * used. We use round-half-to-even to mimic the behavior of the JVM.
         */
        if (droppedPart < halfRoundingUnit)
          truncated
        else if (droppedPart > halfRoundingUnit)
          truncated + roundingUnit
        else if ((truncated & roundingUnit) == 0L) // truncated is even
          truncated
        else
          truncated + roundingUnit
      }

      // Mantissa string

      val mantissaStr = {
        val baseStr = java.lang.Long.toHexString(roundedMantissa)
        val padded =
          "0000000000000".substring(baseStr.length()) + baseStr // 13 zeros

        assert(
          padded.length() == 13 && (13 * 4 == mbits),
          "padded mantissa does not have the right number of bits"
        )

        // ~ padded.dropRightWhile(_ == '0') but keep at least minLength chars
        val minLength = Math.max(1, actualPrecision)
        var len = padded.length
        while (len > minLength && padded.charAt(len - 1) == '0')
          len -= 1
        padded.substring(0, len)
      }

      // Exponent string

      val exponentStr = Integer.toString(exponent)

      // Assemble, pad and send to dest

      val prefix = signStr + (if (flags.upperCase) "0X" else "0x")
      val rest = implicitBitStr + "." + mantissaStr + "p" + exponentStr

      padAndSendToDest(
        flags,
        width,
        prefix,
        applyNumberUpperCase(flags, rest)
      )
    }
  }

  /** scala-go: the date/time conversions, which Scala.js and Scala Native do not have, as the
   *  JDK specifies them. A `Long` (milliseconds since the epoch) or a `Date` is the instant in
   *  the default time zone, as the JDK's `Calendar` holds it; there is no `Calendar`.
   */
  private def formatDateTime(
      arg: Any,
      conversion: Char,
      flags: Flags,
      width: Int
  ): Unit = {
    val temporal: TemporalAccessor = arg match {
      case arg: Long             => inDefaultZone(Instant.ofEpochMilli(arg))
      case arg: Date             => inDefaultZone(arg.toInstant())
      case arg: TemporalAccessor => arg
      case _ => throwIllegalFormatConversionException(conversion, arg)
    }
    val builder = new JStringBuilder()
    try {
      appendDateTime(builder, temporal, conversion)
    } catch {
      case _: DateTimeException =>
        throwIllegalFormatConversionException(conversion, arg)
    }
    formatNonNumericString(flags, width, -1, builder.toString())
  }

  private def formatNonNumericString(
      flags: Flags,
      width: Int,
      precision: Int,
      str: String
  ): Unit = {

    val truncatedStr =
      if (precision < 0 || precision >= str.length()) str
      else str.substring(0, precision)
    padAndSendToDestNoZeroPad(
      flags,
      width,
      applyUpperCase(flags, truncatedStr)
    )
  }

  private def formatNaNOrInfinite(flags: Flags, width: Int, x: Double): Unit = {
    // NaN and Infinite formatting are not localized

    val str = if (JDouble.isNaN(x)) {
      "NaN"
    } else if (x > 0.0) {
      if (flags.positivePlus) "+Infinity"
      else if (flags.positiveSpace) " Infinity"
      else "Infinity"
    } else {
      if (flags.negativeParen) "(Infinity)"
      else "-Infinity"
    }

    padAndSendToDestNoZeroPad(flags, width, applyNumberUpperCase(flags, str))
  }

  private def formatNumericString(
      flags: Flags,
      width: Int,
      str: String,
      basePrefix: String = ""
  ): Unit = {
    /* Flags for which a numeric string needs to be decomposed and transformed,
     * not just padded and/or uppercased. We can write fast-paths in this
     * method if none of them are present.
     */
    val TransformativeFlags =
      PositivePlus | PositiveSpace | UseGroupingSeps | NegativeParen | AltFormat

    if (str.length >= width && !flags.hasAnyOf(TransformativeFlags)) {
      // Super-fast-path
      dest.append(applyNumberUpperCase(flags, str))
    } else if (!flags.hasAnyOf(TransformativeFlags | ZeroPad)) {
      // Fast-path that does not need to inspect the string
      padAndSendToDestNoZeroPad(flags, width, applyNumberUpperCase(flags, str))
    } else {
      // Extract prefix and rest, based on flags and the presence of a sign
      val (numberPrefix, rest0) = if (str.charAt(0) != '-') {
        if (flags.positivePlus)
          ("+", str)
        else if (flags.positiveSpace)
          (" ", str)
        else
          ("", str)
      } else {
        if (flags.negativeParen)
          ("(", str.substring(1) + ")")
        else
          ("-", str.substring(1))
      }

      val prefix = numberPrefix + basePrefix

      // Insert grouping separators, if required
      val rest =
        if (flags.useGroupingSeps) insertGroupingCommas(rest0)
        else rest0

      // Apply uppercase, pad and send
      padAndSendToDest(
        flags,
        width,
        prefix,
        applyNumberUpperCase(flags, rest)
      )
    }
  }

  /** Inserts grouping commas at the right positions: every 3 digits, as in Locale.ROOT. */
  private def insertGroupingCommas(s: String): String = {
    val groupingSize = 3

    val len = s.length
    var index = 0
    while (index != len && isAsciiDigit(s.charAt(index))) {
      index += 1
    }

    index -= groupingSize

    if (index <= 0) {
      s
    } else {
      var result = s.substring(index)
      while (index > groupingSize) {
        val next = index - groupingSize
        result = s.substring(next, index) + "," + result
        index = next
      }
      s.substring(0, index) + "," + result
    }
  }

  private def applyNumberUpperCase(flags: Flags, str: String): String =
    if (flags.upperCase)
      str.toUpperCase() // uppercasing is not localized for numbers
    else str

  private def applyUpperCase(flags: Flags, str: String): String =
    if (flags.upperCase) str.toUpperCase()
    else str

  /** This method ignores `flags.zeroPad` and `flags.upperCase`. */
  private def padAndSendToDestNoZeroPad(
      flags: Flags,
      width: Int,
      str: String
  ): Unit = {

    val len = str.length

    if (len >= width) {
      dest.append(str)
    } else if (flags.leftAlign) {
      dest.append(str)
      dest.append(" ".repeat(width - len))
    } else {
      dest.append(" ".repeat(width - len))
      dest.append(str)
    }
  }

  /** This method ignores `flags.upperCase`. */
  private def padAndSendToDest(
      flags: Flags,
      width: Int,
      prefix: String,
      str: String
  ): Unit = {

    val len = prefix.length + str.length

    if (len >= width) {
      dest.append(prefix)
      dest.append(str)
    } else if (flags.zeroPad) {
      dest.append(prefix)
      dest.append("0".repeat(width - len))
      dest.append(str)
    } else if (flags.leftAlign) {
      dest.append(prefix)
      dest.append(str)
      dest.append(" ".repeat(width - len))
    } else {
      dest.append(" ".repeat(width - len))
      dest.append(prefix)
      dest.append(str)
    }
  }

  def ioException(): IOException = lastIOException

  def locale(): Locale = {
    checkNotClosed()
    formatterLocale
  }

  def out(): Appendable = {
    checkNotClosed()
    dest
  }

  override def toString(): String = {
    checkNotClosed()
    dest.toString()
  }

  @inline private def checkNotClosed(): Unit = {
    if (closed)
      throw new FormatterClosedException()
  }

  /* Helpers to throw exceptions with all the right arguments.
   *
   * Some are direct forwarders, like `IllegalFormatPrecisionException`; they
   * are here for consistency.
   */

  private def throwUnknownFormatConversionException(conversion: String): Nothing =
    throw new UnknownFormatConversionException(conversion)

  private def throwIllegalFormatPrecisionException(precision: Int): Nothing =
    throw new IllegalFormatPrecisionException(precision)

  private def throwIllegalFormatWidthException(width: Int): Nothing =
    throw new IllegalFormatWidthException(width)

  private def throwIllegalFormatFlagsException(flags: Flags): Nothing =
    throw new IllegalFormatFlagsException(flagsToString(flags))

  private def throwMissingFormatWidthException(
      fullFormatSpecifier: String
  ): Nothing =
    throw new MissingFormatWidthException(fullFormatSpecifier)

  private def throwFormatFlagsConversionMismatchException(
      conversionLower: Char,
      flags: Flags,
      illegalFlags: Int
  ): Nothing = {
    throw new FormatFlagsConversionMismatchException(
      flagsToString(new Flags(flags.bits & illegalFlags)),
      conversionLower
    )
  }

  private def throwMissingFormatArgumentException(
      fullFormatSpecifier: String
  ): Nothing =
    throw new MissingFormatArgumentException(fullFormatSpecifier)

  private def throwIllegalFormatConversionException(
      conversionLower: Char,
      arg: Any
  ): Nothing =
    throw new IllegalFormatConversionException(conversionLower, arg.getClass)

  private def throwIllegalFormatCodePointException(arg: Int): Nothing =
    throw new IllegalFormatCodePointException(arg)

}

object Formatter {
  enum BigDecimalLayoutForm(name: String, ordinal: Int)
      extends java.lang.Enum[BigDecimalLayoutForm] {
    case SCIENTIFIC extends BigDecimalLayoutForm("SCIENTIFIC", 0)
    case DECIMAL_FLOAT extends BigDecimalLayoutForm("DECIMAL_FLOAT", 1)
  }

  private final val Unset = -1

  private def appendZeros(builder: JStringBuilder, count: Int): builder.type = {
    val twentyZeros = "00000000000000000000"
    if (count <= 20) {
      builder.append(twentyZeros, 0, count)
    } else {
      var remaining = count
      while (remaining > 20) {
        builder.append(twentyZeros)
        remaining -= 20
      }
      builder.append(twentyZeros, 0, remaining)
    }
    builder
  }

  private inline def assert(inline condition: Boolean, inline msg: String): Unit = {
    if (!condition)
      throw new AssertionError(msg)
  }

  /* This class is never used in a place where it would box, so it will
   * completely disappear at link-time. Make sure to keep it that way.
   *
   * Also note that methods in this class are moved to the companion object, so
   * also take into account the comment on `object Flags`. In particular, do
   * not add non-inlineable methods in this class.
   */
  private final class Flags(val bits: Int) extends AnyVal {
    import Flags._

    @inline def leftAlign: Boolean = (bits & LeftAlign) != 0
    @inline def altFormat: Boolean = (bits & AltFormat) != 0
    @inline def positivePlus: Boolean = (bits & PositivePlus) != 0
    @inline def positiveSpace: Boolean = (bits & PositiveSpace) != 0
    @inline def zeroPad: Boolean = (bits & ZeroPad) != 0
    @inline def useGroupingSeps: Boolean = (bits & UseGroupingSeps) != 0
    @inline def negativeParen: Boolean = (bits & NegativeParen) != 0
    @inline def useLastIndex: Boolean = (bits & UseLastIndex) != 0
    @inline def upperCase: Boolean = (bits & UpperCase) != 0

    @inline def hasAnyOf(testBits: Int): Boolean = (bits & testBits) != 0

    @inline def hasAllOf(testBits: Int): Boolean = (bits & testBits) == testBits
  }

  /* This object only contains `final val`s and (synthetic) `@inline`
   * methods. Therefore, it will completely disappear at link-time. Make sure
   * to keep it that way. In particular, do not add non-inlineable methods.
   */
  private object Flags {
    final val LeftAlign = 0x001
    final val AltFormat = 0x002
    final val PositivePlus = 0x004
    final val PositiveSpace = 0x008
    final val ZeroPad = 0x010
    final val UseGroupingSeps = 0x020
    final val NegativeParen = 0x040
    final val UseLastIndex = 0x080
    final val UpperCase = 0x100
    final val Precision = 0x200 // used in ConversionsIllegalFlags
  }

  /** scala-go: the flag a character stands for, or 0. */
  private def flagOf(c: Char): Int = {
    import Flags._
    (c: @switch) match {
      case '-' => LeftAlign
      case '#' => AltFormat
      case '+' => PositivePlus
      case ' ' => PositiveSpace
      case '0' => ZeroPad
      case ',' => UseGroupingSeps
      case '(' => NegativeParen
      case '<' => UseLastIndex
      case _   => 0
    }
  }

  private val ConversionsIllegalFlags: Array[Int] = {
    import Flags._

    val NumericOnlyFlags =
      PositivePlus | PositiveSpace | ZeroPad | UseGroupingSeps | NegativeParen

    // 'n' and '%' are not here because they have special paths in `format`

    // format: off
    Array(
      UseGroupingSeps | NegativeParen,          // a
      NumericOnlyFlags | AltFormat,             // b
      NumericOnlyFlags | AltFormat | Precision, // c
      AltFormat | UpperCase | Precision,        // d
      UseGroupingSeps,                          // e
      UpperCase,                                // f
      AltFormat,                                // g
      NumericOnlyFlags | AltFormat,             // h
      -1, -1, -1, -1, -1, -1,                   // i -> n
      UseGroupingSeps | UpperCase | Precision,  // o
      -1, -1, -1,                               // p -> r
      NumericOnlyFlags,                         // s
      -1, -1, -1, -1,                           // t -> w
      UseGroupingSeps | Precision,              // x
      -1, -1                                    // y -> z
    )
    // format: on
  }

  /** scala-go: the conversions after 't'. */
  private final val DateTimeConversions = "HIklMSLNpzZsQBbhAaCYyjmdeRTrDFc"

  private val MonthNames = Array(
    "January", "February", "March", "April", "May", "June", "July", "August",
    "September", "October", "November", "December"
  )

  private val DayNames = Array(
    "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"
  )

  private def inDefaultZone(instant: Instant): ZonedDateTime =
    ZonedDateTime.ofInstant(instant, TimeZone.getDefault().toZoneId())

  /** scala-go: appends the date/time conversion `c` of `t`, in English (Locale.ROOT). */
  private def appendDateTime(
      builder: JStringBuilder,
      t: TemporalAccessor,
      c: Char
  ): Unit = {
    import ChronoField._
    (c: @switch) match {
      case 'H' => appendPadded(builder, t.get(HOUR_OF_DAY), 2)
      case 'I' => appendPadded(builder, t.get(CLOCK_HOUR_OF_AMPM), 2)
      case 'k' => builder.append(t.get(HOUR_OF_DAY))
      case 'l' => builder.append(t.get(CLOCK_HOUR_OF_AMPM))
      case 'M' => appendPadded(builder, t.get(MINUTE_OF_HOUR), 2)
      case 'S' => appendPadded(builder, t.get(SECOND_OF_MINUTE), 2)
      case 'L' => appendPadded(builder, t.get(MILLI_OF_SECOND), 3)
      case 'N' =>
        val nanos =
          if (t.isSupported(NANO_OF_SECOND)) t.get(NANO_OF_SECOND)
          else t.get(MILLI_OF_SECOND) * 1000000
        appendPadded(builder, nanos, 9)
      case 'p' =>
        builder.append(if (t.get(AMPM_OF_DAY) == 0) "am" else "pm")
      case 'z' =>
        val offset = t.get(OFFSET_SECONDS)
        val minutes = Math.abs(offset) / 60
        builder.append(if (offset < 0) '-' else '+')
        appendPadded(builder, minutes / 60 * 100 + minutes % 60, 4)
      case 'Z' =>
        builder.append(zoneName(t))
      case 's' =>
        builder.append(t.getLong(INSTANT_SECONDS))
      case 'Q' =>
        builder.append(
          t.getLong(INSTANT_SECONDS) * 1000L + t.getLong(MILLI_OF_SECOND)
        )
      case 'B' =>
        builder.append(MonthNames(t.get(MONTH_OF_YEAR) - 1))
      case 'b' | 'h' =>
        builder.append(MonthNames(t.get(MONTH_OF_YEAR) - 1), 0, 3)
      case 'A' =>
        builder.append(DayNames(t.get(DAY_OF_WEEK) % 7))
      case 'a' =>
        builder.append(DayNames(t.get(DAY_OF_WEEK) % 7), 0, 3)
      case 'C' => appendPadded(builder, t.get(YEAR_OF_ERA) / 100, 2)
      case 'Y' => appendPadded(builder, t.get(YEAR_OF_ERA), 4)
      case 'y' => appendPadded(builder, t.get(YEAR_OF_ERA) % 100, 2)
      case 'j' => appendPadded(builder, t.get(DAY_OF_YEAR), 3)
      case 'm' => appendPadded(builder, t.get(MONTH_OF_YEAR), 2)
      case 'd' => appendPadded(builder, t.get(DAY_OF_MONTH), 2)
      case 'e' => builder.append(t.get(DAY_OF_MONTH))
      case 'R' => appendDateTimes(builder, t, "H:M")
      case 'T' => appendDateTimes(builder, t, "H:M:S")
      case 'r' =>
        appendDateTimes(builder, t, "I:M:S ")
        builder.append(if (t.get(AMPM_OF_DAY) == 0) "AM" else "PM")
      case 'D' => appendDateTimes(builder, t, "m/d/y")
      case 'F' => appendDateTimes(builder, t, "Y-m-d")
      case 'c' => appendDateTimes(builder, t, "a b d T Z Y")
    }
  }

  /** Appends the conversions in `pattern`, each a letter, and the other characters as they are. */
  private def appendDateTimes(
      builder: JStringBuilder,
      t: TemporalAccessor,
      pattern: String
  ): Unit = {
    var i = 0
    while (i != pattern.length()) {
      val c = pattern.charAt(i)
      if (isConversionChar(c)) appendDateTime(builder, t, c)
      else builder.append(c)
      i += 1
    }
  }

  private def appendPadded(builder: JStringBuilder, value: Int, width: Int): Unit = {
    val digits = Integer.toString(value)
    appendZeros(builder, width - digits.length())
    builder.append(digits)
  }

  /** The zone's ID, or for a region at an instant, its abbreviation (as `TimeZone` names it). */
  private def zoneName(t: TemporalAccessor): String = {
    val zone = t.query(TemporalQueries.zone)
    if (zone == null)
      throw new DateTimeException("no zone")
    zone match {
      case _: ZoneOffset => zone.getId
      case _ if t.isSupported(ChronoField.INSTANT_SECONDS) =>
        new ZoneTimeZone(zone.getId, zone).displayName(Instant.from(t))
      case _ => zone.getId
    }
  }

  /** Converts a `Double` into a `Decimal` that has as few digits as possible
   *  while still uniquely identifying `x`.
   *
   *  We do this by converting the absolute value of the number into a string
   *  using its built-in `toString()` conversion. By ECMAScript's spec, this
   *  yields a decimal representation with as few significant digits as
   *  possible, although it can be in fixed notation or in computerized
   *  scientific notation.
   *
   *  We then parse that string to recover the integer part, the factional part
   *  and the exponent; the latter two being optional.
   *
   *  From the parts, we construct a `Decimal`, making sure to create one that
   *  does not have leading 0's (as it is forbidden by `Decimal`'s invariants).
   */
  private def numberToDecimal(x: Double): Decimal = {
    if (x == 0.0) {
      val negative = 1.0 / x < 0.0
      Decimal.zero(negative)
    } else {
      val negative = x < 0.0
      val s = JDouble.toString(if (negative) -x else x)

      val ePos = s.indexOf('E')
      val e =
        if (ePos < 0) 0
        else Integer.parseInt(s.substring(ePos + 1))
      val significandEnd = if (ePos < 0) s.length() else ePos

      val dotPos = s.indexOf('.')
      if (dotPos < 0) {
        // No '.'; there cannot be leading 0's (x == 0.0 was handled before)
        val unscaledValue = s.substring(0, significandEnd)
        val scale = -e
        new Decimal(negative, unscaledValue, scale)
      } else {
        // There is a '.'; there can be leading 0's, which we must remove
        val digits =
          s.substring(0, dotPos) + s.substring(dotPos + 1, significandEnd)
        val digitsLen = digits.length()
        var i = 0
        while (i < digitsLen && digits.charAt(i) == '0')
          i += 1
        val unscaledValue = digits.substring(i)
        val scale = -e + (significandEnd - (dotPos + 1))
        new Decimal(negative, unscaledValue, scale)
      }
    }
  }

  /** Converts a `BigDecimal` into a `Decimal`.
   *
   *  Zero values are considered positive for the conversion.
   *
   *  All other values keep their sign, unscaled value and scale.
   */
  private def bigDecimalToDecimal(x: BigDecimal): Decimal = {
    val unscaledValueWithSign = x.unscaledValue().toString()

    if (unscaledValueWithSign == "0") {
      Decimal.zero(negative = false)
    } else {
      val negative = unscaledValueWithSign.charAt(0) == '-'
      val unscaledValue =
        if (negative) unscaledValueWithSign.substring(1)
        else unscaledValueWithSign
      val scale = x.scale()
      new Decimal(negative, unscaledValue, scale)
    }
  }

  /** A decimal representation of a number.
   *
   *  An instance of this class represents the number whose absolute value is
   *  `unscaledValue × 10^(-scale)`, and that is negative iff `negative` is
   *  true.
   *
   *  The `unscaledValue` is stored as a String of decimal digits, i.e.,
   *  characters in the range ['0', '9'], expressed in base 10. Leading 0's are
   *  *not* valid.
   *
   *  As an exception, a zero value is represented by an `unscaledValue` of
   *  `"0"`. The scale of zero value is always 0.
   *
   *  `Decimal` is similar to `BigDecimal`, with some differences:
   *
   *    - `Decimal` distinguishes +0 from -0.
   *    - The unscaled value of `Decimal` is stored in base 10.
   *
   *  The methods it exposes have the same meaning as for BigDecimal, with the
   *  only rounding mode being HALF_UP.
   */
  private final class Decimal(
      val negative: Boolean,
      val unscaledValue: String,
      val scale: Int
  ) {

    def isZero: Boolean = unscaledValue == "0"

    /** The number of digits in the unscaled value.
     *
     *  The precision of a zero value is 1.
     */
    def precision: Int = unscaledValue.length()

    /** Rounds the number so that it has at most the given precision, i.e., at
     *  most the given number of digits in its `unscaledValue`.
     *
     *  The given `precision` must be greater than 0.
     */
    def round(precision: Int): Decimal = {
      assert(
        precision > 0,
        "Decimal.round() called with non-positive precision"
      )

      roundAtPos(roundingPos = precision)
    }

    /** Returns a new Decimal instance with the same value, possibly rounded,
     *  with the given scale.
     *
     *  If this is a zero value, the same value is returned (a zero value must
     *  always have a 0 scale). Rounding may also cause the result to be a zero
     *  value, in which case its scale must be 0 as well. Otherwise, the result
     *  is non-zero and is guaranteed to have exactly the given new scale.
     */
    def setScale(newScale: Int): Decimal = {
      val roundingPos = unscaledValue.length() + newScale - scale
      val rounded = roundAtPos(roundingPos)
      assert(
        rounded.isZero || rounded.scale <= newScale,
        "roundAtPos returned a non-zero value with a scale too large"
      )

      if (rounded.isZero || rounded.scale == newScale) {
        rounded
      } else {
        val newUnscaledValueBuilder = new JStringBuilder(rounded.unscaledValue)
        appendZeros(newUnscaledValueBuilder, newScale - rounded.scale)
        new Decimal(negative, newUnscaledValueBuilder.toString(), newScale)
      }
    }

    /** Rounds the number at the given position in its `unscaledValue`.
     *
     *  The `roundingPos` may be any integer value.
     *
     *    - If it is < 0, the result is always a zero value.
     *    - If it is >= `unscaledValue.lenght()`, the result is always the same
     *      value.
     *    - Otherwise, the `unscaledValue` will be truncated at `roundingPos`,
     *      and rounded up iff `unscaledValue.charAt(roundingPos) >= '5'`.
     *
     *  The value of `negative` is always preserved.
     *
     *  Unless the result is a zero value, the following guarantees apply:
     *
     *    - its scale is guaranteed to be at most `scale -
     *      (unscaledValue.length() - roundingPos)`.
     *    - its precision is guaranteed to be at most `max(1, roundingPos)`.
     */
    private def roundAtPos(roundingPos: Int): Decimal = {
      val digits = this.unscaledValue // local copy
      val digitsLen = digits.length()

      if (roundingPos < 0) {
        Decimal.zero(negative)
      } else if (roundingPos >= digitsLen) {
        this // no rounding necessary
      } else {
        @inline def scaleAtPos(pos: Int): Int = scale - (digitsLen - pos)

        if (digits.charAt(roundingPos) < '5') {
          // Truncate at roundingPos
          if (roundingPos == 0) {
            Decimal.zero(negative)
          } else {
            new Decimal(
              negative,
              digits.substring(0, roundingPos),
              scaleAtPos(roundingPos)
            )
          }
        } else {
          // Truncate and increment at roundingPos

          // Find the position of the last non-9 digit in the truncated digits (can be -1)
          var lastNonNinePos = roundingPos - 1
          while (lastNonNinePos >= 0 && digits.charAt(lastNonNinePos) == '9')
            lastNonNinePos -= 1

          val newUnscaledValue = {
            if (lastNonNinePos < 0) {
              "1"
            } else {
              digits.substring(0, lastNonNinePos) +
                (digits.charAt(lastNonNinePos) + 1).toChar
            }
          }

          val newScale = scaleAtPos(lastNonNinePos + 1)

          new Decimal(negative, newUnscaledValue, newScale)
        }
      }
    }

    // for debugging only
    override def toString(): String =
      s"Decimal($negative, $unscaledValue, $scale)"
  }

  private object Decimal {
    @inline def zero(negative: Boolean): Decimal =
      new Decimal(negative, "0", 0)
  }

  // scala-go: helpers of the format string parser

  private def charAtOrZero(s: String, i: Int): Char =
    if (i < s.length()) s.charAt(i) else '\u0000'

  private def skipDigits(s: String, start: Int): Int = {
    var i = start
    while (i < s.length() && isAsciiDigit(s.charAt(i)))
      i += 1
    i
  }

  /** The value of the digits in `s` from `start` to `end`, or Int.MinValue if it is too large. */
  private def parsePositiveInt(s: String, start: Int, end: Int): Int = {
    var value = 0L
    var i = start
    while (i != end && value <= Int.MaxValue) {
      value = value * 10 + (s.charAt(i) - '0')
      i += 1
    }
    if (value <= Int.MaxValue) value.toInt else Int.MinValue
  }

  private def isConversionChar(c: Char): Boolean =
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '%'

  @inline
  private[util] def isAsciiDigit(c: Char) = c >= '0' && c <= '9'
}
