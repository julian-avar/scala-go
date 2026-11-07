// Adapted from scala-java-time 2.9.0 (BSD-3-Clause, see javalib/LICENSE-scala-java-time.md).
/*
 * Copyright (c) 2007-present, Stephen Colebourne & Michael Nascimento Santos
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  * Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *
 *  * Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 *  * Neither the name of JSR-310 nor the names of its contributors
 *    may be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package java.time.format

import java.util.Locale
import java.time.chrono.Chronology

private[format] object SimpleDateTimeFormatStyleProvider {

  /**
   * scala-go: there is no locale data, so every locale gets the patterns of the JDK's `en_US`
   * locale (CLDR 42 and later, with a narrow no-break space before the AM/PM marker).
   */
  def pattern(dateStyle: FormatStyle, timeStyle: FormatStyle): String = {
    if (dateStyle == null && timeStyle == null)
      throw new IllegalArgumentException("Either dateStyle or timeStyle must be non-null")
    val date = dateStyle match {
      case null              => null
      case FormatStyle.FULL  => "EEEE, MMMM d, y"
      case FormatStyle.LONG  => "MMMM d, y"
      case FormatStyle.MEDIUM => "MMM d, y"
      case FormatStyle.SHORT => "M/d/yy"
    }
    val time = timeStyle match {
      case null              => null
      case FormatStyle.FULL  => "h:mm:ss a zzzz"
      case FormatStyle.LONG  => "h:mm:ss a z"
      case FormatStyle.MEDIUM => "h:mm:ss a"
      case FormatStyle.SHORT => "h:mm a"
    }
    if (date == null) time else if (time == null) date else s"$date, $time"
  }
}

/**
 * The Service Provider Implementation to obtain date-time formatters for a style.
 *
 * <h3>Specification for implementors</h3> This class is immutable and thread-safe.
 */
final class SimpleDateTimeFormatStyleProvider extends DateTimeFormatStyleProvider {
  override def getAvailableLocales: Array[Locale] = Array(Locale.ROOT, Locale.US)

  def getFormatter(
    dateStyle: FormatStyle,
    timeStyle: FormatStyle,
    chrono:    Chronology,
    locale:    Locale
  ): DateTimeFormatter =
    new DateTimeFormatterBuilder()
      .appendPattern(SimpleDateTimeFormatStyleProvider.pattern(dateStyle, timeStyle))
      .toFormatter(locale)
}
