import java.time.*
import java.time.format.{DateTimeFormatter, DateTimeParseException, FormatStyle, TextStyle}
import java.time.temporal.{ChronoField, ChronoUnit, IsoFields, TemporalAdjusters, WeekFields}
import java.util.Locale

/** java.time: the case runs with TZ=America/New_York (env.txt), and uses fixed instants only. */
object Main {
  def attempt(label: String)(body: => Any): Unit =
    try println(s"$label: ${body}")
    catch { case e: Exception => println(s"$label: ${e.getClass.getName}: ${e.getMessage}") }

  def main(args: Array[String]): Unit = {
    dates()
    times()
    instantsAndDurations()
    zones()
    formatting()
    parsing()
    legacy()
    exceptions()
  }

  def dates(): Unit = {
    println("-- LocalDate")
    val d = LocalDate.of(2024, 2, 28)
    println(s"$d ${d.plusDays(1)} ${d.plusDays(2)} ${d.plusMonths(1)} ${d.plusYears(1).plusDays(1)}")
    println(s"${d.isLeapYear} ${LocalDate.of(2023, 1, 1).isLeapYear} ${LocalDate.of(1900, 1, 1).isLeapYear} ${LocalDate.of(2000, 1, 1).isLeapYear}")
    println(s"${d.getDayOfWeek} ${d.getDayOfYear} ${d.lengthOfMonth} ${d.lengthOfYear} ${d.getMonth} ${d.getMonthValue}")
    println(s"${LocalDate.of(2024, 1, 31).plusMonths(1)} ${LocalDate.of(2024, 3, 31).minusMonths(1)}")
    println(s"${d.withDayOfMonth(1)} ${d.`with`(TemporalAdjusters.lastDayOfMonth())} ${d.`with`(TemporalAdjusters.firstDayOfNextMonth())}")
    println(s"${d.`with`(TemporalAdjusters.next(DayOfWeek.MONDAY))} ${d.`with`(TemporalAdjusters.previousOrSame(DayOfWeek.WEDNESDAY))}")
    println(s"${d.`with`(TemporalAdjusters.firstInMonth(DayOfWeek.FRIDAY))} ${d.`with`(TemporalAdjusters.lastDayOfYear())}")
    val e = LocalDate.of(2025, 7, 4)
    println(s"${d.isBefore(e)} ${d.isAfter(e)} ${d.compareTo(e)} ${d == LocalDate.parse("2024-02-28")} ${d.hashCode == LocalDate.parse("2024-02-28").hashCode}")
    println(s"${ChronoUnit.DAYS.between(d, e)} ${ChronoUnit.WEEKS.between(d, e)} ${ChronoUnit.MONTHS.between(d, e)} ${d.until(e, ChronoUnit.YEARS)}")
    println(s"${Period.between(d, e)} ${d.until(e)} ${Period.between(e, d)} ${e.toEpochDay} ${LocalDate.ofEpochDay(0)}")
    println(s"${LocalDate.ofYearDay(2024, 366)} ${LocalDate.MIN} ${LocalDate.MAX} ${LocalDate.EPOCH}")
    println(s"${d.get(ChronoField.ALIGNED_WEEK_OF_YEAR)} ${d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)} ${d.get(IsoFields.QUARTER_OF_YEAR)} ${d.get(WeekFields.ISO.weekOfYear())}")
    println(s"${LocalDate.of(-1, 12, 31)} ${LocalDate.of(10000, 1, 1)} ${LocalDate.of(-10000, 1, 1)}")
    println((-70 to 70 by 13).map(n => LocalDate.of(2024, 1, 31).plusDays(n)).mkString(" "))
    println(Seq(27L, 28L, 29L, 30L, 58L, 59L, 60L, 365L, -1L).map(LocalDate.of(2023, 12, 3).plusDays(_)).mkString(" "))

    println("-- Period, DayOfWeek, Month, Year, YearMonth, MonthDay")
    val p = Period.of(1, 14, 40)
    println(s"$p ${p.normalized} ${p.toTotalMonths} ${Period.ofWeeks(3)} ${Period.ZERO} ${Period.parse("P1Y2M3D").plusDays(5)} ${p.negated} ${p.multipliedBy(2)}")
    println(s"${d.plus(p)} ${d.minus(Period.ofMonths(13))}")
    println(s"${DayOfWeek.MONDAY.plus(10)} ${DayOfWeek.SUNDAY.getValue} ${DayOfWeek.of(3)} ${DayOfWeek.values.toList} ${DayOfWeek.valueOf("FRIDAY").ordinal}")
    println(s"${DayOfWeek.TUESDAY.getDisplayName(TextStyle.FULL, Locale.US)} ${Month.FEBRUARY.getDisplayName(TextStyle.SHORT, Locale.US)}")
    println(s"${Month.JANUARY.minus(2)} ${Month.FEBRUARY.length(true)} ${Month.FEBRUARY.maxLength} ${Month.of(9).firstMonthOfQuarter} ${Month.MARCH.firstDayOfYear(true)}")
    println(s"${Year.of(2024).isLeap} ${Year.isLeap(2100)} ${Year.of(2024).atDay(60)} ${Year.of(2023).length}")
    val ym = YearMonth.of(2024, 2)
    println(s"$ym ${ym.lengthOfMonth} ${ym.atEndOfMonth} ${ym.plusMonths(11)} ${YearMonth.parse("2023-12").plusMonths(1)} ${MonthDay.of(2, 29).isValidYear(2023)} ${MonthDay.of(12, 25)}")
  }

