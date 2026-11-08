import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

import gofacades.{argus, time}
import scalago.go.GoData

/** A service's configuration, as the application uses it: rebuilt from each revision. */
final case class ServiceConfig(host: String, port: Long, timeout: java.time.Duration, logLevel: String, features: Seq[String]):
  def describe: String =
    s"$host:$port, timeout ${timeout.toSeconds}s, log level $logLevel, features ${features.mkString("[", ", ", "]")}"

object ServiceConfig:
  /** Used when no source has a key (argus reads `server.port` from `{"server": {"port": ...}}`). */
  val defaults = GoData.map(
    "server.host" -> "localhost",
    "server.port" -> 8080,
    "server.timeout" -> "30s",
    "log.level" -> "info",
    "features" -> Seq.empty[String]
  )

  def from(s: argus.Settings): ServiceConfig = ServiceConfig(
    s.GetString("server.host"),
    s.GetInt("server.port"),
    java.time.Duration.ofNanos(s.GetDuration("server.timeout").Nanoseconds()),
    s.GetString("log.level"),
    s.GetStringSlice("features").toSeq.map(_.toJavaString)
  )

/** What happened to the configuration. */
enum ConfigEvent:
  case Reloaded(config: ServiceConfig, changedKeys: Seq[String])
  case Rejected(error: String)

/** Configuration that follows its file: argus reloads it when the file changes, and the current
 *  `ServiceConfig` is swapped atomically. A file that does not parse is rejected, and the last good
 *  configuration stays.
 */
final class LiveConfig(file: Path, onEvent: ConfigEvent => Unit):
  private val current = AtomicReference[ServiceConfig]()

  // Callbacks come from argus's own goroutines.
  private val settings = argus.Setup("demo")
    .Defaults(ServiceConfig.defaults)
    .File(file.toString)
    .MaxStaleness(time.ParseDuration("100ms"))
    .OnReload { (s, change) =>
      val config = ServiceConfig.from(s)
      current.set(config)
      onEvent(ConfigEvent.Reloaded(config, change.Keys.toSeq.map(_.toJavaString).sorted))
    }
    .OnError(err => onEvent(ConfigEvent.Rejected(err.Error())))
    .Start()
  current.set(ServiceConfig.from(settings))

  def get: ServiceConfig = current.get
  def revision: Long = settings.Revision()
  def close(): Unit = settings.Close()

/** Feature flags read as plain data: argus's universal watcher parses any format (here YAML) into
 *  a Go map, which `GoData.toScala` turns into Scala data.
 */
final class FlagsWatcher(file: Path, onFlags: Map[String, Any] => Unit):
  private val config = new argus.Config
  config.PollInterval = time.ParseDuration("100ms")
  config.DisableAudit = true // no audit trail (argus writes one to a SQLite file by default)
  config.ErrorHandler = (err, path) => System.err.println(s"flags: $path: ${err.Error()}")

  private val watcher = argus.UniversalConfigWatcherWithConfig(file.toString, flags =>
    GoData.toScala(flags) match
      case m: Map[String, Any] @unchecked => onFlags(m)
      case other                          => System.err.println(s"flags: unexpected $other"),
    config)

  def close(): Unit = watcher.Close()

object Main:
  def main(args: Array[String]): Unit = args.toList match
    case List("--self-test") => SelfTest.run()
    case List(file) =>
      val live = LiveConfig(Path.of(file), {
        case ConfigEvent.Reloaded(config, keys) => println(s"reloaded (${keys.mkString(", ")} changed): ${config.describe}")
        case ConfigEvent.Rejected(error)        => println(s"rejected: $error")
      })
      println(s"watching $file (Ctrl-C to stop): ${live.get.describe}")
      Thread.sleep(Long.MaxValue)
    case _ => System.err.println("usage: argus-demo <config file> | --self-test")

/** Edits configuration files in a temporary directory and checks that each change is seen. */
object SelfTest:
  private var failures = 0

  private def check(what: String, actual: Any, expected: Any): Unit =
    val ok = actual == expected
    if !ok then failures += 1
    println(s"${if ok then "ok  " else "FAIL"} $what: $actual")
    if !ok then println(s"     expected $expected")

  /** Replaces a file at once (a watcher must never see half of it). */
  private def write(file: Path, content: String): Unit =
    val tmp = file.resolveSibling(s".${file.getFileName}.tmp")
    Files.writeString(tmp, content)
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

  private def next[T](queue: LinkedBlockingQueue[T]): Option[T] = Option(queue.poll(10, TimeUnit.SECONDS))

  def run(): Unit =
    val dir = Files.createTempDirectory("argus-demo")
    try
      settingsTest(dir.resolve("service.json"))
      flagsTest(dir.resolve("flags.yaml"))
    finally
      Files.list(dir).forEach(Files.delete)
      Files.delete(dir)
    if failures == 0 then println("all changes seen as expected")
    else
      println(s"$failures checks failed")
      sys.exit(1)

  private def settingsTest(file: Path): Unit =
    write(file, """{"server": {"host": "0.0.0.0", "port": 9000}, "log": {"level": "debug"}}""")
    val events = LinkedBlockingQueue[ConfigEvent]()
    val live = LiveConfig(file, events.put)
    try
      check("initial", live.get, ServiceConfig("0.0.0.0", 9000, java.time.Duration.ofSeconds(30), "debug", Nil))

      write(file, """{"server": {"host": "0.0.0.0", "port": 9001, "timeout": "5s"}, "log": {"level": "debug"}, "features": ["search", "export"]}""")
      val expected = ServiceConfig("0.0.0.0", 9001, java.time.Duration.ofSeconds(5), "debug", Seq("search", "export"))
      check("edited", next(events), Some(ConfigEvent.Reloaded(expected, Seq("features", "server.port", "server.timeout"))))
      check("current", live.get, expected)

      write(file, """{"server": {"port": """)
      check("broken file", next(events).map(_.isInstanceOf[ConfigEvent.Rejected]), Some(true))
      check("kept", live.get, expected)

      write(file, """{"server": {"host": "0.0.0.0", "port": 9001, "timeout": "5s"}, "log": {"level": "warn"}, "features": ["search", "export"]}""")
      check("repaired", next(events), Some(ConfigEvent.Reloaded(expected.copy(logLevel = "warn"), Seq("log.level"))))
      check("revisions", live.revision, 3L)
    finally live.close()

  private def flagsTest(file: Path): Unit =
    write(file, "dark_mode: true\nbeta:\n  users: [ana, bo]\n  rollout: 0.25\n")
    val seen = LinkedBlockingQueue[Map[String, Any]]()
    val watcher = FlagsWatcher(file, seen.put)
    try
      check("flags", next(seen), Some(Map("dark_mode" -> true, "beta" -> Map("users" -> Vector("ana", "bo"), "rollout" -> 0.25))))
      write(file, "dark_mode: false\nbeta:\n  users: [ana, bo, cy]\n  rollout: 0.5\nmax_items: 20\n")
      check("flags edited", next(seen),
        Some(Map("dark_mode" -> false, "beta" -> Map("users" -> Vector("ana", "bo", "cy"), "rollout" -> 0.5), "max_items" -> 20L)))
    finally watcher.close()
