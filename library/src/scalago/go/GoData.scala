package scalago.go

import scala.collection.immutable.TreeMap

import scalago.go.internal.GoNatives

/** Conversions between Scala data and plain Go data, the values that Go code inspecting values by
 *  reflection works with: `encoding/json` (gin's `c.JSON`), templates, YAML, configuration
 *  libraries. Go code cannot see inside Scala objects, so a case class passed as an `any` would
 *  not be encoded as its fields; `GoData(todo)` is a Go `map[string]any` of its fields.
 *
 *  Both directions copy, deeply.
 */
object GoData:
  /** Plain Go data for a Scala value:
   *  - `String` and `Char`: Go `string`; `Int`, `Long`, `Short` and `Byte`: Go `int` (the type of
   *    Go's integer literals, which Go code expects); other numbers and `Boolean`: themselves;
   *  - `Option`: its value, or `nil`;
   *  - maps (`collection.Map`): `map[string]any`, keyed by the keys' `toString`;
   *  - other collections and arrays: `[]any`;
   *  - case classes and other products: `map[string]any` of their fields (by
   *    `productElementNames`), objects and enum cases without fields: their name;
   *  - `null`: `nil`; anything else (Go values, facades, `GoSlice`s) as it is.
   */
  def apply(value: Any): Any = value match
    case null                              => null
    case s: String                         => GoString(s)
    case c: Char                           => GoString(c.toString)
    case n: Int                            => GoNatives.intData(n)
    case n: Long                           => GoNatives.intData(n)
    case n: Short                          => GoNatives.intData(n)
    case n: Byte                           => GoNatives.intData(n)
    case _: (Boolean | Float | Double)     => value
    case o: Option[?]                      => o.map(apply).orNull
    case m: collection.Map[?, ?]           => map(m.iterator.map((k, v) => k.toString -> v).toSeq*)
    case xs: IterableOnce[?]               => slice(xs.iterator)
    case xs: Array[?]                      => slice(xs.iterator)
    case p: Product if p.productArity == 0 => GoString(p.toString)
    case p: Product                        => map(p.productElementNames.zip(p.productIterator).toSeq*)
    case other                             => other

  /** A Go `map[string]any` of the entries, their values converted by `apply`. */
  def map(entries: (String, Any)*): GoMap[GoString, Any] =
    val out = GoMap.make[GoString, Any]
    for (k, v) <- entries do out(GoString(k)) = apply(v)
    out

  private def slice(elems: Iterator[?]): GoSlice[Any] = GoSlice.fromSeq(elems.map(apply).toSeq)

  /** Scala data for plain Go data (the inverse of `apply`): Go strings become `String`s, integers
   *  `Long`s, floating-point numbers `Double`s, booleans `Boolean`s, maps `Map[String, Any]`
   *  sorted by key (keyed by the keys' string form; Go maps have no order), slices and arrays
   *  `Vector`s. Other values (`nil`, structs, Scala values) are returned as they are. Only Go's
   *  predeclared types are data: a named type such as `time.Duration` stays a Go value.
   */
  def toScala(value: Any): Any =
    if value == null then null
    else
      GoNatives.dataKind(value) match
        case GoNatives.BasicData => GoNatives.basicData(value)
        case GoNatives.MapData =>
          val entries = GoNatives.mapEntries(value.asInstanceOf[AnyRef])
          TreeMap.from(Iterator.range(0, entries.length / 2).map(i => toScala(entries(2 * i)).toString -> toScala(entries(2 * i + 1))))
        case GoNatives.SliceData =>
          val s = value.asInstanceOf[GoSlice[Any]]
          Vector.tabulate(s.length)(i => toScala(s(i)))
        case _ => value
