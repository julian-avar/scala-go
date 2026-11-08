package java.net.http

import java.io.IOException
import java.util.{ArrayList, Collections, List as JList, Map as JMap, Optional, OptionalLong, TreeMap}
import java.util.function.BiPredicate

/** Read-only header fields: names compare without case, and keep the order of their values. */
final class HttpHeaders private (headers: JMap[String, JList[String]]):
  def map(): JMap[String, JList[String]] = headers

  def firstValue(name: String): Optional[String] =
    val values = allValues(name)
    if values.isEmpty() then Optional.empty() else Optional.of(values.get(0))

  def firstValueAsLong(name: String): OptionalLong =
    val values = allValues(name)
    if values.isEmpty() then OptionalLong.empty() else OptionalLong.of(java.lang.Long.parseLong(values.get(0)))

  def allValues(name: String): JList[String] =
    java.util.Objects.requireNonNull(name)
    val values = headers.get(name)
    if values == null then Collections.emptyList() else values

  override def equals(obj: Any): Boolean = obj match
    case that: HttpHeaders => headers == that.map()
    case _                 => false

  override def hashCode(): Int =
    var h = 0
    headers.forEach((k, v) => h += k.toLowerCase(java.util.Locale.ROOT).hashCode ^ v.hashCode)
    h

  override def toString(): String = super.toString() + " { " + headers + " }"

object HttpHeaders:
  private val ignoringCase: java.util.Comparator[String] = (a, b) => a.compareToIgnoreCase(b)

  def of(headerMap: JMap[String, JList[String]], filter: BiPredicate[String, String]): HttpHeaders =
    java.util.Objects.requireNonNull(headerMap)
    java.util.Objects.requireNonNull(filter)
    val map = new TreeMap[String, JList[String]](ignoringCase)
    headerMap.forEach { (name, values) =>
      if name == null || values == null then throw new NullPointerException()
      val name1 = name.trim()
      if map.containsKey(name1) then throw new IllegalArgumentException("duplicate key: " + name1)
      val kept = new ArrayList[String]()
      values.forEach { v =>
        if v == null then throw new NullPointerException()
        if filter.test(name1, v) then kept.add(v.trim())
      }
      if !kept.isEmpty() then map.put(name1, Collections.unmodifiableList(kept))
    }
    new HttpHeaders(Collections.unmodifiableMap(map))

  /** Headers from name/value pairs, in order. */
  private[http] def ofPairs(pairs: java.lang.Iterable[Array[String]]): HttpHeaders =
    val map = new TreeMap[String, JList[String]](ignoringCase)
    pairs.forEach { p =>
      var values = map.get(p(0))
      if values == null then
        values = new ArrayList[String]()
        map.put(p(0), values)
      values.add(p(1))
    }
    map.replaceAll((_, v) => Collections.unmodifiableList(v))
    new HttpHeaders(Collections.unmodifiableMap(map))

class HttpTimeoutException(message: String) extends IOException(message)

class HttpConnectTimeoutException(message: String) extends HttpTimeoutException(message)
