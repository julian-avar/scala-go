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
package java.time.format.internal

import java.time.temporal.ChronoField.AMPM_OF_DAY
import java.time.temporal.ChronoField.DAY_OF_WEEK
import java.time.temporal.ChronoField.ERA
import java.time.temporal.ChronoField.MONTH_OF_YEAR
import java.lang.{ Long => JLong }
import java.util.Locale
import java.util.{ Map => JMap }

import java.time.temporal.IsoFields
import java.time.temporal.TemporalField
import java.time.format.TextStyle

object TTBPSimpleDateTimeTextProvider {

  private def createLocaleStore(
    valueTextMap: Map[TextStyle, LocaleStore.Item]
  ): TTBPSimpleDateTimeTextProvider.LocaleStore = {
    val tmp1 =
      (valueTextMap + (TextStyle.FULL_STANDALONE -> valueTextMap
        .get(TextStyle.FULL)
        .orNull)) + (TextStyle.SHORT_STANDALONE  -> valueTextMap.get(TextStyle.SHORT).orNull)
    val tmp2 =
      if (
        valueTextMap.contains(TextStyle.NARROW) && !valueTextMap
          .contains(TextStyle.NARROW_STANDALONE)
      )
        tmp1 + (TextStyle.NARROW_STANDALONE -> valueTextMap.get(TextStyle.NARROW).orNull)
      else
        tmp1
    new TTBPSimpleDateTimeTextProvider.LocaleStore(tmp2)
  }

  /**
   * Stores the text for a single locale.
   *
   * Some fields have a textual representation, such as day-of-week or month-of-year. These textual
   * representations can be captured in this class for printing and parsing.
   *
   * This class is immutable and thread-safe.
   *
   * @constructor
   *
   * @param valueTextMap
   *   the map of values to text to store, assigned and not altered, not null
   */
  private[format] final class LocaleStore(
    private val valueTextMap: Map[TextStyle, LocaleStore.Item]
  ) {

    /** Parsable data. */
    private val parsable: (List[(String, Long)], Map[TextStyle, List[(String, Long)]]) = {
      val u = valueTextMap.foldLeft(
        (List.empty[(String, Long)], Map.empty[TextStyle, List[(String, Long)]])
      ) { case ((all, map), (style, entries)) =>
        val reverse =
          entries.sorted.foldLeft((true, Map.empty[String, (String, Long)])) {
            case (a @ (false, _), _)   => a
            case ((true, acc), (k, v)) =>
              val continue = !acc.contains(v)
              (continue, acc + (v -> (v -> k)))
          }
        val list    = reverse._2.values.toList.sortBy(x => -x._1.length)
        (all ::: list, map + (style -> list))
      }
      (u._1.sortBy(x => -x._1.length), u._2)
    }

    /**
     * Gets the text for the specified field value, locale and style for the purpose of printing.
     *
     * @param value
     *   the value to get text for, not null
     * @param style
     *   the style to get text for, not null
     * @return
     *   the text for the field value, null if no text found
     */
    def getText(value: Long, style: TextStyle): String =
      valueTextMap.get(style).flatMap(_.get(value)).orNull

    /**
     * Gets an iterator of text to field for the specified style for the purpose of parsing.
     *
     * The iterator must be returned in order from the longest text to the shortest.
     *
     * @param style
     *   the style to get text for, null for all parsable text
     * @return
     *   the iterator of text to field pairs, in order from longest text to shortest text, null if
     *   the style is not parsable
     */
    def getTextIterator(style: TextStyle): Iterator[(String, Long)] =
      parsable._2.getOrElse(style, parsable._1).iterator
  }

