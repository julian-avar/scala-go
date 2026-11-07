package java.nio

/** A read-only view of chars, as `CharBuffer.wrap` makes it: enough for using it as a
 *  `CharSequence` (libraries wrap char arrays to pass them where text is expected). NIO buffers
 *  (`get`, `put`, positions and limits) are not implemented.
 */
final class CharBuffer private (chars: CharSequence, start: Int, end: Int) extends Object with CharSequence
    with Comparable[CharBuffer] with Appendable:
  def length(): Int = end - start
  def remaining(): Int = length()
  def hasRemaining(): Boolean = length() > 0
  def charAt(index: Int): Char =
    if index < 0 || index >= length() then throw new IndexOutOfBoundsException(s"index $index, length ${length()}")
    chars.charAt(start + index)
  def subSequence(from: Int, to: Int): CharBuffer =
    if from < 0 || to > length() || from > to then throw new IndexOutOfBoundsException()
    new CharBuffer(chars, start + from, start + to)
  override def toString(): String = chars.subSequence(start, end).toString

  def compareTo(that: CharBuffer): Int = toString().compareTo(that.toString())
  override def equals(that: Any): Boolean = that match
    case b: CharBuffer => toString() == b.toString()
    case _             => false
  override def hashCode(): Int = toString().hashCode

  def append(csq: CharSequence): CharBuffer = throw new ReadOnlyBufferException()
  def append(csq: CharSequence, from: Int, to: Int): CharBuffer = throw new ReadOnlyBufferException()
  def append(c: Char): CharBuffer = throw new ReadOnlyBufferException()
  def isReadOnly(): Boolean = true

object CharBuffer:
  def wrap(array: Array[Char]): CharBuffer = new CharBuffer(new String(array), 0, array.length)
  def wrap(array: Array[Char], offset: Int, length: Int): CharBuffer =
    if offset < 0 || length < 0 || offset > array.length - length then throw new IndexOutOfBoundsException()
    new CharBuffer(new String(array, offset, length), 0, length)
  def wrap(csq: CharSequence): CharBuffer = new CharBuffer(csq, 0, csq.length())
  def wrap(csq: CharSequence, start: Int, end: Int): CharBuffer =
    if start < 0 || end > csq.length() || start > end then throw new IndexOutOfBoundsException()
    new CharBuffer(csq, start, end)

class ReadOnlyBufferException extends UnsupportedOperationException
