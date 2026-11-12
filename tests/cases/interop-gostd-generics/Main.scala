import scalago.go.*
import scalago.gostd.{atomic, cmp, errors, fs, iter, maps, os, reflect, slices, sql, strings, sync}

/** Generic Go functions and types of the standard library, through generated facades: type
 *  arguments are canonical views (GoInt, GoString), given or inferred in Scala.
 */
object Main:
  def show[T](s: GoSlice[T]): String = s.iterator.mkString("[", " ", "]")

  def main(args: Array[String]): Unit =
    val xs = GoSlice.of(GoInt(3), GoInt(1), GoInt(2))
    slices.Sort(xs)
    println(s"Sort: ${show(xs)}, Max: ${slices.Max(xs)}, Index(2): ${slices.Index(xs, GoInt(2))}")
    println(s"Contains: ${slices.Contains(xs, GoInt(5))}, BinarySearch: ${slices.BinarySearch(xs, GoInt(3))}")
    // Variadic type parameter values.
    println(s"Insert: ${show(slices.Insert(xs, 1, GoInt(10), GoInt(11)))}")

    val words = GoSlice.of(GoString("pear"), GoString("fig"), GoString("banana"))
    // A Scala lambda as func(a, b E) int.
    slices.SortFunc(words, (a, b) => cmp.Compare(a.byteLength, b.byteLength))
    println(s"SortFunc by length: ${show(words)}")
    println(s"cmp.Compare: ${cmp.Compare(GoString("a"), GoString("b"))} ${cmp.Compare(2.5, 1.0)}")

    // iter.Seq: Go iterators are Scala functions taking a yield function.
    val m = GoMap.of(GoString("b") -> GoInt(2), GoString("a") -> GoInt(1), GoString("c") -> GoInt(3))
    println(s"maps.Keys, sorted: ${show(slices.Sorted(maps.Keys(m)))}")
    val lines = strings.Lines("one\ntwo\nthree\n")
    val seen = collection.mutable.ListBuffer.empty[String]
    lines { line => seen += line.toJavaString.trim; seen.size < 2 }
    println(s"strings.Lines, stopped after 2: $seen")
    // A Scala function as an iter.Seq.
    val squares: iter.Seq[GoInt] = yieldTo => { (1 to 4).forall(i => yieldTo(GoInt(i * i))); () }
    println(s"slices.Collect of a Scala iterator: ${show(slices.Collect(squares))}")
    val (next, stop) = iter.Pull(squares)
    println(s"iter.Pull: ${next()} ${next()}")
    stop()

    // Generic types: atomic.Pointer[T], sql.Null[T].
    val p = new atomic.Pointer[GoString]
    println(s"atomic.Pointer: ${p.Load().isNil}")
    val cell = GoPtr.alloc[GoString]
    cell.set(GoString("stored"))
    p.Store(cell)
    println(s"atomic.Pointer: ${p.Load().get}")
    val n = new sql.Null[GoInt]
    n.V = GoInt(42)
    n.Valid = true
    println(s"sql.Null: ${n.V} ${n.Valid} ${n.Value()}")

    // Type parameters only in results, or given explicitly.
    println(s"reflect.TypeFor: ${reflect.TypeFor[GoInt]().String()} ${reflect.TypeFor[GoSlice[GoString]]().String()}")
    try os.Open("/no/such/file")
    catch
      case e: GoException =>
        val (pathError, ok) = errors.AsType[fs.PathError](e.error)
        println(s"errors.AsType: $ok ${pathError.Op} ${pathError.Path}")

    val once = sync.OnceValue(() => { println("computing once"); GoInt(7) })
    println(s"sync.OnceValue: ${once().toLong + once().toLong}")
