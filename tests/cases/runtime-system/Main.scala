import java.io.File
import java.nio.charset.Charset
import java.time.{Instant, ZoneId}
import java.util.{Locale, TimeZone}

import scala.jdk.CollectionConverters.*

/** System properties, the environment (env.txt), the clocks, the default time zone (TZ in
 *  env.txt), the default locale and charset (LC_ALL in env.txt).
 */
object Main:
  def attempt(body: => Any): String =
    try String.valueOf(body)
    catch case e: Exception => e.getClass.getName

  def show(s: String): String = if s == null then "null" else s.replace("\n", "\\n").replace("\r", "\\r")

  def main(args: Array[String]): Unit =
    println("-- properties")
    for key <- Seq("line.separator", "file.separator", "path.separator", "file.encoding", "native.encoding",
        "sun.jnu.encoding", "stdout.encoding", "stderr.encoding", "java.io.tmpdir", "os.name") do
      println(s"$key=${show(System.getProperty(key))}")
    println(s"os.arch known: ${Set("amd64", "aarch64").contains(System.getProperty("os.arch"))}")
    val userDir = System.getProperty("user.dir")
    println(s"user.dir: absolute=${new File(userDir).isAbsolute} is the working directory=${userDir == new File("").getAbsolutePath} " +
      s"exists=${new File(userDir).isDirectory}")
    println(s"user.home and user.name set: ${!System.getProperty("user.home").isEmpty} ${!System.getProperty("user.name").isEmpty}")
    println(s"lineSeparator: ${show(System.lineSeparator)} ${System.lineSeparator == System.getProperty("line.separator")}")

    val props = System.getProperties
    println(s"getProperties: ${props.getProperty("os.name")} ${props.stringPropertyNames.contains("line.separator")}")
    println(s"setProperty: ${System.setProperty("scalago.test.n", "12")} ${System.setProperty("scalago.test.n", "34")} " +
      s"${props.getProperty("scalago.test.n")} ${props.containsKey("scalago.test.n")}")
    props.setProperty("scalago.test.flag", "true")
    println(s"set through getProperties: ${System.getProperty("scalago.test.flag")}")
    println(s"clearProperty: ${System.clearProperty("scalago.test.n")} ${System.getProperty("scalago.test.n")} " +
      s"${System.getProperty("scalago.test.n", "fallback")}")
    println(s"sys.props: ${sys.props.get("os.name")} ${sys.props.get("scalago.test.none")}")
    sys.props("scalago.test.scala") = "from Scala"
    println(s"sys.props update: ${System.getProperty("scalago.test.scala")}")
    println(s"null key: ${attempt(System.getProperty(null))}, null value: ${attempt(System.setProperty("k", null))}")

    println("-- environment")
    println(s"getenv: [${System.getenv("SCALAGO_TEST_GREETING")}] [${System.getenv("SCALAGO_TEST_EMPTY")}] ${System.getenv("SCALAGO_TEST_UNSET")}")
    val env = System.getenv()
    println(s"getenv(): ${env.get("SCALAGO_TEST_GREETING")} ${env.containsKey("SCALAGO_TEST_EMPTY")} ${env.containsKey("SCALAGO_TEST_UNSET")} " +
      s"${env.asScala.get("SCALAGO_TEST_GREETING") == sys.env.get("SCALAGO_TEST_GREETING")} unmodifiable: ${attempt(env.remove("SCALAGO_TEST_GREETING"))}")
    def echo(builder: ProcessBuilder): String =
      val p = builder.redirectErrorStream(true).start()
      val out = new String(p.getInputStream.readAllBytes(), "UTF-8").trim
      p.waitFor()
      out
    val command = Seq("sh", "-c", "echo \"[$SCALAGO_TEST_GREETING] [${SCALAGO_TEST_UNSET-unset}]\"")
    println(s"inherited by a subprocess: ${echo(new ProcessBuilder(command*))}")
    val changed = new ProcessBuilder(command*)
    changed.environment.put("SCALAGO_TEST_GREETING", "changed")
    changed.environment.put("SCALAGO_TEST_UNSET", "now set")
    println(s"ProcessBuilder.environment: ${echo(changed)}; unchanged here: ${System.getenv("SCALAGO_TEST_GREETING")} ${System.getenv("SCALAGO_TEST_UNSET")}")

    println("-- clocks")
    var previous = System.nanoTime()
    var monotonic = true
    for _ <- 1 to 100000 do
      val now = System.nanoTime()
      if now < previous then monotonic = false
      previous = now
    println(s"nanoTime never goes back: $monotonic")
    val before = System.nanoTime()
    Thread.sleep(30)
    println(s"sleep(30) took at least 30 ms: ${System.nanoTime() - before >= 30_000_000L}")
    val millis = System.currentTimeMillis()
    println(s"currentTimeMillis: after 2024 ${millis > 1704067200000L}, close to Instant.now ${math.abs(Instant.now().toEpochMilli - millis) < 10000}")

    println("-- zone, locale, charset")
    println(s"${ZoneId.systemDefault} ${TimeZone.getDefault.getID} ${TimeZone.getDefault.getRawOffset}")
    println(s"${java.time.ZonedDateTime.ofInstant(Instant.ofEpochSecond(1700000000L), ZoneId.systemDefault)}")
    println(s"${Locale.getDefault} ${Locale.getDefault.getLanguage} ${Locale.getDefault.getCountry} ${Locale.getDefault.toLanguageTag}")
    println(s"${Charset.defaultCharset} ${"%.2f".format(1234.5)} ${"TITLE".toLowerCase} ${"i".toUpperCase}")

    println("-- gc")
    System.gc()
    Runtime.getRuntime.gc()
    println("gc returned")
