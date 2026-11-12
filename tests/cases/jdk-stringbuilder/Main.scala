import java.lang.{StringBuilder as JStringBuilder}

// java.lang.StringBuilder and StringBuffer: appending each type, inserting, deleting, replacing,
// reversing (surrogate pairs stay in order), length and capacity, searching, and the exceptions
// of out-of-range indices.
object Main:
  def attempt(label: String)(body: => Any): Unit =
    val r =
      try String.valueOf(body)
      catch case e: Exception => s"${e.getClass.getName}: ${e.getMessage}"
    println(s"$label: $r")

  /** Only the exception's class: the messages of index errors differ (see known-bugs-jdk). */
  def attemptType(label: String)(body: => Any): Unit =
    val r =
      try String.valueOf(body)
      catch case e: Exception => e.getClass.getName
    println(s"$label: $r")

  def main(args: Array[String]): Unit =
    appending()
    editing()
    reversing()
    lengths()
    searching()
    buffers()
    errors()

  def appending(): Unit =
    println("== append")
    val sb = new JStringBuilder()
    sb.append("s").append('c').append(1).append(2L).append(1.5f).append(2.25).append(true).append(null: String)
    sb.append(Array('x', 'y')).append(Array('a', 'b', 'c', 'd'), 1, 2).append("0123456789": CharSequence, 3, 6)
    sb.append(new JStringBuilder("[sb]")).append(List(1, 2): Object).append(null: Object).append(new StringBuffer("{buf}"))
    println(sb)
    println(new JStringBuilder().append(Float.NaN).append(' ').append(Double.NegativeInfinity).append(' ').append(-0.0).append(' ').append(1e10f).append(' ').append(Long.MinValue))
    val cp = new JStringBuilder().appendCodePoint(0x41).appendCodePoint(0x1F600).appendCodePoint(0xe9)
    println(s"$cp ${cp.length}")
    println(s"${new JStringBuilder("init")} ${new JStringBuilder("cs": CharSequence)} [${new JStringBuilder(100)}] ${new JStringBuilder(100).capacity}")

  def editing(): Unit =
    println("== insert, delete, replace")
    val sb = new JStringBuilder("0123456789")
    sb.insert(0, "a").insert(3, 'b').insert(sb.length, 42).insert(1, 7L).insert(2, true).insert(0, 1.5).insert(0, null: String)
    println(sb)
    sb.insert(4, List("x"): Object).insert(4, "<cs>": CharSequence)
    println(sb)
    sb.delete(0, 4)
    println(sb)
    sb.delete(5, 1000)
    println(sb)
    sb.deleteCharAt(0)
    println(sb)
    sb.replace(1, 3, "REPLACED")
    println(sb)
    sb.replace(2, 1000, "!")
    println(sb)
    sb.replace(sb.length, sb.length, "-end")
    println(sb)
    sb.setCharAt(0, 'Z')
    println(sb)
    val e = new JStringBuilder("abc")
    e.delete(1, 1).replace(0, 0, "")
    println(s"$e ${e.substring(1)} ${e.substring(0, 2)} ${e.subSequence(1, 3)} ${e.charAt(2)}")

  def reversing(): Unit =
    println("== reverse")
    println(new JStringBuilder("hello").reverse())
    println(new JStringBuilder("").reverse().length)
    val pairs = new JStringBuilder("a😀b𝄞c").reverse().toString
    println(s"$pairs ${pairs.codePoints().toArray.map(_.toHexString).mkString(",")}")
    val lone = new JStringBuilder("x\ud800y\udc00z").reverse().toString
    println(lone.map(_.toInt.toHexString).mkString(","))
    val swapped = new JStringBuilder("\udc00\ud800").reverse().toString
    println(swapped.map(_.toInt.toHexString).mkString(","))

  def lengths(): Unit =
    println("== length and capacity")
    val sb = new JStringBuilder("abcdef")
    sb.setLength(3)
    println(s"[$sb] ${sb.length}")
    sb.setLength(5)
    println(s"${sb.toString.map(_.toInt).mkString(",")} ${sb.length}")
    sb.setLength(0)
    println(s"[$sb] ${sb.length} ${sb.isEmpty}")
    val c = new JStringBuilder()
    println(s"capacity ${c.capacity}")
    c.ensureCapacity(50)
    println(s"ensureCapacity ${c.capacity >= 50}")
    c.append("x" * 100)
    c.trimToSize()
    println(s"after 100 ${c.length} ${c.capacity >= 100}")
    val dst = Array.fill(5)('.')
    new JStringBuilder("hello").getChars(1, 4, dst, 1)
    println(dst.mkString)
    println(s"compareTo ${new JStringBuilder("abc").compareTo(new JStringBuilder("abd"))} ${new JStringBuilder("b").compareTo(new JStringBuilder("a"))} ${new JStringBuilder("x").compareTo(new JStringBuilder("x"))}")
    println(s"equals by identity ${new JStringBuilder("x").equals(new JStringBuilder("x"))}")

  def searching(): Unit =
    println("== indexOf")
    val sb = new JStringBuilder("abcabcabc")
    println(s"${sb.indexOf("bc")} ${sb.indexOf("bc", 2)} ${sb.indexOf("zz")} ${sb.lastIndexOf("bc")} ${sb.lastIndexOf("bc", 6)} ${sb.indexOf("")} ${sb.lastIndexOf("")}")
    val chars = new JStringBuilder("héllo").chars().toArray.mkString(",")
    println(chars)

  def buffers(): Unit =
    println("== StringBuffer")
    val b = new StringBuffer()
    b.append("one").append(' ').append(2).append(' ').append(3L).append(' ').append(4.5f).append(' ').append(6.75).append(' ').append(false)
    b.append(Array('!', '?')).append(Array('a', 'b', 'c'), 1, 1).append("xyz": CharSequence, 0, 2).append(null: Object).append(new StringBuffer("+"))
    println(s"$b ${b.length}")
    b.insert(0, ">> ").delete(3, 7).deleteCharAt(0)
    println(b)
    b.setCharAt(0, '#')
    b.setLength(8)
    println(s"$b ${b.charAt(1)} ${b.substring(2)} ${b.substring(2, 4)} ${b.subSequence(0, 1)} ${b.indexOf("2")} ${b.lastIndexOf(" ")}")
    println(s"${new StringBuffer("cs": CharSequence)} [${new StringBuffer(10)}] ${new StringBuffer(10).capacity}")

  def errors(): Unit =
    println("== errors")
    def sb = new JStringBuilder("abc")
    attemptType("charAt(3)")(sb.charAt(3))
    attemptType("charAt(-1)")(sb.charAt(-1))
    attemptType("setCharAt(5)")(sb.setCharAt(5, 'x'))
    attemptType("insert(4)")(sb.insert(4, "x"))
    attemptType("insert(-1)")(sb.insert(-1, "x"))
    attemptType("delete(2, 1)")(sb.delete(2, 1))
    attemptType("delete(-1, 1)")(sb.delete(-1, 1))
    attemptType("deleteCharAt(3)")(sb.deleteCharAt(3))
    attemptType("replace(4, 5)")(sb.replace(4, 5, "x"))
    attemptType("replace(2, 1)")(sb.replace(2, 1, "x"))
    attemptType("substring(2, 5)")(sb.substring(2, 5))
    attemptType("substring(4)")(sb.substring(4))
    attempt("setLength(-1)")(sb.setLength(-1))
    attempt("new StringBuilder(-1)")(new JStringBuilder(-1))
    attemptType("StringBuffer charAt(9)")(new StringBuffer("abc").charAt(9))
