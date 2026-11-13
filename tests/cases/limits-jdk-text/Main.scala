import java.time.format.{DateTimeFormatter, TextStyle}
import java.util.Locale

// Limitations and differences in text (docs/_docs/user/limitations.md, "JDK and Scala libraries";
// differences.md, "Text"): each line prints what scala-go does today; the comment says what the
// JVM does. When one changes, this case fails: move the line to a case that runs on the JVM too
// (if it now matches), and update the docs.
object Main:
  def attempt(what: String)(body: => Any): Unit =
    val result =
      try String.valueOf(body)
      catch case e: Throwable => s"throws ${e.getClass.getName}${Option(e.getMessage).fold("")(": " + _)}"
    println(s"$what: $result")

  def main(args: Array[String]): Unit =
    // String.format conversions that throw, as an IllegalArgumentException that names the JDK's
    // exception in its message. JVM: "a", "a a", "1,234", "61", "1970", "0x1.0p0", "1.50", "1,234"
    attempt("%1$s")(String.format("%1$s", "a"))
    attempt("%s %<s")(String.format("%s %<s", "a"))
    attempt("%,d")(String.format("%,d", 1234))
    attempt("%h")(String.format("%h", "a"))
    attempt("%tY")(String.format("%tY", java.time.LocalDate.of(1970, 1, 1)))
    attempt("%a")(String.format("%a", 1.0))
    attempt("%.2f of BigDecimal")(String.format("%.2f", java.math.BigDecimal("1.5")))
    attempt("f\"$n%,d\"")({ val n = 1234; f"$n%,d" })

    // String.format conversions with other output. JVM: "ffffffff", "37777777770", "1.00000",
    // throws IllegalFormatConversionException, "5", "(5)"
    attempt("%x of -1")(String.format("%x", -1))
    attempt("%o of -8")(String.format("%o", -8))
    attempt("%g of 1.0")(String.format("%g", 1.0))
    attempt("%d of BigInt")(String.format("%d", BigInt(5)))
    attempt("%d of BigInteger")(String.format("%d", java.math.BigInteger.valueOf(5)))
    attempt("%(d")(String.format("%(d", -5))

    // Regular expressions are RE2: other syntax throws UnsupportedOperationException when the
    // pattern is compiled. JVM: true for each
    attempt("lookahead")("ab".matches("a(?=b)b"))
    attempt("lookbehind")("ab".matches("a(?<=a)b"))
    attempt("backreference")("aa".matches("(a)\\1"))
    attempt("possessive")("aa".matches("a*+"))
    attempt("atomic group")("aa".matches("(?>a*)"))

    // Charsets: UTF-8, ISO-8859-1 and US-ASCII only; others throw UnsupportedEncodingException.
    // JVM: 2, true, true, 1
    attempt("UTF-16 bytes")("a".getBytes("UTF-16LE").length)
    attempt("windows-1252 supported")(java.nio.charset.Charset.isSupported("windows-1252"))
    attempt("UTF-16 supported")(java.nio.charset.Charset.isSupported("UTF-16"))
    attempt("ISO-8859-15 bytes")("€".getBytes("ISO-8859-15").length)

    // No locale data: every locale formats as Locale.ROOT. JVM: "1,50", "İ", "janvier",
    // "Central European Time"
    attempt("%.2f in Locale.GERMANY")(String.format(Locale.GERMANY, "%.2f", 1.5))
    attempt("\"i\".toUpperCase(tr)")("i".toUpperCase(Locale.forLanguageTag("tr")))
    attempt("MMMM in Locale.FRANCE")(
      java.time.LocalDate.of(2026, 1, 1).format(DateTimeFormatter.ofPattern("MMMM", Locale.FRANCE)))
    attempt("zone long name")(java.time.ZoneId.of("Europe/Paris").getDisplayName(TextStyle.FULL, Locale.US))
