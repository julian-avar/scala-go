import java.io.{IOException, PrintWriter, StringWriter}
import java.time.{Instant, LocalDate, LocalDateTime, LocalTime, OffsetDateTime, ZoneId, ZoneOffset, ZonedDateTime}
import java.util.{Date, Formattable, FormattableFlags, Formatter, Locale}

// java.util.Formatter, behind String.format, f"...", formatted and printf: each conversion with
// its flags, widths and precisions, argument indexes, the date/time conversions (in env.txt's
// time zone), the Formatter class, and the exceptions of malformed specifiers.
object Main:
  def attempt(label: String)(body: => Any): Unit =
    val r =
      try String.valueOf(body)
      catch case e: Exception => s"${e.getClass.getName}: ${e.getMessage}"
    println(s"$label: $r")

  def fmt(format: String, args: Any*): Unit =
    attempt(format)(String.format(format, args.map(_.asInstanceOf[AnyRef])*))

  /** Shows the flags, width and precision it is formatted with. */
  final class Money(cents: Int) extends Formattable:
    def formatTo(f: Formatter, flags: Int, width: Int, precision: Int): Unit =
      val upper = (flags & FormattableFlags.UPPERCASE) != 0
      val left = (flags & FormattableFlags.LEFT_JUSTIFY) != 0
      val alt = (flags & FormattableFlags.ALTERNATE) != 0
      f.format("%s %d.%02d (width %d, precision %d, left %b, alternate %b)",
        if upper then "EUR" else "eur", cents / 100, cents % 100, width, precision, left, alt)

  def main(args: Array[String]): Unit =
    general()
    characters()
    integers()
    floatingPoint()
    indexes()
    dateTime()
    formatterClass()
    interpolators()
    errors()

  def general(): Unit =
    println("== general")
    fmt("%b %b %b %B %b", true, null, "x", false, java.lang.Boolean.FALSE)
    fmt("[%5b] [%-6b] [%.2b]", true, false, true)
    fmt("%h %H %h", "a", "hello", null)
    fmt("[%s] [%S] [%10s] [%-10s] [%.3s] [%8.2s]", "text", "upper", "right", "left", "truncate", "ab")
    fmt("%s %s %s", null, List(1, 2), Array(1).length)
    fmt("[%s] [%S] [%-#40.3s]", Money(1250), Money(99), Money(5))
    fmt("%%|%5%|%-5%|%n|")

  def characters(): Unit =
    println("== characters")
    fmt("%c %C %c %c %c", 'a', 'b', 0x1F600, 65.toByte, 0x416.toShort)
    fmt("[%3c] [%-3c] %c", 'x', 'y', null)

  def integers(): Unit =
    println("== integers")
    fmt("%d %d %d %d %d", 42, (-7).toByte, 300.toShort, Long.MinValue, java.math.BigInteger.TEN.pow(25))
    fmt("[%5d] [%-5d] [%05d] [%+d] [% d] [%+d] [%(d] [%(d]", 42, 42, -42, 5, 5, -5, -5, 5)
    fmt("%,d %,d %,d %,d %,d", 1234, 123, -1234567, Long.MaxValue, java.math.BigInteger.TEN.pow(10))
    fmt("[%,010d] [%(,d] [%+,d] [%-,12d]", 1234567, -1234567, 1234567, 1234567)
    fmt("%x %X %o %x %x %o", 255, 255, 8, -1, -1L, -8)
    fmt("%x %o %x %o", (-1).toByte, (-1).toByte, (-1).toShort, (-1).toShort)
    fmt("%#x %#X %#o %08x %-8X| %#010x", 255, 255, 8, 255, 255, 255)
    fmt("%x %X %o %(x %+x %#x", java.math.BigInteger.valueOf(-255), java.math.BigInteger.valueOf(255),
      java.math.BigInteger.valueOf(-8), java.math.BigInteger.valueOf(-255), java.math.BigInteger.valueOf(255),
      java.math.BigInteger.valueOf(-255))

  def floatingPoint(): Unit =
    println("== floating point")
    fmt("%f %e %g %a", Math.PI, Math.PI, Math.PI, Math.PI)
    fmt("%.2f %.0f %.1f %.2f %.2f %.2f", 0.125, 2.5, 0.05, 0.0004, 0.005, 99.996)
    fmt("%f %f %f", 1099511627774.0 / 3, 0.0, -0.0)
    fmt("[%08.2f] [%+.1f] [%-7.1f] [%(.1f] [% .2f] [%#.0f] [%.0f]", -3.14159, 2.0, 2.0, -2.0, 1.5, 3.0, 3.0)
    fmt("%,.2f %,f %(,.2f", 1234567.891, 1e7, -9876.5)
    fmt("%e %.3e %.1e %.2e %E %.0e %#.0e", 12345.678, 0.0, 9.96, 1.5e-7, 1e100, 5e-300, 3.0)
    fmt("%g %g %g %g %g %G", 1.0, 0.0001, 0.00001, 123456.0, 1234567.0, 1e-10)
    fmt("%.3g %.0g %.3g %10.4g|", 3.14159, 2.5, 1.0, 1234.5678)
    fmt("%f %.1f %e %g", 1.5f, 0.15f, 1e10f, 0.1f)
    fmt("%f %e %f %+f %(f %08f %E", Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity,
      Double.PositiveInfinity, Double.NegativeInfinity, Double.NaN, Double.NaN)
    fmt("%f %.20f %.3f", 1e20, 0.1, 1e-10)
    fmt("%a %A %a %a %.2a %a %a %15a| %+a", 1.0, 255.5, -0.5, 0.0, Math.PI, Double.MinPositiveValue,
      Double.MaxValue, 2.0f, 1.0)
    fmt("%.2f %.3e %g %.10f %,.2f %f", java.math.BigDecimal("1.5"), java.math.BigDecimal("12345.6789"),
      java.math.BigDecimal("0.000123"), java.math.BigDecimal("3.14159265358979323846264338327950288"),
      java.math.BigDecimal("-1234567.005"), java.math.BigDecimal.ZERO)

  def indexes(): Unit =
    println("== argument indexes")
    fmt("%2$s %1$s %2$s", "a", "b")
    fmt("%s %<s %s %<S", "a", "b")
    fmt("%1$s %s %s %3$s %<s %s", "a", "b", "c")
    fmt("%3$d %<x %<o %1$s", "unused", "two", 255)

  def dateTime(): Unit =
    println("== date and time")
    val summer = ZonedDateTime.of(2024, 7, 4, 15, 6, 7, 89_012_345, ZoneId.of("Europe/Paris"))
    val winter = ZonedDateTime.of(2023, 12, 31, 0, 1, 2, 0, ZoneId.of("America/New_York"))
    for c <- "HIklMSLNpzZsQBbhAaCYyjmdeRTrDFc" do
      fmt(s"%t$c %T$c", summer, winter)
    fmt("[%-30tc] [%30Tc] [%10tY]", summer, summer, summer)
    fmt("%tF %tT %tN %tZ", LocalDateTime.of(2024, 2, 29, 23, 59, 58, 1_000_000), LocalTime.of(1, 2, 3),
      LocalTime.of(1, 2, 3, 456_789_012), ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.ofHours(2)))
    fmt("%tc %tz %tZ", OffsetDateTime.of(2024, 5, 6, 7, 8, 9, 0, ZoneOffset.ofHoursMinutes(-3, -30)),
      OffsetDateTime.of(2024, 5, 6, 7, 8, 9, 0, ZoneOffset.ofHoursMinutes(5, 45)), ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneId.of("UTC")))
    // A Long or a Date is an instant in the default time zone (env.txt)
    fmt("%tc | %tc | %tQ %ts", 0L, Date(1_700_000_000_000L), 1_700_000_000_123L, Date(1_700_000_000_123L))
    fmt("%tY-%<tm-%<td %<tH:%<tM %<tZ", 1_720_000_000_000L)
    fmt("%tY %tj %ta %tA", LocalDate.of(5, 1, 1), LocalDate.of(2024, 12, 31), LocalDate.of(2024, 12, 31), LocalDate.of(2024, 12, 31))
    fmt("%s %tY", "null:", null)
    fmt("%tH", LocalDate.of(2024, 1, 1))
    fmt("%tY", Instant.EPOCH)
    fmt("%tZ", LocalDateTime.of(2024, 1, 1, 0, 0))
    fmt("%ts", LocalDateTime.of(2024, 1, 1, 0, 0))
    fmt("%tY", 2024)

  def formatterClass(): Unit =
    println("== Formatter")
    val sb = new java.lang.StringBuilder("> ")
    val f = new Formatter(sb)
    f.format("%d", 1).format(" and %s", "two")
    println(s"$sb | ${f.out() eq sb} | $f")
    println(new Formatter().format("%08.3f", -1.5))
    println(s"${new Formatter(Locale.ROOT).locale() == Locale.ROOT} ${new Formatter(Locale.FRANCE).locale()}")
    val sw = new StringWriter()
    val toWriter = new Formatter(sw)
    toWriter.format("%s=%d", "x", 3)
    toWriter.flush()
    println(s"$sw ${toWriter.ioException()}")
    toWriter.close()
    attempt("format after close")(toWriter.format("%d", 1))
    attempt("toString after close")(toWriter.toString)
    attempt("ioException after close")(toWriter.ioException())
    val failing = new Formatter(new Appendable:
      def append(c: Char): Appendable = throw new IOException("full")
      def append(cs: CharSequence): Appendable = throw new IOException("full")
      def append(cs: CharSequence, start: Int, end: Int): Appendable = throw new IOException("full"))
    failing.format("%s", "a")
    println(s"IOException kept: ${failing.ioException().getMessage}")
    println(java.util.Formatter.BigDecimalLayoutForm.values().mkString(" "))

  def interpolators(): Unit =
    println("== f, format, formatted, printf")
    val n = 1234
    val x = 2.5
    val name = "scala"
    println(f"$n%,d|$n%08x|$x%6.2f|$x%-6.1e|$name%10s|$name%-7S|${n.toLong}%d|${'c'}%c|${true}%b|$x%%")
    println(f"${BigInt(5)}%d ${BigInt("123456789012345678901234567890")}%,d ${BigInt(-255)}%x ${BigDecimal("1.505")}%.2f")
    println(f"${java.math.BigInteger.valueOf(5)}%d ${java.math.BigDecimal("2.5")}%.0f")
    println(f"$n%s $n%<x $x%a ${Math.PI}%.3g")
    println("%d-%s-%.1f".format(1, "a", 0.25))
    println("%s|%5s".formatted("a", "b"))
    println(String.format(Locale.ROOT, "%,.2f", 1234.5))
    System.out.printf("%s=%d%n", "printf", 7)
    System.out.format(Locale.ROOT, "%05.1f%n", 2.25)
    val sw = new StringWriter()
    val pw = new PrintWriter(sw)
    pw.printf("%d-%s|%5.2f%n", 42, "x", 3.14159).format(Locale.ROOT, "%,d", 1000000)
    pw.flush()
    print(sw)
    println()

  def errors(): Unit =
    println("== errors")
    fmt("%.f", 1.0)
    fmt("%", 1)
    fmt("abc%", 1)
    fmt("%q", 1)
    fmt("%-", 1)
    fmt("%--5d", 1)
    fmt("%--q", 1)
    fmt("%-0$s", "a")
    fmt("%<1$s", "a")
    fmt("%.2-s", "a")
    fmt("%0$s", "a")
    fmt("%2147483648$s", "a")
    fmt("%99999999999d", 1)
    fmt("%.99999999999f", 1.0)
    fmt("%s %s", "a")
    fmt("%3$s", "a")
    fmt("%<s", "a")
    fmt("%d", "x")
    fmt("%d", 1.5)
    fmt("%f", 1)
    fmt("%c", "x")
    fmt("%c", -1)
    fmt("%c", 0x110000)
    fmt("%a", java.math.BigDecimal.ONE)
    fmt("%-d", 1)
    fmt("%0s", "a")
    fmt("%-08d", 1)
    fmt("%+ d", 1)
    fmt("%#d", 1)
    fmt("%.2d", 1)
    fmt("%,x", 1)
    fmt("%+x", 1)
    fmt("%(s", "a")
    fmt("%#s", "a")
    fmt("%,e", 1.0)
    fmt("%.2c", 'a')
    fmt("%D", 1)
    fmt("%F", 1.0)
    fmt("%#g", 1.0)
    fmt("%5n")
    fmt("%-n")
    fmt("%.3%")
    fmt("%#%")
    fmt("%-%")
    fmt("%tq", 1L)
    fmt("%t", 1L)
    fmt("%t%", 1L)
    fmt("%-tY", 1L)
    fmt("%.2tY", 1L)
    fmt("%+tY", 1L)
    fmt("%#tY", 1L)
