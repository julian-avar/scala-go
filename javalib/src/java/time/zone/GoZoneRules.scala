package java.time.zone

import java.time.{ Duration, Instant, LocalDateTime, ZoneOffset }
import java.util.{ Collections, Objects }

import scalago.runtime.Time

/**
 * The provider of the time-zone database: Go's (see scalago.runtime.Time). A zone exists when Go
 * can load it, from the system's zoneinfo or from the copy that programs using zones embed.
 */
private[time] object GoZoneRulesProvider extends ZoneRulesProvider {

  /** The region IDs of the system's database: only used to list zones. */
  lazy val zoneIds: java.util.Set[String] = {
    val ids = new java.util.HashSet[String]()
    for (id <- Time.zoneIds()) ids.add(id)
    Collections.unmodifiableSet(ids)
  }

  def contains(zoneId: String): Boolean = Time.zone(zoneId) != null

  override protected def provideZoneIds: java.util.Set[String] = zoneIds

  override protected def provideRules(regionId: String, forCaching: Boolean): ZoneRules = {
    val zone = Time.zone(regionId)
    if (zone == null) throw new ZoneRulesException(s"Unknown time-zone ID: $regionId")
    new GoZoneRules(regionId, zone)
  }

  override protected def provideVersions(zoneId: String): java.util.NavigableMap[String, ZoneRules] = {
    val versions = new java.util.TreeMap[String, ZoneRules]()
    versions.put("go", provideRules(zoneId, forCaching = false))
    versions
  }
}

/**
 * The rules of a region's time zone, read from a Go `time.Location`.
 *
 * Go gives the offset at an instant and the period around it during which the offset stays the
 * same (`time.Time.ZoneBounds`). Transitions are the ends of periods whose offsets differ. A local
 * date-time is resolved by looking at the periods within 18 hours of it (the largest offset):
 * each period whose offset maps the local date-time back into the period gives a valid offset.
 * There is one in the normal case, none in a gap and two in an overlap.
 */
