package scalago.go

import scalago.go.internal.GoNatives

/** The Go type that values of Scala type `T` are stored as in Go data structures, for creating
 *  them: `GoSlice.make[Int](n)` makes an `[]int32`. Instances exist for Scala's primitive types
 *  (`Int` is `int32`, `Long` is `int64`, ...), the Go numeric types (`GoInt`, `GoByte`, ...),
 *  `GoString` (`string`), `String` (Scala strings, as they are), `Any` (`any`), `GoError`, and
 *  slices, maps, channels and pointers of those.
 */
opaque type GoType[T] = AnyRef

object GoType:
  private def basic[T](name: String): GoType[T] = GoNatives.basicType(name)

  given GoType[Boolean] = basic("bool")
  given GoType[Byte] = basic("int8")
  given GoType[Short] = basic("int16")
  given GoType[Char] = basic("uint16")
  given GoType[Int] = basic("int32")
  given GoType[Long] = basic("int64")
  given GoType[Float] = basic("float32")
  given GoType[Double] = basic("float64")
  given GoType[GoInt] = basic("int")
  given GoType[GoUint] = basic("uint")
  given GoType[GoUint64] = basic("uint64")
  given GoType[GoUintptr] = basic("uintptr")
  given GoType[GoUint32] = basic("uint32")
  given GoType[GoByte] = basic("uint8")
  given GoType[GoString] = basic("string")
  given GoType[String] = basic("scala.String")
  given GoType[Any] = basic("any")
  given GoType[GoError] = basic("error")

  given slice[T](using elem: GoType[T]): GoType[GoSlice[T]] = GoNatives.sliceType(elem.underlying)
  given map[K, V](using key: GoType[K], value: GoType[V]): GoType[GoMap[K, V]] = GoNatives.mapType(key.underlying, value.underlying)
  given chan[T](using elem: GoType[T]): GoType[GoChan[T]] = GoNatives.chanType(elem.underlying)
  given ptr[T](using elem: GoType[T]): GoType[GoPtr[T]] = GoNatives.ptrType(elem.underlying)

  def of[T](using t: GoType[T]): GoType[T] = t

  extension [T](t: GoType[T])
    /** The runtime representation, for the natives. */
    private[go] def underlying: AnyRef = t
    def name: String = t.toString