  def times(): Unit = {
    println("-- LocalTime, LocalDateTime")
    val t = LocalTime.of(23, 59, 30, 123456789)
    println(s"$t ${t.plusSeconds(45)} ${t.plusHours(2)} ${t.truncatedTo(ChronoUnit.MINUTES)} ${t.withNano(0)} ${t.toSecondOfDay} ${t.toNanoOfDay}")
    println(s"${LocalTime.of(10, 0)} ${LocalTime.of(10, 0, 5)} ${LocalTime.of(10, 0, 0, 1000)} ${LocalTime.of(10, 0, 0, 1000000)} ${LocalTime.MIDNIGHT} ${LocalTime.NOON} ${LocalTime.MAX}")
    println(s"${ChronoUnit.MINUTES.between(LocalTime.of(9, 15), t)} ${LocalTime.parse("07:30").isBefore(t)} ${LocalTime.ofSecondOfDay(3661)}")
    val dt = LocalDateTime.of(2024, 12, 31, 23, 30)
    println(s"$dt ${dt.plusMinutes(45)} ${dt.plusNanos(1)} ${dt.toLocalDate} ${dt.toLocalTime} ${dt.getDayOfWeek} ${dt.truncatedTo(ChronoUnit.HOURS)}")
    println(s"${dt.toEpochSecond(ZoneOffset.UTC)} ${LocalDateTime.ofEpochSecond(1700000000L, 5, ZoneOffset.ofHours(2))} ${dt.atOffset(ZoneOffset.ofHoursMinutes(5, 30))}")
    println(s"${Duration.between(dt, dt.plusDays(2).plusSeconds(7))} ${ChronoUnit.HOURS.between(dt, LocalDateTime.of(2025, 1, 2, 0, 0))}")
    println(s"${LocalDate.of(2024, 3, 1).atTime(8, 15)} ${LocalDate.of(2024, 3, 1).atStartOfDay}")
  }

