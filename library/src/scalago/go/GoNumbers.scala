package scalago.go

// Go numeric types that have no Scala counterpart. Their values are Scala numbers (the type
// they are opaque over), so they only matter as type arguments: `GoSlice.make[GoInt](n)` makes
// a Go `[]int`, while `GoSlice.make[Long](n)` makes an `[]int64`. Reading either gives Longs.

/** Go `int` (64 bits on the platforms scala-go supports).
 *
 *  Go's numeric types without a Scala counterpart are opaque over Scala numbers, so they only
 *  matter as type arguments and in facades: `GoSlice.make[GoInt](n)` makes an `[]int`, while
 *  `GoSlice.make[Long](n)` makes an `[]int64`. Reading either gives `Long`s.
 *  {{{
 *  val n: GoInt = GoInt(42)
 *  n.toLong + 1   // 43
 *  }}}
 */
opaque type GoInt = Long
object GoInt:
  def apply(x: Long): GoInt = x
  extension (x: GoInt) def toLong: Long = x

/** Go `uint`: its bits as a Long. */
opaque type GoUint = Long
object GoUint:
  def apply(x: Long): GoUint = x
  extension (x: GoUint) def toLong: Long = x

/** Go `uint64`: its bits as a Long. */
opaque type GoUint64 = Long
object GoUint64:
  def apply(x: Long): GoUint64 = x
  extension (x: GoUint64) def toLong: Long = x

/** Go `uintptr`: its bits as a Long. */
opaque type GoUintptr = Long
object GoUintptr:
  def apply(x: Long): GoUintptr = x
  extension (x: GoUintptr) def toLong: Long = x

/** Go `uint32`: its bits as an Int. */
opaque type GoUint32 = Int
object GoUint32:
  def apply(x: Int): GoUint32 = x
  extension (x: GoUint32) def toInt: Int = x

/** Go `byte` (`uint8`): its bits as a Byte. Go `[]byte` is `GoSlice[GoByte]`. */
opaque type GoByte = Byte
object GoByte:
  def apply(x: Byte): GoByte = x
  extension (x: GoByte)
    def toByte: Byte = x
    /** The unsigned value, 0 to 255. */
    def toInt: Int = x & 0xff
