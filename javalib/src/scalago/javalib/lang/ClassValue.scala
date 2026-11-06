package scalago.javalib.lang

/** Lazily computed value per class, cached (single-threaded, linear lookup). */
abstract class ClassValue[T]:
  private var keys = new Array[Class[?]](0)
  private var values = new Array[AnyRef](0)

  protected def computeValue(tpe: Class[?]): T

  def get(tpe: Class[?]): T =
    var i = 0
    while i < keys.length do
      if keys(i) eq tpe then return values(i).asInstanceOf[T]
      i += 1
    val value = computeValue(tpe)
    val newKeys = new Array[Class[?]](keys.length + 1)
    val newValues = new Array[AnyRef](values.length + 1)
    java.lang.System.arraycopy(keys, 0, newKeys, 0, keys.length)
    java.lang.System.arraycopy(values, 0, newValues, 0, values.length)
    newKeys(keys.length) = tpe
    newValues(values.length) = value.asInstanceOf[AnyRef]
    keys = newKeys
    values = newValues
    value

  def remove(tpe: Class[?]): Unit =
    var i = 0
    while i < keys.length do
      if keys(i) eq tpe then
        keys(i) = null
        values(i) = null
      i += 1
