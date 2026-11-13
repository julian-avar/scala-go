import scala.util.matching.Regex

/** Scala regular expressions (java.util.regex on Go's regexp). */
object Main:
  val Date: Regex = """(\d{4})-(\d{2})-(\d{2})""".r
  val Word = "[a-z]+".r

  def main(args: Array[String]): Unit =
    "2026-11-06" match
      case Date(y, m, d) => println(s"year $y, month $m, day $d")
      case _             => println("no date")
    println("not a date" match { case Date(_, _, _) => "date"; case _ => "no date" })

    val text = "the cat sat on 2 mats and 13 hats"
    println(Word.findFirstIn(text))
    println("""\d+""".r.findAllIn(text).toList)
    println(Word.findAllMatchIn(text).map(m => s"${m.matched}@${m.start}").take(3).mkString(" "))
    println("""(\w+)at""".r.replaceAllIn(text, m => m.group(1).toUpperCase + "AT"))
    println("""\s+""".r.split("a  b \t c").toList)
    println(Date.findFirstMatchIn("on 1999-12-31!").map(m => (m.group(1), m.end)))
    println("""a(b)?c""".r.findFirstMatchIn("ac").map(m => m.group(1)))
    println(Regex.quote("1+1=2") + " " + "1+1=2".matches(Regex.quote("1+1=2")))
    println("(?i)hello".r.matches("HeLLo"))
    println("x".r.unapplySeq("x"))

    val p = java.util.regex.Pattern.compile("(\\w)(\\d)")
    val m = p.matcher("a1 b2 c3")
    val found = scala.collection.mutable.ListBuffer.empty[String]
    while m.find() do found += s"${m.group(2)}${m.group(1)}"
    println(found.mkString(","))
    println(p.matcher("z9").matches())
    println("tel: 555-1234".replaceAll("(\\d+)-(\\d+)", "$2-$1"))

    // Empty matches right after a match are found too, as in Java.
    println("a*".r.findAllMatchIn("baaac").map(m => s"${m.start}-${m.end}").mkString(" "))
    println("(a*)".r.replaceAllIn("baaac", m => s"<${m.group(1)}>"))
    val words = java.util.regex.Pattern.compile("(?<word>\\w+)").matcher("one two")
    val sb = new java.lang.StringBuilder()
    while words.find() do words.appendReplacement(sb, "${word}!")
    println(words.appendTail(sb))
    try java.util.regex.Pattern.compile("(")
    catch case e: java.util.regex.PatternSyntaxException => println(s"${e.getClass.getName} ${e.getPattern}")
