package scalago.runtime

/** The native side of java.time (runtime/rt/time.go): the clock, and time zones as Go
 *  `time.Location`s. Go reads the tz database from the system (zoneinfo directories, `ZONEINFO`)
 *  and otherwise from its embedded copy, `time/tzdata`, which the linker adds to programs that
 *  can reach [[zone]].
 */
object Time:
  private def native: Nothing = throw new UnsupportedOperationException("scala-go native")

  /** The current time, in nanoseconds since the epoch. */
  def nowNanos(): Long = native

  /** The `time.Location` of the region ID `id` ("Europe/Paris"), or null if there is none. */
  def zone(id: String): Object = native

  /** The offset from UTC, in seconds, of `zone` at `epochSecond`. */
  def offset(zone: Object, epochSecond: Long): Int = native

  /** The period around `epochSecond` during which `zone` keeps the same offset:
   *  `Array(offset, start, end, isDST)`, where `start` is `Long.MinValue` and `end` is
   *  `Long.MaxValue` when the period is unbounded, and `isDST` is 1 for daylight saving time.
   */
  def period(zone: Object, epochSecond: Long): Array[Long] = native

  /** The abbreviation of the zone's name at `epochSecond` ("EST", "CEST", "+03"). */
  def abbreviation(zone: Object, epochSecond: Long): String = native

  /** The ID of the system's time zone, found as the JDK does on Linux: the `TZ` environment
   *  variable, then `/etc/timezone`, then the target of the `/etc/localtime` link, or the
   *  zoneinfo file with the same contents. "GMT" if none of them names a known zone.
   */
  def systemZoneId(): String = native

  /** The region IDs of the system's tz database (empty if it has none). */
  def zoneIds(): Array[String] = native
