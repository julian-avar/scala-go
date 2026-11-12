import scalago.go.*
import scalago.gostd.{context, errors, math, sync, time}

/** A context key: compared by identity, as Go compares pointers. */
object RequestId

/** scalago-gostd's sync, context, time and math (docs/_docs/libraries/gostd.md). */
object Main:
  def main(args: Array[String]): Unit =
    // sync: WaitGroup.Go, Mutex, Once, OnceValue, Map, RWMutex, Pool with Scala closures.
    val wg = new sync.WaitGroup
    val mu = new sync.Mutex
    var total = 0
    for i <- 1 to 20 do wg.Go(() => { mu.Lock(); total += i; mu.Unlock() })
    wg.Wait()
    println(s"WaitGroup: $total TryLock: ${mu.TryLock()} ${mu.TryLock()}")
    mu.Unlock()
    val once = new sync.Once
    for _ <- 1 to 3 do once.Do(() => println("Once runs once"))
    var computed = 0
    val value = sync.OnceValue(() => { computed += 1; s"value $computed" })
    println(s"OnceValue: ${value()} ${value()} computed $computed")
    val m = new sync.Map
    m.Store("a", 1)
    m.Store(go"b", 2L)
    val (loaded, ok) = m.LoadOrStore("a", 100)
    println(s"Map: ${m.Load("a")} ${m.Load("zzz")._2} LoadOrStore: $loaded $ok")
    val keys = collection.mutable.ListBuffer.empty[String]
    m.Range((k, v) => { keys += s"$k=$v"; true })
    println(s"Range: ${keys.sorted.mkString(",")}")
    val rw = new sync.RWMutex
    rw.RLock()
    println(s"RWMutex: ${rw.TryRLock()} ${rw.TryLock()}")
    rw.RUnlock()
    rw.RUnlock()
    val pool = new sync.Pool
    pool.New = () => new StringBuilder("fresh")
    println(s"Pool: ${pool.Get()}")

    // context: cancellation, values keyed by Scala objects, AfterFunc callbacks.
    val (ctx, cancel) = context.WithCancel(context.Background())
    println(s"before cancel: ${ctx.Err()}")
    val fired = GoChan.make[String](1)
    context.AfterFunc(ctx, () => fired.send("AfterFunc ran"))
    cancel()
    println(s"after cancel: ${ctx.Err().Error()} ${errors.Is(ctx.Err(), context.Canceled)} ${fired.receive().get}")
    val withValue = context.WithValue(context.Background(), RequestId, "req-7")
    println(s"Value: ${withValue.Value(RequestId)} ${withValue.Value("other")}")
    val (timed, stop) = context.WithTimeout(context.Background(), time.Duration(20L * 1000000L))
    println(s"deadline set: ${timed.Deadline()._2} ${context.Background().Deadline()._2}")
    time.After(time.Duration(60L * 1000000L)).receive()
    println(s"after the timeout: ${errors.Is(timed.Err(), context.DeadlineExceeded)}")
    stop()
    val (caused, cancelCause) = context.WithCancelCause(context.Background())
    cancelCause(errors.New("shutting down"))
    println(s"Cause: ${context.Cause(caused).Error()} ${caused.Err().Error()}")

    // time: fixed instants only.
    val t = time.Date(2024, time.March, 9, 14, 30, 5, 0, time.UTC)
    println(s"Date: ${t.String()} ${t.Format(time.RFC3339)} ${t.Format(time.Kitchen)} ${t.Weekday().String()} day ${t.YearDay()}")
    val later = t.Add(time.Duration(90L * 60 * 1000000000L)).AddDate(0, 1, 0)
    println(s"Add: ${later.Format(time.DateOnly)} ${later.Hour()}:${later.Minute()} ${later.Sub(t).String()} ${later.After(t)} ${t.Equal(t.UTC())}")
    println(s"Unix: ${time.Unix(0, 0).UTC().String()} ${t.Unix()}")
    val parsed = time.Parse("2006-01-02 15:04", "2023-12-31 23:59")
    println(s"Parse: ${parsed.Year()} ${parsed.Month().String()} ${parsed.Day()} ${time.Month(2).String()}")
    try time.Parse("2006-01-02", "31/12/2023")
    catch case e: GoException => println(s"Parse failed: ${e.getMessage}")
    val zone = time.FixedZone("UTC+2", 2 * 3600)
    println(s"In: ${t.In(zone).Format(time.RFC3339)} ${zone.String()}")
    val d = time.ParseDuration("1h15m30.5s")
    println(s"Duration: ${d.String()} ${d.Hours()} ${d.Minutes()} ${d.Truncate(time.Minute).String()} ${d.Round(time.Hour).String()} ${time.Duration(-1500L).Abs().String()}")
    try time.ParseDuration("forever")
    catch case e: GoException => println(s"ParseDuration failed: ${e.getMessage}")

    // math: IEEE 754 special values, as Go computes them.
    println(s"Inf: ${math.Inf(1)} ${math.Inf(-1)} ${math.IsInf(math.Inf(1), 0)} NaN: ${math.IsNaN(math.NaN())}")
    println(s"limits: ${math.MaxInt64} ${math.MinInt64} ${math.MaxUint32} ${math.MaxFloat64} ${math.SmallestNonzeroFloat64}")
    println(s"rounding: ${math.Floor(-1.5)} ${math.Ceil(-1.5)} ${math.Trunc(-1.5)} ${math.Round(2.5)} ${math.RoundToEven(2.5)} ${math.Mod(-7, 3)}")
    println(s"functions: ${math.Pow(2, 10)} ${math.Hypot(3, 4)} ${math.Cbrt(27)} ${math.Log2(8)} ${math.Abs(-2)} ${math.Copysign(3, -1)}")
    println(s"bits: ${math.Float64frombits(0x3FF0000000000000L)} ${math.Signbit(-0.0)} ${math.Nextafter(1, 2) - 1}")