private[time] final class GoZoneRules(private val id: String, zone: AnyRef) extends ZoneRules {
  import GoZoneRules._

  private def span(epochSecond: Long): Span = {
    val p = Time.period(zone, epochSecond)
    new Span(ZoneOffset.ofTotalSeconds(p(0).toInt), p(1), p(2), p(3) != 0)
  }

  /** The periods that contain instants within 18 hours of `localSecond`, in order. */
  private def spansAround(localSecond: Long): List[Span] = {
    val spans = List.newBuilder[Span]
    var p       = span(localSecond - MaxOffsetSeconds)
    spans += p
    while (p.end <= localSecond + MaxOffsetSeconds) {
      p = span(p.end)
      spans += p
    }
    spans.result()
  }

  /** The valid offsets at `localSecond`, or the transition of the gap or overlap it is in. */
  private def resolve(dateTime: LocalDateTime): Either[ZoneOffset, ZoneOffsetTransition] = {
    val local   = dateTime.toEpochSecond(ZoneOffset.UTC)
    val spans = spansAround(local)
    val valid   = spans.filter(_.contains(local))
    if (valid.size == 1) Left(valid.head.offset)
    else {
      // Two valid offsets (an overlap) or none (a gap): the transition between two periods
      // with different offsets that `local` falls around.
      // (Not `collect`: a partial function literal crashes the compiler in javalib, which
      // defines java.io.Serializable.)
      val transitions = spans.zip(spans.tail).filter(pair => pair._1.offset != pair._2.offset)
      val (before, after) = transitions
        .find { case (b, a) =>
          val (lo, hi) = (a.start + b.offset.getTotalSeconds, a.start + a.offset.getTotalSeconds)
          local >= math.min(lo, hi) && local < math.max(lo, hi)
        }
        .getOrElse(transitions.head)
      Right(new ZoneOffsetTransition(after.start, before.offset, after.offset))
    }
  }

  def isFixedOffset: Boolean = {
    val p = span(0)
    p.start == Long.MinValue && p.end == Long.MaxValue
  }

  def getOffset(instant: Instant): ZoneOffset =
    ZoneOffset.ofTotalSeconds(Time.offset(zone, instant.getEpochSecond))

  def getOffset(localDateTime: LocalDateTime): ZoneOffset =
    resolve(localDateTime) match {
      case Left(offset)      => offset
      case Right(transition) => transition.getOffsetBefore
    }

  def getValidOffsets(localDateTime: LocalDateTime): java.util.List[ZoneOffset] =
    resolve(localDateTime) match {
      case Left(offset)      => Collections.singletonList(offset)
      case Right(transition) => transition.getValidOffsets
    }

  def getTransition(localDateTime: LocalDateTime): ZoneOffsetTransition =
    resolve(localDateTime).toOption.orNull

  def isValidOffset(localDateTime: LocalDateTime, offset: ZoneOffset): Boolean =
    getValidOffsets(localDateTime).contains(offset)

  /**
   * Go knows whether a period is daylight saving time, but not its standard offset: that of the
   * latest standard-time period before it.
   */
  def getStandardOffset(instant: Instant): ZoneOffset = {
    var p     = span(instant.getEpochSecond)
    val first = p
    var steps = 0
    while (p.isDST && p.start != Long.MinValue && steps < 16) {
      p = span(p.start - 1)
      steps += 1
    }
    if (p.isDST) first.offset else p.offset
  }

  def getDaylightSavings(instant: Instant): Duration =
    Duration.ofSeconds(
      getOffset(instant).getTotalSeconds.toLong - getStandardOffset(instant).getTotalSeconds
    )

  def isDaylightSavings(instant: Instant): Boolean =
    getStandardOffset(instant) != getOffset(instant)

  def nextTransition(instant: Instant): ZoneOffsetTransition =
    nextTransitionAfter(instant.getEpochSecond)

  private def nextTransitionAfter(epochSecond: Long): ZoneOffsetTransition = {
    var p = span(epochSecond)
    while (p.end != Long.MaxValue) {
      val next = span(p.end)
      if (next.offset != p.offset) return new ZoneOffsetTransition(p.end, p.offset, next.offset)
      p = next
    }
    null
  }

  def previousTransition(instant: Instant): ZoneOffsetTransition = {
    // The latest transition strictly before the instant.
    val epochSecond = instant.getEpochSecond + (if (instant.getNano > 0) 1 else 0)
    var p           = span(epochSecond - 1)
    while (p.start != Long.MinValue) {
      val before = span(p.start - 1)
      if (before.offset != p.offset) return new ZoneOffsetTransition(p.start, before.offset, p.offset)
      p = before
    }
    null
  }

  /** The transitions until the end of 2037 (Go has no list of rules for the years after). */
  def getTransitions: java.util.List[ZoneOffsetTransition] = {
    val transitions = new java.util.ArrayList[ZoneOffsetTransition]()
    var t           = nextTransitionAfter(EarliestSecond)
    while (t != null && t.toEpochSecond < TransitionsUntil) {
      transitions.add(t)
      t = nextTransitionAfter(t.toEpochSecond)
    }
    Collections.unmodifiableList(transitions)
  }

  def getTransitionRules: java.util.List[ZoneOffsetTransitionRule] =
    Collections.emptyList[ZoneOffsetTransitionRule]()

  override def equals(other: Any): Boolean = other match {
    case that: GoZoneRules => id == that.id
    case _                 => false
  }

  override def hashCode: Int = id.hashCode

  override def toString: String = s"ZoneRules[currentStandardOffset=${getStandardOffset(Instant.now)}]"
}

private object GoZoneRules {
  private val MaxOffsetSeconds = 18 * 3600L

  /** Far enough back for every zone's first transition (Go cannot represent much earlier). */
  private val EarliestSecond = -1L << 40

  /** 2038-01-01T00:00:00Z. */
  private val TransitionsUntil = 2145916800L

  /** A span of instants `[start, end)` with the same offset. */
  private final class Span(val offset: ZoneOffset, val start: Long, val end: Long, val isDST: Boolean) {

    /** Whether the local date-time `localSecond` at this period's offset falls in the period. */
    def contains(localSecond: Long): Boolean = {
      val instant = localSecond - offset.getTotalSeconds
      start <= instant && instant < end
    }
  }
}
