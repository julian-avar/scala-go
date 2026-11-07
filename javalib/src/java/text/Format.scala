package java.text

/** java.text.Format: the base of the classic formatting API. scala-go has no implementation of
 *  its own (no locale data); java.time's `DateTimeFormatter.toFormat` returns one.
 */
abstract class Format protected () extends Object with java.io.Serializable with Cloneable:
  final def format(obj: Object): String = format(obj, new java.lang.StringBuffer(), new FieldPosition(0)).toString

  def format(obj: Object, toAppendTo: java.lang.StringBuffer, pos: FieldPosition): java.lang.StringBuffer

  def parseObject(source: String, pos: ParsePosition): Object

  @throws[ParseException]
  def parseObject(source: String): Object =
    val pos = new ParsePosition(0)
    val result = parseObject(source, pos)
    if pos.getIndex() == 0 then throw new ParseException("Format.parseObject(String) failed", pos.getErrorIndex())
    result

  override def clone(): Object = super.clone()

/** java.text.FieldPosition, identifying fields by number only (no `Format.Field` attributes). */
class FieldPosition(field: Int):
  private var beginIndex = 0
  private var endIndex = 0

  def getField(): Int = field
  def getBeginIndex(): Int = beginIndex
  def getEndIndex(): Int = endIndex
  def setBeginIndex(bi: Int): Unit = beginIndex = bi
  def setEndIndex(ei: Int): Unit = endIndex = ei

  override def equals(obj: Any): Boolean = obj match
    case that: FieldPosition =>
      field == that.getField() && beginIndex == that.getBeginIndex() && endIndex == that.getEndIndex()
    case _ => false

  override def hashCode(): Int = (field << 24) | (beginIndex << 16) | endIndex

  override def toString(): String =
    s"${getClass.getName}[field=$field,attribute=null,beginIndex=$beginIndex,endIndex=$endIndex]"

/** java.text.ParsePosition: the position of a parse, and of its error. */
class ParsePosition(private var index: Int):
  private var errorIndex = -1

  def getIndex(): Int = index
  def setIndex(index: Int): Unit = this.index = index
  def getErrorIndex(): Int = errorIndex
  def setErrorIndex(errorIndex: Int): Unit = this.errorIndex = errorIndex

  override def equals(obj: Any): Boolean = obj match
    case that: ParsePosition => index == that.getIndex() && errorIndex == that.getErrorIndex()
    case _                   => false

  override def hashCode(): Int = (errorIndex << 16) | index

  override def toString(): String = s"${getClass.getName}[index=$index,errorIndex=$errorIndex]"

class ParseException(message: String, errorOffset: Int) extends java.lang.Exception(message):
  def getErrorOffset(): Int = errorOffset
