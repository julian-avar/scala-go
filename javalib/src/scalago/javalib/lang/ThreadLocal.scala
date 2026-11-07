package scalago.javalib.lang

/** java.lang.ThreadLocal: as in the JDK, each Thread holds a table of its values. Tables hold
 *  their ThreadLocals strongly (the JDK's are weak references), so a value lives as long as its
 *  thread or until `remove`.
 */
class ThreadLocal[T] extends Object:
  private[lang] val hash: Int = ThreadLocal.nextHash()

  protected def initialValue(): T = null.asInstanceOf[T]

  def get(): T =
    val values = valuesOf(Thread.currentThread(), create = true)
    val i = values.indexOf(this)
    if i >= 0 then values.valueAt(i).asInstanceOf[T]
    else
      val value = initialValue()
      values.put(this, value.asInstanceOf[Object])
      value

  def set(value: T): Unit = valuesOf(Thread.currentThread(), create = true).put(this, value.asInstanceOf[Object])

  def remove(): Unit =
    val values = valuesOf(Thread.currentThread(), create = false)
    if values != null then values.remove(this)

  /** The table holding this ThreadLocal's value in thread `t`. */
  private[lang] def valuesOf(t: Thread, create: scala.Boolean): ThreadLocal.Values =
    if t.threadLocals == null && create then t.threadLocals = new ThreadLocal.Values()
    t.threadLocals

object ThreadLocal:
  @scala.annotation.static def withInitial[S](supplier: java.util.function.Supplier[? <: S]): ThreadLocal[S] =
    if supplier == null then throw new java.lang.NullPointerException()
    new ThreadLocal[S]:
      override protected def initialValue(): S = supplier.get()

  private val HashIncrement = 0x61c88647 // as in the JDK: spreads consecutive hashes well
  private var lastHash = 0

  private def nextHash(): Int = synchronized {
    lastHash += HashIncrement
    lastHash
  }

  /** The inheritable values a new thread copies from its creator. */
  private[lang] def inheritedValues(parent: Thread): Values =
    if parent.inheritableThreadLocals == null then null else parent.inheritableThreadLocals.inherited()

  /** A table of ThreadLocal values: open addressing with linear probing. */
  private[lang] final class Values:
    private var keys = new Array[ThreadLocal[?]](16)
    private var values = new Array[Object](16)
    private var size = 0

    def indexOf(key: ThreadLocal[?]): Int =
      val mask = keys.length - 1
      var i = key.hash & mask
      while keys(i) != null do
        if keys(i) eq key then return i
        i = (i + 1) & mask
      -1

    def valueAt(i: Int): Object = values(i)

    def put(key: ThreadLocal[?], value: Object): Unit =
      val existing = indexOf(key)
      if existing >= 0 then values(existing) = value
      else
        if (size + 1) * 3 > keys.length * 2 then grow()
        insert(key, value)
        size += 1

    private def insert(key: ThreadLocal[?], value: Object): Unit =
      val mask = keys.length - 1
      var i = key.hash & mask
      while keys(i) != null do i = (i + 1) & mask
      keys(i) = key
      values(i) = value

    private def grow(): Unit =
      val oldKeys = keys
      val oldValues = values
      keys = new Array[ThreadLocal[?]](oldKeys.length * 2)
      values = new Array[Object](oldKeys.length * 2)
      var i = 0
      while i < oldKeys.length do
        if oldKeys(i) != null then insert(oldKeys(i), oldValues(i))
        i += 1

    /** Removes a key, shifting back the entries of its probe sequence. */
    def remove(key: ThreadLocal[?]): Unit =
      var i = indexOf(key)
      if i >= 0 then
        val mask = keys.length - 1
        keys(i) = null
        values(i) = null
        size -= 1
        var j = (i + 1) & mask
        while keys(j) != null do
          val home = keys(j).hash & mask
          // Move entry j into the hole at i unless its home lies cyclically in (i, j].
          val stays = if i <= j then i < home && home <= j else i < home || home <= j
          if !stays then
            keys(i) = keys(j)
            values(i) = values(j)
            keys(j) = null
            values(j) = null
            i = j
          j = (j + 1) & mask

    /** A copy for a child thread, with each value passed through `childValue`. */
    def inherited(): Values =
      val copy = new Values()
      var i = 0
      while i < keys.length do
        keys(i) match
          case key: InheritableThreadLocal[?] => copy.put(key, key.inheritedValue(values(i)))
          case _                              => ()
        i += 1
      copy

class InheritableThreadLocal[T] extends ThreadLocal[T]:
  protected def childValue(parentValue: T): T = parentValue

  private[lang] def inheritedValue(parentValue: Object): Object = childValue(parentValue.asInstanceOf[T]).asInstanceOf[Object]

  override private[lang] def valuesOf(t: Thread, create: scala.Boolean): ThreadLocal.Values =
    if t.inheritableThreadLocals == null && create then t.inheritableThreadLocals = new ThreadLocal.Values()
    t.inheritableThreadLocals