  private[format] object LocaleStore {
    sealed abstract class Item {
      def get(index: Long): Option[String]
      def sorted: List[(Long, String)]
    }

    final class MapItem(
      underlying: JMap[JLong, String]
    ) extends Item {
      def get(index: Long): Option[String] = Option(underlying.get(index))

      def sorted: List[(Long, String)] = {
        import scala.jdk.CollectionConverters._
        underlying
          .entrySet()
          .asScala
          .map(entry => (entry.getKey().toLong, entry.getValue()))
          .toList
          .sortBy(_._1)
      }
    }

    class ArrayItem(
      array:  Array[String],
      start:  Int,
      end:    Int,
      offset: Int
    ) extends Item {
      def get(index: Long): Option[String] = {
        val i = index - offset
        if (i >= start && i < end) Some(processString(array(i.toInt - start))) else None
      }

      def sorted: List[(Long, String)] = {
        var i   = end - 1
        var acc = List.empty[(Long, String)]
        while (i >= start) {
          val text = processString(array(i - start))
          acc = ((i + offset).toLong, text) :: acc
          i -= 1
        }
        acc
      }

      def processString(s: String): String = s
    }

    final class ArrayItemFirstChar(array: Array[String], start: Int, end: Int, offset: Int)
        extends ArrayItem(array, start, end, offset) {
      override def processString(s: String): String = s.substring(0, 1)
    }
  }

  /** scala-go: the texts of the JDK's English locale, for every locale. */
  private[internal] object EnglishStores {
    import LocaleStore.{ ArrayItem, ArrayItemFirstChar }

    private def store(full: Array[String], short: Array[String], offset: Int): LocaleStore =
      createLocaleStore(
        Map(
          TextStyle.FULL   -> new ArrayItem(full, 0, full.length, offset),
          TextStyle.NARROW -> new ArrayItemFirstChar(full, 0, full.length, offset),
          TextStyle.SHORT  -> new ArrayItem(short, 0, short.length, offset)
        )
      )

    lazy val months: LocaleStore = store(
      Array("January", "February", "March", "April", "May", "June", "July", "August",
            "September", "October", "November", "December"),
      Array("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"),
      offset = 1
    )

    lazy val weekdays: LocaleStore = store(
      Array("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"),
      Array("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"),
      offset = 1
    )

    lazy val amPm: LocaleStore = {
      val item = new ArrayItem(Array("AM", "PM"), 0, 2, 0)
      createLocaleStore(Map(TextStyle.FULL -> item, TextStyle.SHORT -> item))
    }

    lazy val eras: LocaleStore = {
      val short = Array("BC", "AD")
      createLocaleStore(
        Map(
          TextStyle.SHORT  -> new ArrayItem(short, 0, 2, 0),
          TextStyle.FULL   -> new ArrayItem(Array("Before Christ", "Anno Domini"), 0, 2, 0),
          TextStyle.NARROW -> new ArrayItemFirstChar(short, 0, 2, 0)
        )
      )
    }

    lazy val quarters: LocaleStore =
      createLocaleStore(
        Map(
          TextStyle.SHORT -> new ArrayItem(Array("Q1", "Q2", "Q3", "Q4"), 1, 5, 0),
          TextStyle.FULL  -> new ArrayItem(
            Array("1st quarter", "2nd quarter", "3rd quarter", "4th quarter"), 1, 5, 0)
        )
      )
  }
}

/**
 * The Service Provider Implementation to obtain date-time text for a field.
 *
 * scala-go: there is no locale data, so every locale gets the English texts of the JDK's `en`
 * locale (the stores are built once and shared, so they need no cache).
 *
 * <h3>Specification for implementors</h3> This class is immutable and thread-safe.
 */
final class TTBPSimpleDateTimeTextProvider extends TTBPDateTimeTextProvider {
  import TTBPSimpleDateTimeTextProvider.LocaleStore
  import TTBPSimpleDateTimeTextProvider.EnglishStores

  override def getText(
    field:  TemporalField,
    value:  Long,
    style:  TextStyle,
    locale: Locale
  ): String =
    findStore(field) match {
      case null  => null
      case store => store.getText(value, style)
    }

  override def getTextIterator(
    field:  TemporalField,
    style:  TextStyle,
    locale: Locale
  ): Iterator[(String, Long)] =
    findStore(field) match {
      case null  => null
      case store => store.getTextIterator(style)
    }

  private def findStore(field: TemporalField): LocaleStore =
    if (field eq MONTH_OF_YEAR) EnglishStores.months
    else if (field eq DAY_OF_WEEK) EnglishStores.weekdays
    else if (field eq AMPM_OF_DAY) EnglishStores.amPm
    else if (field eq ERA) EnglishStores.eras
    else if (field eq IsoFields.QUARTER_OF_YEAR) EnglishStores.quarters
    else null
}
