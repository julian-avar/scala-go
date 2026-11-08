package scalago.go.internal

import scala.collection.mutable

/** The primitive operations behind `GoSlice`, `GoMap`, `GoChan`, `GoPtr` and `GoType`. Under
 *  scala-go, the linker replaces every method with Go code (see `runtime/rt/gotypes.go`). The
 *  bodies here are the JVM implementation, with the same semantics, so that programs using these
 *  types run and can be tested on the JVM too.
 */
object GoNatives:
  // ---- Types ----------------------------------------------------------------------------------

  final class JvmGoType(val name: String, val elem: JvmGoType, val key: JvmGoType):
    def zero: Any = name match
      case "bool"                                          => false
      case "int" | "int64" | "uint" | "uint64" | "uintptr" => 0L
      case "int32" | "uint32"                              => 0
      case "int16"                                         => 0.toShort
      case "uint16"                                        => 0.toChar
      case "int8" | "uint8"                                => 0.toByte
      case "float32"                                       => 0f
      case "float64"                                       => 0.0
      case "string"                                        => scalago.go.GoString.empty
      case _                                               => null
    override def toString: String = name

  private def jvmType(t: AnyRef): JvmGoType = t.asInstanceOf[JvmGoType]

  def basicType(name: String): AnyRef = JvmGoType(name, null, null)
  def sliceType(elem: AnyRef): AnyRef = JvmGoType(s"[]$elem", jvmType(elem), null)
  def mapType(key: AnyRef, value: AnyRef): AnyRef = JvmGoType(s"map[$key]$value", jvmType(value), jvmType(key))
  def chanType(elem: AnyRef): AnyRef = JvmGoType(s"chan $elem", jvmType(elem), null)
  def ptrType(elem: AnyRef): AnyRef = JvmGoType(s"*$elem", jvmType(elem), null)

  def isNil(x: AnyRef): Boolean = x == null

  // ---- Slices: a segment of a shared array, as in Go ---------------------------------------------

  final class JvmSlice(val array: Array[Any], val offset: Int, val length: Int, val elem: JvmGoType):
    def capacity: Int = array.length - offset
    override def toString: String = (0 until length).map(i => array(offset + i)).mkString("[", " ", "]")

  private def slice(s: AnyRef): JvmSlice =
    if s == null then throw new NullPointerException()
    s.asInstanceOf[JvmSlice]

  private def checkIndex(i: Int, length: Int): Unit =
    if i < 0 || i >= length then throw new ArrayIndexOutOfBoundsException(s"Index $i out of bounds for length $length")

  def makeSlice(elemType: AnyRef, length: Int, capacity: Int): AnyRef =
    if length < 0 || capacity < length then throw new IllegalArgumentException(s"invalid slice length $length or capacity $capacity")
    val elem = jvmType(elemType)
    JvmSlice(Array.fill[Any](capacity)(elem.zero), 0, length, elem)

  def sliceLength(s: AnyRef): Int = slice(s).length
  def sliceCapacity(s: AnyRef): Int = slice(s).capacity

  def sliceGet(s: AnyRef, i: Int): Any =
    val sl = slice(s)
    checkIndex(i, sl.length)
    sl.array(sl.offset + i)

  def sliceSet(s: AnyRef, i: Int, value: Any): Unit =
    val sl = slice(s)
    checkIndex(i, sl.length)
    sl.array(sl.offset + i) = value

  def sliceSlice(s: AnyRef, from: Int, until: Int): AnyRef =
    val sl = slice(s)
    if from < 0 || until < from || until > sl.capacity then
      throw new IndexOutOfBoundsException(s"slice bounds [$from:$until] with capacity ${sl.capacity}")
    JvmSlice(sl.array, sl.offset + from, until - from, sl.elem)

  def sliceAppend(s: AnyRef, value: Any): AnyRef =
    val sl = slice(s)
    val grown = ensureCapacity(sl, sl.length + 1)
    grown.array(grown.offset + sl.length) = value
    JvmSlice(grown.array, grown.offset, sl.length + 1, sl.elem)

  def sliceAppendAll(s: AnyRef, other: AnyRef): AnyRef =
    val (sl, o) = (slice(s), slice(other))
    val items = Array.tabulate[Any](o.length)(i => o.array(o.offset + i))
    val grown = ensureCapacity(sl, sl.length + o.length)
    System.arraycopy(items, 0, grown.array, grown.offset + sl.length, items.length)
    JvmSlice(grown.array, grown.offset, sl.length + o.length, sl.elem)

  /** `sl` itself if it has room for `needed` elements, otherwise a copy in a larger array. */
  private def ensureCapacity(sl: JvmSlice, needed: Int): JvmSlice =
    if needed <= sl.capacity then sl
    else
      val array = Array.fill[Any](math.max(needed, 2 * sl.capacity))(sl.elem.zero)
      System.arraycopy(sl.array, sl.offset, array, 0, sl.length)
      JvmSlice(array, 0, sl.length, sl.elem)

  def sliceCopy(dst: AnyRef, src: AnyRef): Int =
    val (d, s) = (slice(dst), slice(src))
    val n = math.min(d.length, s.length)
    System.arraycopy(s.array, s.offset, d.array, d.offset, n) // overlapping ranges are handled
    n

  // ---- Maps -----------------------------------------------------------------------------------

  final class JvmMap(val key: JvmGoType, val value: JvmGoType):
    val entries = mutable.LinkedHashMap.empty[Any, Any]
    override def toString: String = entries.map((k, v) => s"$k:$v").mkString("map[", " ", "]")

  private def jvmMap(m: AnyRef): JvmMap =
    if m == null then throw new NullPointerException()
    m.asInstanceOf[JvmMap]

  def makeMap(keyType: AnyRef, valueType: AnyRef): AnyRef = JvmMap(jvmType(keyType), jvmType(valueType))
  def mapSize(m: AnyRef): Int = jvmMap(m).entries.size
  def mapLookup(m: AnyRef, key: Any, missing: AnyRef): Any = jvmMap(m).entries.getOrElse(key, missing)
  def mapStore(m: AnyRef, key: Any, value: Any): Unit = jvmMap(m).entries(key) = value
  def mapDelete(m: AnyRef, key: Any): Unit = jvmMap(m).entries.remove(key)
  def mapEntries(m: AnyRef): Array[AnyRef] =
    jvmMap(m).entries.toArray.flatMap((k, v) => Array(k.asInstanceOf[AnyRef], v.asInstanceOf[AnyRef]))

  // ---- Channels -------------------------------------------------------------------------------

  /** A Go channel on monitors. Unbuffered channels hand each value over: the sender waits until
   *  a receiver has taken it.
   */
  final class JvmChan(val elem: JvmGoType, val capacity: Int):
    private val buffer = mutable.Queue.empty[Any]
    private var closed = false
    private var sent = 0L
    private var received = 0L

    def length: Int = synchronized(if capacity == 0 then 0 else buffer.size)

    def send(value: Any): Unit = synchronized {
      while !closed && buffer.size >= math.max(capacity, 1) do wait()
      if closed then throw new IllegalStateException("send on closed channel")
      buffer.enqueue(value)
      sent += 1
      val ticket = sent
      notifyAll()
      if capacity == 0 then
        while received < ticket && !closed do wait()
    }

    def trySend(value: Any): Boolean = synchronized {
      if closed then throw new IllegalStateException("send on closed channel")
      if capacity > 0 && buffer.size < capacity then
        buffer.enqueue(value)
        sent += 1
        notifyAll()
        true
      else false
    }

    def receive(closedMarker: AnyRef): Any = synchronized {
      while buffer.isEmpty && !closed do wait()
      take(closedMarker)
    }

    def tryReceive(none: AnyRef, closedMarker: AnyRef): Any = synchronized {
      if buffer.isEmpty && !closed then none else take(closedMarker)
    }

    private def take(closedMarker: AnyRef): Any =
      if buffer.isEmpty then closedMarker
      else
        received += 1
        notifyAll()
        buffer.dequeue()

    def close(): Unit = synchronized {
      if closed then throw new IllegalStateException("close of closed channel")
      closed = true
      notifyAll()
    }

  private def chan(c: AnyRef): JvmChan =
    if c == null then throw new NullPointerException()
    c.asInstanceOf[JvmChan]

  def makeChan(elemType: AnyRef, capacity: Int): AnyRef =
    if capacity < 0 then throw new IllegalArgumentException("negative channel capacity")
    JvmChan(jvmType(elemType), capacity)
  def chanSend(c: AnyRef, value: Any): Unit = chan(c).send(value)
  def chanReceive(c: AnyRef, closed: AnyRef): Any = chan(c).receive(closed)
  def chanTrySend(c: AnyRef, value: Any): Boolean = chan(c).trySend(value)
  def chanTryReceive(c: AnyRef, none: AnyRef, closed: AnyRef): Any = chan(c).tryReceive(none, closed)
  def chanClose(c: AnyRef): Unit = chan(c).close()
  def chanLength(c: AnyRef): Int = chan(c).length
  def chanCapacity(c: AnyRef): Int = chan(c).capacity

  // ---- Pointers -------------------------------------------------------------------------------

  final class JvmPtr(var value: Any)

  def newPtr(elemType: AnyRef): AnyRef = JvmPtr(jvmType(elemType).zero)
  def ptrGet(p: AnyRef): Any =
    if p == null then throw new NullPointerException()
    p.asInstanceOf[JvmPtr].value
  def ptrSet(p: AnyRef, value: Any): Unit =
    if p == null then throw new NullPointerException()
    p.asInstanceOf[JvmPtr].value = value

  // ---- Plain data (scalago.go.GoData) -----------------------------------------------------------

  /** `dataKind` results: values of predeclared basic types, maps, slices and arrays. */
  final val BasicData = 1
  final val MapData = 2
  final val SliceData = 3

  /** What kind of Go data `x` is (`BasicData`, `MapData`, `SliceData`), or 0 for anything else. */
  def dataKind(x: Any): Int = x match
    case _: Array[Byte] | _: java.lang.Number | _: java.lang.Boolean | _: java.lang.Character => BasicData
    case _: JvmMap                                                                            => MapData
    case _: JvmSlice                                                                          => SliceData
    case _                                                                                    => 0

  /** `x` as a Go `int` (on the JVM, a `Long`). */
  def intData(x: Long): Any = x

  /** A value of a basic type as a `String`, `Long`, `Double` or `Boolean`. */
  def basicData(x: Any): Any = x match
    case bytes: Array[Byte]                       => new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
    case _: java.lang.Float | _: java.lang.Double => x.asInstanceOf[java.lang.Number].doubleValue
    case n: java.lang.Number                      => n.longValue
    case c: java.lang.Character                   => c.charValue.toLong
    case other                                    => other

  // ---- Go funcs (only created by Go facades, so never on the JVM) -------------------------------

  private def goOnly: Nothing = throw new UnsupportedOperationException("Go funcs only exist under scala-go")
  def call0(f: AnyRef): Any = goOnly
  def call1(f: AnyRef, a: Any): Any = goOnly
  def call2(f: AnyRef, a: Any, b: Any): Any = goOnly
  def call3(f: AnyRef, a: Any, b: Any, c: Any): Any = goOnly