  def instantsAndDurations(): Unit = {
    println("-- Instant, Duration, Clock")
    val i = Instant.ofEpochSecond(1700000000L, 123000000)
    println(s"$i ${i.toEpochMilli} ${i.plusMillis(877)} ${i.minus(Duration.ofDays(1))} ${i.plusNanos(1)} ${Instant.ofEpochMilli(-1)}")
    println(s"${Instant.EPOCH} ${Instant.ofEpochSecond(0, 100)} ${Instant.parse("2024-02-29T12:00:00Z")} ${Instant.parse("2024-02-29T12:00:00.5Z").getNano}")
    println(s"${Instant.MIN} ${Instant.MAX} ${i.truncatedTo(ChronoUnit.SECONDS)} ${i.isAfter(Instant.EPOCH)} ${i.until(i.plusSeconds(90), ChronoUnit.MINUTES)}")
    val du = Duration.ofSeconds(3725, 500000000)
    println(s"$du ${du.toMinutes} ${du.toMillis} ${du.getSeconds} ${du.getNano} ${du.multipliedBy(3)} ${du.dividedBy(4)} ${du.negated} ${du.abs}")
    println(s"${Duration.ZERO} ${Duration.ofMillis(-1500)} ${Duration.ofDays(2)} ${Duration.ofHours(25).toDays} ${Duration.parse("PT1H30M").getSeconds} ${Duration.parse("-PT0.5S")}")
    println(s"${Duration.ofMinutes(90).compareTo(Duration.ofHours(1))} ${Duration.ofSeconds(-1, 1)} ${Duration.of(3, ChronoUnit.HALF_DAYS)} ${du.toHoursPart} ${du.toMinutesPart} ${du.toSecondsPart} ${du.toMillisPart}")
    println(s"${Duration.ofSeconds(100).dividedBy(Duration.ofSeconds(30))} ${du.truncatedTo(ChronoUnit.MINUTES)} ${du.toDaysPart} ${du.toNanosPart}")
    val fixed = Clock.fixed(i, ZoneId.of("Europe/Paris"))
    println(s"${Instant.now(fixed)} ${LocalDateTime.now(fixed)} ${LocalDate.now(fixed)} ${ZonedDateTime.now(fixed)} ${Clock.offset(fixed, Duration.ofHours(1)).instant}")
    val now = Instant.now()
    val later = Instant.now()
    println(s"${!later.isBefore(now)} ${now.isAfter(Instant.parse("2020-01-01T00:00:00Z"))} ${math.abs(System.currentTimeMillis - now.toEpochMilli) < 60000}")
  }

  def zones(): Unit = {
    println("-- zones")
    val ny = ZoneId.of("America/New_York")
    val paris = ZoneId.of("Europe/Paris")
    println(s"$ny ${ny.getRules.isFixedOffset} ${ZoneId.of("UTC")} ${ZoneId.of("Z")} ${ZoneId.of("+05:30")} ${ZoneId.of("GMT+2")} ${ZoneOffset.UTC} ${ZoneOffset.ofHours(-7)}")
    println(s"${ZoneId.systemDefault} ${ZoneId.of("PST", ZoneId.SHORT_IDS)} ${ZoneId.of("UTC").normalized} ${ZoneOffset.ofTotalSeconds(-12345)} ${ZoneOffset.of("+01:30").getTotalSeconds}")
    // DST gap: 2024-03-10 02:00 does not exist in New York; overlap: 2024-11-03 01:00 happens twice.
    val gap = LocalDateTime.of(2024, 3, 10, 2, 30)
    val overlap = LocalDateTime.of(2024, 11, 3, 1, 30)
    println(s"${ZonedDateTime.of(gap, ny)} ${ny.getRules.getValidOffsets(gap)} ${ny.getRules.getTransition(gap)}")
    val o = ZonedDateTime.of(overlap, ny)
    println(s"$o ${o.withLaterOffsetAtOverlap} ${o.withLaterOffsetAtOverlap.withEarlierOffsetAtOverlap} ${ny.getRules.getValidOffsets(overlap)} ${ny.getRules.getTransition(overlap)}")
    println(s"${ny.getRules.getTransition(overlap).isOverlap} ${ny.getRules.getTransition(gap).isGap} ${ny.getRules.getTransition(gap).getDuration} ${ny.getRules.getValidOffsets(LocalDateTime.of(2024, 7, 1, 12, 0))}")
    val before = ZonedDateTime.of(2024, 3, 10, 1, 59, 0, 0, ny)
    println(s"${before.plusMinutes(1)} ${before.plusHours(1)} ${before.plusDays(1)} ${before.plus(Duration.ofDays(1))} ${Duration.between(before, before.plusDays(1))}")
    val z = ZonedDateTime.of(2024, 7, 14, 12, 0, 0, 0, paris)
    println(s"$z ${z.withZoneSameInstant(ny)} ${z.withZoneSameLocal(ny)} ${z.toInstant} ${z.toOffsetDateTime} ${z.getOffset} ${z.toEpochSecond}")
    println(s"${z.withZoneSameInstant(ZoneId.of("Asia/Kolkata"))} ${z.withZoneSameInstant(ZoneId.of("Australia/Lord_Howe"))} ${z.withZoneSameInstant(ZoneOffset.UTC)}")
    val rules = ny.getRules
    val at = Instant.parse("2024-06-01T00:00:00Z")
    println(s"${rules.getOffset(at)} ${rules.getStandardOffset(at)} ${rules.isDaylightSavings(at)} ${rules.getDaylightSavings(at)} ${rules.nextTransition(at)} ${rules.previousTransition(at)}")
    println(s"${rules.isDaylightSavings(Instant.parse("2024-01-01T00:00:00Z"))} ${rules.nextTransition(Instant.parse("2024-11-03T06:00:00Z"))} ${rules.previousTransition(Instant.parse("2024-11-03T06:00:00Z"))}")
    println(s"${paris.getRules.getOffset(Instant.parse("1900-01-01T00:00:00Z"))} ${rules.getOffset(LocalDateTime.of(2024, 3, 10, 2, 30))} ${rules.isValidOffset(gap, ZoneOffset.ofHours(-5))}")
    println(s"${ZonedDateTime.ofInstant(Instant.EPOCH, ZoneId.of("Asia/Tokyo"))} ${OffsetDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.ofHours(9)).atZoneSameInstant(ZoneOffset.UTC)}")
    println(s"${ZonedDateTime.parse("2024-11-03T01:30-04:00[America/New_York]").plusHours(1)} ${ZonedDateTime.ofStrict(overlap, ZoneOffset.ofHours(-5), ny)}")
    println(s"${OffsetTime.of(10, 0, 0, 0, ZoneOffset.ofHours(2)).withOffsetSameInstant(ZoneOffset.UTC)} ${ZoneId.getAvailableZoneIds.contains("Europe/Paris")}")
    println(s"${ZonedDateTime.of(2024, 12, 31, 23, 0, 0, 0, ZoneId.systemDefault)} ${LocalDateTime.of(2024, 6, 1, 12, 0).atZone(ZoneId.systemDefault).toInstant}")
  }

