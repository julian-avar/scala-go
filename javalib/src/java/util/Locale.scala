package java.util

import scala.annotation.static

/** A language, country and variant. scala-go has no locale data: formatting and case mapping
 *  behave as for `Locale.ROOT` whatever the locale (see docs/design.md), so a Locale only
 *  carries its identifiers, with the JDK's normalization and string forms.
 */
final class Locale(language: String, country: String, variant: String) extends Object with java.io.Serializable
    with Cloneable:
  def this(language: String, country: String) = this(language, country, "")
  def this(language: String) = this(language, "", "")

  if language == null || country == null || variant == null then throw new NullPointerException()

  private val lang = Locale.toLowerAscii(language)
  private val ctry = Locale.toUpperAscii(country)

  def getLanguage(): String = lang
  def getCountry(): String = ctry
  def getVariant(): String = variant
  def getScript(): String = ""

  /** `en_US`, `en`, `_US`, `en_US_POSIX` (empty for ROOT), as in the JDK. */
  override def toString(): String =
    if lang.isEmpty && ctry.isEmpty then ""
    else
      val base = if ctry.isEmpty && variant.isEmpty then lang else lang + "_" + ctry
      if variant.isEmpty then base else base + "_" + variant

  /** The BCP 47 tag: `en-US`, and `und` for an empty language. */
  def toLanguageTag(): String =
    val sb = new java.lang.StringBuilder(if lang.isEmpty then "und" else lang)
    if ctry.nonEmpty then sb.append('-').append(ctry)
    if variant.nonEmpty then sb.append('-').append(variant)
    sb.toString()

  override def equals(that: Any): Boolean = that match
    case l: Locale => lang == l.getLanguage() && ctry == l.getCountry() && variant == l.getVariant()
    case _         => false

  override def hashCode(): Int = (lang.hashCode * 31 + ctry.hashCode) * 31 + variant.hashCode

  override def clone(): Object = this

object Locale:
  @static final val ROOT: Locale = new Locale("", "", "")
  @static final val ENGLISH: Locale = new Locale("en", "", "")
  @static final val US: Locale = new Locale("en", "US", "")
  @static final val UK: Locale = new Locale("en", "GB", "")
  @static final val CANADA: Locale = new Locale("en", "CA", "")
  @static final val FRENCH: Locale = new Locale("fr", "", "")
  @static final val FRANCE: Locale = new Locale("fr", "FR", "")
  @static final val GERMAN: Locale = new Locale("de", "", "")
  @static final val GERMANY: Locale = new Locale("de", "DE", "")
  @static final val ITALIAN: Locale = new Locale("it", "", "")
  @static final val ITALY: Locale = new Locale("it", "IT", "")
  @static final val JAPANESE: Locale = new Locale("ja", "", "")
  @static final val JAPAN: Locale = new Locale("ja", "JP", "")
  @static final val CHINESE: Locale = new Locale("zh", "", "")

  @static private var default: Locale = US

  /** The default locale: `en_US`. (scala-go has no locale data; see the class comment.) */
  @static def getDefault(): Locale = default
  @static def setDefault(locale: Locale): Unit =
    if locale == null then throw new NullPointerException()
    default = locale

  @static def of(language: String): Locale = new Locale(language)
  @static def of(language: String, country: String): Locale = new Locale(language, country)
  @static def of(language: String, country: String, variant: String): Locale = new Locale(language, country, variant)

  /** Parses the language, region and variant of a BCP 47 tag (`en-US`); `und` is ROOT. */
  @static def forLanguageTag(languageTag: String): Locale =
    val parts = languageTag.replace('_', '-').split("-")
    val language = if parts(0).equalsIgnoreCase("und") then "" else parts(0)
    val country = if parts.length > 1 && (parts(1).length == 2 || parts(1).length == 3) then parts(1) else ""
    val variant = if parts.length > 2 then parts(2) else ""
    new Locale(language, country, variant)

  @static def getAvailableLocales(): Array[Locale] = Array(ROOT, ENGLISH, US, UK)

  private def toLowerAscii(s: String): String =
    val chars = s.toCharArray
    var i = 0
    while i < chars.length do
      if chars(i) >= 'A' && chars(i) <= 'Z' then chars(i) = (chars(i) + 32).toChar
      i += 1
    new String(chars)

  private def toUpperAscii(s: String): String =
    val chars = s.toCharArray
    var i = 0
    while i < chars.length do
      if chars(i) >= 'a' && chars(i) <= 'z' then chars(i) = (chars(i) - 32).toChar
      i += 1
    new String(chars)
