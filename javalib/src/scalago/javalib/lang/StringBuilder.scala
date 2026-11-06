package scalago.javalib.lang

/** A mutable sequence of UTF-16 code units, backed by a growable char array. */
final class StringBuilder private (private var chars: Array[Char], private var count: Int)
    extends Object
    with CharSequence
    with Appendable
    with Comparable[StringBuilder]
    with scalago.javalib.io.Serializable:

  def this() = this(new Array[Char](16), 0)
  def this(capacity: Int) = this(new Array[Char](capacity), 0)
  def this(str: String) =
    this(new Array[Char](str.length() + 16), 0)
    append(str)
  def this(seq: CharSequence) =
    this(new Array[Char](seq.length() + 16), 0)
    append(seq)

  def length(): Int = count
  def capacity(): Int = chars.length

  def charAt(index: Int): Char =
    if index < 0 || index >= count then throw new java.lang.StringIndexOutOfBoundsException("index " + index + ",length " + count)
    chars(index)

  def setCharAt(index: Int, ch: Char): Unit =
    if index < 0 || index >= count then throw new java.lang.StringIndexOutOfBoundsException("index " + index + ",length " + count)
    chars(index) = ch

  def ensureCapacity(minimumCapacity: Int): Unit =
    if minimumCapacity > chars.length then
      val grown = new Array[Char](scala.math.max(minimumCapacity, chars.length * 2 + 2))
      java.lang.System.arraycopy(chars, 0, grown, 0, count)
      chars = grown

  def trimToSize(): Unit = ()

  def setLength(newLength: Int): Unit =
    if newLength < 0 then throw new java.lang.StringIndexOutOfBoundsException(newLength)
    ensureCapacity(newLength)
    var i = count
    while i < newLength do
      chars(i) = '\u0000'
      i += 1
    count = newLength

  private def appendChars(s: String): StringBuilder =
    val n = s.length()
    ensureCapacity(count + n)
    var i = 0
    while i < n do
      chars(count + i) = s.charAt(i)
      i += 1
    count += n
    this

  def append(s: String): StringBuilder = appendChars(if s == null then "null" else s)
  def append(obj: Object): StringBuilder = appendChars(String.valueOf(obj))
  def append(sb: StringBuffer): StringBuilder = appendChars(String.valueOf(sb))
  def append(s: CharSequence): StringBuilder = appendChars(String.valueOf(s))
  def append(s: CharSequence, start: Int, end: Int): StringBuilder =
    if s == null then appendChars("null".substring(start, end))
    else
      var i = start
      while i < end do
        append(s.charAt(i))
        i += 1
      this
  def append(str: Array[Char]): StringBuilder = append(str, 0, str.length)
  def append(str: Array[Char], offset: Int, len: Int): StringBuilder =
    ensureCapacity(count + len)
    java.lang.System.arraycopy(str, offset, chars, count, len)
    count += len
    this
  def append(b: scala.Boolean): StringBuilder = appendChars(String.valueOf(b))
  def append(c: Char): StringBuilder =
    ensureCapacity(count + 1)
    chars(count) = c
    count += 1
    this
  def append(i: Int): StringBuilder = appendChars(String.valueOf(i))
  def append(l: scala.Long): StringBuilder = appendChars(String.valueOf(l))
  def append(f: scala.Float): StringBuilder = appendChars(String.valueOf(f))
  def append(d: scala.Double): StringBuilder = appendChars(String.valueOf(d))
  def appendCodePoint(codePoint: Int): StringBuilder =
    if codePoint < 0x10000 then append(codePoint.toChar)
    else
      val c = codePoint - 0x10000
      append((0xd800 + (c >> 10)).toChar)
      append((0xdc00 + (c & 0x3ff)).toChar)

  def insert(offset: Int, str: String): StringBuilder =
    if offset < 0 || offset > count then throw new java.lang.StringIndexOutOfBoundsException("offset " + offset + ", length " + count)
    val s = if str == null then "null" else str
    val n = s.length()
    ensureCapacity(count + n)
    java.lang.System.arraycopy(chars, offset, chars, offset + n, count - offset)
    var i = 0
    while i < n do
      chars(offset + i) = s.charAt(i)
      i += 1
    count += n
    this
  def insert(offset: Int, obj: Object): StringBuilder = insert(offset, String.valueOf(obj))
  def insert(offset: Int, c: Char): StringBuilder = insert(offset, String.valueOf(c))
  def insert(offset: Int, i: Int): StringBuilder = insert(offset, String.valueOf(i))
  def insert(offset: Int, l: scala.Long): StringBuilder = insert(offset, String.valueOf(l))
  def insert(offset: Int, b: scala.Boolean): StringBuilder = insert(offset, String.valueOf(b))
  def insert(offset: Int, d: scala.Double): StringBuilder = insert(offset, String.valueOf(d))
  def insert(offset: Int, s: CharSequence): StringBuilder = insert(offset, String.valueOf(s))

  def delete(start: Int, end: Int): StringBuilder =
    val e = scala.math.min(end, count)
    if start < 0 || start > e then throw new java.lang.StringIndexOutOfBoundsException("start " + start + ", end " + end + ", length " + count)
    java.lang.System.arraycopy(chars, e, chars, start, count - e)
    count -= e - start
    this

  def deleteCharAt(index: Int): StringBuilder =
    if index < 0 || index >= count then throw new java.lang.StringIndexOutOfBoundsException("index " + index + ",length " + count)
    delete(index, index + 1)

  def replace(start: Int, end: Int, str: String): StringBuilder =
    delete(start, scala.math.min(end, count))
    insert(start, str)

  def reverse(): StringBuilder =
    var i = 0
    var j = count - 1
    while i < j do
      val c = chars(i)
      chars(i) = chars(j)
      chars(j) = c
      i += 1
      j -= 1
    // Surrogate pairs keep their order, as in the JDK.
    var k = 0
    while k < count - 1 do
      if Character.isLowSurrogate(chars(k)) && Character.isHighSurrogate(chars(k + 1)) then
        val c = chars(k)
        chars(k) = chars(k + 1)
        chars(k + 1) = c
        k += 2
      else k += 1
    this

  override def toString(): String = new String(chars, 0, count)

  def substring(start: Int): String = substring(start, count)
  def substring(start: Int, end: Int): String =
    if start < 0 || end > count || start > end then
      throw new java.lang.StringIndexOutOfBoundsException("start " + start + ", end " + end + ", length " + count)
    new String(chars, start, end - start)
  // A java.lang.String is a CharSequence at run time (the compiler only sees the JDK's String here).
  def subSequence(start: Int, end: Int): CharSequence = substring(start, end).asInstanceOf[CharSequence]

  def indexOf(str: String): Int = toString().indexOf(str)
  def indexOf(str: String, fromIndex: Int): Int = toString().indexOf(str, fromIndex)
  def lastIndexOf(str: String): Int = toString().lastIndexOf(str)
  def lastIndexOf(str: String, fromIndex: Int): Int = toString().lastIndexOf(str, fromIndex)

  def getChars(srcBegin: Int, srcEnd: Int, dst: Array[Char], dstBegin: Int): Unit =
    if srcBegin < 0 || srcEnd > count || srcBegin > srcEnd then
      throw new java.lang.StringIndexOutOfBoundsException("start " + srcBegin + ", end " + srcEnd + ", length " + count)
    java.lang.System.arraycopy(chars, srcBegin, dst, dstBegin, srcEnd - srcBegin)

  def compareTo(another: StringBuilder): Int = toString().compareTo(another.toString())

/** StringBuffer is StringBuilder without the (here meaningless) synchronization. */
final class StringBuffer(private val builder: StringBuilder) extends Object with CharSequence with java.io.Serializable:
  def this() = this(new StringBuilder())
  def this(str: String) = this(new StringBuilder(str))
  def length(): Int = builder.length()
  def charAt(index: Int): Char = builder.charAt(index)
  def subSequence(start: Int, end: Int): CharSequence = builder.subSequence(start, end)
  def append(s: String): StringBuffer = { builder.append(s); this }
  def append(s: CharSequence): StringBuffer = { builder.append(s); this }
  def append(s: CharSequence, start: Int, end: Int): StringBuffer = { builder.append(s, start, end); this }
  def append(obj: Object): StringBuffer = { builder.append(obj); this }
  def append(c: Char): StringBuffer = { builder.append(c); this }
  def append(i: Int): StringBuffer = { builder.append(i); this }
  override def toString(): String = builder.toString()