  def formatting(): Unit = {
    println("-- formatting")
    val dt = LocalDateTime.of(2024, 3, 5, 7, 8, 9, 10000000)
    val z = dt.atZone(ZoneId.of("America/New_York"))
    for p <- List("yyyy-MM-dd HH:mm", "dd/MM/yy h:mm a", "EEE, d MMM yyyy", "EEEE MMMM dd", "uuuu-DDD", "HH:mm:ss.SSS", "yyyyMMdd'T'HHmmss",
        "'Q'Q yyyy, 'week' w", "MMMMM LLL", "G yyyy", "hh 'o''clock' a, K", "SSSSSS n")
    do println(s"$p => ${dt.format(DateTimeFormatter.ofPattern(p))}")
    for p <- List("yyyy-MM-dd'T'HH:mmXXX", "x xx xxx X", "Z ZZZZ ZZZZZ", "O OOOO", "VV", "z", "yyyy-MM-dd HH:mm z (VV)") do
      println(s"$p => ${z.format(DateTimeFormatter.ofPattern(p))}")
    println(z.withZoneSameInstant(ZoneId.of("Europe/Paris")).format(DateTimeFormatter.ofPattern("HH:mm z")))
    println(z.withZoneSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("HH:mm z")))
    for f <- List(DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ISO_DATE_TIME, DateTimeFormatter.ISO_OFFSET_DATE_TIME, DateTimeFormatter.ISO_ZONED_DATE_TIME,
        DateTimeFormatter.ISO_INSTANT, DateTimeFormatter.BASIC_ISO_DATE, DateTimeFormatter.ISO_WEEK_DATE, DateTimeFormatter.ISO_ORDINAL_DATE, DateTimeFormatter.RFC_1123_DATE_TIME)
    do println(f.format(z))
    println(DateTimeFormatter.ISO_LOCAL_TIME.format(dt))
    println(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(dt))
    println(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).format(dt))
    println(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).format(dt))
    println(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC).format(Instant.ofEpochSecond(1700000000L)))
  }

  def parsing(): Unit = {
    println("-- parsing")
    println(LocalDate.parse("2024-02-29"))
    println(LocalDateTime.parse("2024-02-29T10:15:30"))
    println(LocalDateTime.parse("2024-02-29 10:15", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
    println(LocalDate.parse("5 Mar 2024", DateTimeFormatter.ofPattern("d MMM yyyy")))
    println(LocalDate.parse("Tuesday, March 5, 2024", DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")))
    println(LocalTime.parse("7:08 PM", DateTimeFormatter.ofPattern("h:mm a")))
    println(OffsetDateTime.parse("2024-02-29T10:15:30+01:00"))
    println(ZonedDateTime.parse("2024-02-29T10:15:30+01:00[Europe/Paris]"))
    println(ZonedDateTime.parse("2024-07-01 12:00 Europe/Paris", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm VV")))
    println(Instant.parse("2024-02-29T10:15:30.123456Z"))
    println(LocalDate.parse("20240229", DateTimeFormatter.BASIC_ISO_DATE))
    println(Duration.parse("P2DT3H4M5.5S"))
    println(Period.parse("-P1Y2M"))
    println(DateTimeFormatter.ISO_LOCAL_DATE.parse("2024-02-29").get(ChronoField.DAY_OF_YEAR))
    println(YearMonth.parse("2024-02").atDay(29))
  }

  def legacy(): Unit = {
    println("-- java.util.Date, TimeZone")
    val date = new java.util.Date(1700000000123L)
    println(s"$date ${date.getTime} ${date.toInstant} ${java.util.Date.from(Instant.parse("2024-07-04T12:00:00Z"))} ${new java.util.Date(0)}")
    println(s"${date.before(new java.util.Date(1700000000124L))} ${date == new java.util.Date(1700000000123L)} ${date.compareTo(new java.util.Date(0))}")
    val tz = java.util.TimeZone.getDefault
    println(s"${tz.getID} ${tz.getRawOffset} ${tz.useDaylightTime} ${tz.getOffset(1720000000000L)} ${tz.inDaylightTime(date)} ${tz.toZoneId}")
    val paris = java.util.TimeZone.getTimeZone("Europe/Paris")
    println(s"${paris.getID} ${paris.getRawOffset} ${paris.getOffset(1720000000000L)} ${paris.getDisplayName(false, java.util.TimeZone.SHORT)} ${paris.getDisplayName(true, java.util.TimeZone.SHORT)}")
    println(s"${java.util.TimeZone.getTimeZone("Nowhere/Land").getID} ${java.util.TimeZone.getTimeZone("GMT+5").getID} ${java.util.TimeZone.getTimeZone(ZoneOffset.ofHours(-3)).getID}")
    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
    println(s"${ZoneId.systemDefault} ${new java.util.Date(0)}")
    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
    println(s"${ZoneId.systemDefault} ${new java.util.Date(0)}")
    java.util.TimeZone.setDefault(null)
    println(ZoneId.systemDefault)
  }

  def exceptions(): Unit = {
    println("-- exceptions")
    attempt("parse")(LocalDate.parse("2024-13-01"))
    attempt("parse2")(LocalDate.parse("2023-02-29"))
    attempt("parse3")(LocalDate.parse("not a date"))
    attempt("parse4")(LocalTime.parse("25:00"))
    attempt("parse5")(LocalDate.parse("2024-02-29", DateTimeFormatter.ofPattern("yyyy/MM/dd")))
    try LocalDate.parse("2024-02-3x")
    catch { case e: DateTimeParseException => println(s"${e.getErrorIndex} ${e.getParsedString}") }
    attempt("of")(LocalDate.of(2023, 2, 29))
    attempt("of2")(LocalTime.of(24, 0))
    attempt("zone")(ZoneId.of("Mars/Olympus_Mons"))
    attempt("zone2")(ZoneId.of("not a zone!"))
    attempt("offset")(ZoneOffset.ofHours(19))
    attempt("unsupported")(LocalDate.of(2024, 1, 1).plus(1, ChronoUnit.HOURS))
    attempt("field")(LocalTime.NOON.get(ChronoField.YEAR))
    attempt("pattern")(DateTimeFormatter.ofPattern("yyyy-MM-dd {"))
    attempt("format")(LocalDate.of(2024, 1, 1).format(DateTimeFormatter.ofPattern("HH:mm")))
    attempt("overflow")(Instant.MAX.plusSeconds(1))
    attempt("duration")(Duration.parse("1 hour"))
    attempt("arith")(LocalDate.MAX.plusDays(1))
    attempt("from")(LocalDate.from(LocalTime.NOON))
  }
}
