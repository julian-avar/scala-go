import java.io.{PrintWriter, StringWriter}
import java.util.Locale

// Smaller JDK APIs: Properties, Locale, String.join and locale variants, printStackTrace to a
// PrintWriter, and scala.util.Properties (which needs java.util.Properties and jar.Attributes).
object Main:
  def main(args: Array[String]): Unit =
    val props = new java.util.Properties()
    props.load(new java.io.StringReader("a=1\n# comment\nb : two words\ncontinued = con\\\n    tinued\nunicode=\\u00e9\n"))
    println(props.getProperty("a") + " " + props.getProperty("b") + " " + props.getProperty("continued") + " " +
      props.getProperty("unicode") + " " + props.getProperty("missing", "default"))
    val defaults = new java.util.Properties()
    defaults.setProperty("x", "from defaults")
    val layered = new java.util.Properties(defaults)
    println(layered.getProperty("x") + " " + layered.size())

    println(List(Locale.ROOT, Locale.US, Locale.ENGLISH, Locale.UK, new Locale("FR", "ca"), new Locale("", "DE"))
      .map(l => s"[$l|${l.toLanguageTag}|${l.getLanguage}|${l.getCountry}]").mkString(" "))
    println(Locale.forLanguageTag("pt-BR") == new Locale("pt", "BR"))
    println("MiXeD".toLowerCase(Locale.ROOT) + " " + "MiXeD".toUpperCase(Locale.ENGLISH))
    println(String.format(Locale.ROOT, "%d-%s-%.2f", Integer.valueOf(7), "x", java.lang.Double.valueOf(1.005)))
    println(String.join(", ", "a", "b", null, "d") + "|" + String.join("", "x") + "|" + String.join("-"))

    val sw = new StringWriter()
    val e = new IllegalStateException("outer", new RuntimeException("inner"))
    e.printStackTrace(new PrintWriter(sw))
    val lines = sw.toString.linesIterator.toList
    println(lines.head)
    println(lines.exists(_.startsWith("Caused by: java.lang.RuntimeException: inner")))
    println(lines.filter(_.startsWith("\tat Main")).map(_.replaceAll(":\\d+\\)", ")")).distinct.mkString(" "))

    println(scala.util.Properties.lineSeparator == "\n")
    println(classOf[Main.type].getResourceAsStream("/missing.properties") == null)
    println(new java.util.jar.Attributes.Name("Main-Class") == java.util.jar.Attributes.Name.MAIN_CLASS)
