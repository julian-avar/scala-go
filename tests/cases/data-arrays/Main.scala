import java.util.Arrays

// Arrays (docs/_docs/spec/data.md, "Arrays"): primitive arrays of every type, arrays of
// references, default values, bounds checks, multi-dimensional and jagged arrays, clone,
// System.arraycopy (overlapping, ArrayStoreException between primitive types), covariance,
// java.util.Arrays on primitive arrays, varargs and patterns. Not here: the element type of
// arrays of references (limits-runtime, limits-data), negative indices (known-bugs-misc).
case class Pt(x: Int, y: Int)

object Main:
  def p(what: String)(body: => Any): Unit =
    val r =
      try String.valueOf(body)
      catch case e: Throwable => s"throws ${e.getClass.getName}"
    println(s"$what: $r")
  def sum(xs: Int*): Int = xs.sum
  def i(x: Int): Int = x

  def main(args: Array[String]): Unit =
    // Default values and lengths of every element type
    p("defaults")(List(new Array[Int](2).toList, new Array[Long](1).toList, new Array[Double](1).toList, new Array[Float](1).toList,
      new Array[Boolean](2).toList, new Array[Byte](1).toList, new Array[Short](1).toList, new Array[Char](1).map(_.toInt).toList,
      new Array[String](2).toList, new Array[Pt](1).toList, new Array[Any](1).toList, new Array[Array[Int]](1).toList))
    p("Array[Unit]")(new Array[Unit](2).toList)
    p("empty arrays")(s"${new Array[Int](0).length} ${Array.empty[String].length} ${Array[Double]().isEmpty}")
    p("lengths")(s"${Array(1, 2, 3).length} ${Array("a").length} ${Array.fill(1000)(0).length}")

    // Element stores and loads with edge values, per type
    val bs = new Array[Byte](2); bs(0) = Byte.MinValue; bs(1) = (bs(0) - 1).toByte
    val ss = new Array[Short](2); ss(0) = Short.MaxValue; ss(1) = (ss(0) + 1).toShort
    val cs = new Array[Char](2); cs(0) = '\uffff'; cs(1) = (cs(0) + 1).toChar
    val ls = Array(Long.MaxValue, Long.MinValue); ls(0) += 1
    val fs = Array(Float.MaxValue, Float.NaN, -0.0f); fs(0) *= 2
    val ds = Array(Double.MinPositiveValue, Double.NaN); ds(0) /= 2
    val zs = Array(true, false); zs(1) = !zs(1)
    p("stores")(s"${bs.toList} ${ss.toList} ${cs.map(_.toInt).toList} ${ls.toList} ${fs.toList} ${ds.toList} ${zs.toList}")

    // Bounds checks
    val a = Array(10, 20, 30)
    p("read past the end")(a(i(3)))
    p("write past the end")({ a(i(3)) = 1; a.toList })
    p("message")(try a(i(5)) catch case e: ArrayIndexOutOfBoundsException => e.getMessage)
    p("empty array index 0")(new Array[String](0)(i(0)))
    p("long array past the end")(Array(1L)(i(1)))
    p("reference array past the end")(Array("a")(i(1)))
    p("2D inner past the end")(Array.ofDim[Int](2, 2)(1)(i(2)))
    p("negative size")(new Array[Int](i(-1)))
    p("null array")({ val n: Array[Int] = null; n.length })

    // Multi-dimensional and jagged arrays
    val grid = Array.ofDim[Int](3, 4)
    for r <- 0 until 3; c <- 0 until 4 do grid(r)(c) = r * 10 + c
    p("ofDim 3x4")(grid.map(_.mkString(",")).mkString(" / "))
    p("ofDim shapes")(s"${grid.length} ${grid(0).length} ${Array.ofDim[Double](2, 3, 4)(1)(2).length} ${Array.ofDim[String](2, 2)(1)(1)}")
    val jagged = Array(Array(1), Array(1, 2), Array[Int]())
    jagged(2) = Array(7, 8, 9)
    p("jagged")(jagged.map(_.length).toList)
    p("rows are shared references")({ val row = grid(1); row(0) = -1; grid(1)(0) })
    p("tabulate")(Array.tabulate(3, 2)((r, c) => r + c).map(_.toList).toList)
    p("deepToString")(Arrays.deepToString(Array[AnyRef](Array(1, 2), Array("a"), Array(Array(true)))))
    p("deepEquals")(Arrays.deepEquals(Array[AnyRef](Array(1, 2)), Array[AnyRef](Array(1, 2))))

    // clone and copies are independent
    val orig = Array(1, 2, 3)
    val cl = orig.clone()
    cl(0) = 99
    p("clone")(s"${orig.toList} ${cl.toList} ${orig eq cl}")
    val nested = Array(Array(1), Array(2))
    val shallow = nested.clone()
    shallow(0)(0) = 42
    shallow(1) = Array(0)
    p("clone is shallow")(s"${nested(0)(0)} ${nested(1)(0)}")
    p("clone of each type")(s"${Array(1L).clone().toList} ${Array('c').clone().toList} ${Array(true).clone().toList} ${Array("s").clone().toList} ${Array(1.5f).clone().toList}")
    p("copyOf grows and truncates")(s"${Arrays.copyOf(orig, 5).toList} ${Arrays.copyOf(orig, 1).toList} ${Arrays.copyOf(Array(true), 2).toList}")
    p("copyOfRange")(Arrays.copyOfRange(Array('a', 'b', 'c', 'd'), 1, 3).mkString)
    p("Array.copyOf (Scala)")(Array.copyOf(Array(1.5), 3).toList)

    // System.arraycopy
    val src = Array(1, 2, 3, 4, 5)
    val dst = new Array[Int](5)
    System.arraycopy(src, 1, dst, 0, 3)
    p("arraycopy")(dst.toList)
    val ov1 = Array(1, 2, 3, 4, 5)
    System.arraycopy(ov1, 0, ov1, 1, 4)
    val ov2 = Array(1, 2, 3, 4, 5)
    System.arraycopy(ov2, 1, ov2, 0, 4)
    p("arraycopy overlapping")(s"${ov1.toList} ${ov2.toList}")
    val strs = Array("a", "b", "c")
    val objs = new Array[AnyRef](4)
    System.arraycopy(strs, 0, objs, 1, 3)
    p("arraycopy String[] to Object[]")(objs.toList)
    p("arraycopy int[] to long[]")(System.arraycopy(Array(1), 0, new Array[Long](1), 0, 1))
    p("arraycopy int[] to Object[]")(System.arraycopy(Array(1), 0, new Array[AnyRef](1), 0, 1))
    p("arraycopy out of bounds")(System.arraycopy(src, 3, dst, 0, 5))
    p("arraycopy negative length")(System.arraycopy(src, 0, dst, 0, -1))
    p("arraycopy null")(System.arraycopy(null, 0, dst, 0, 1))
    p("arraycopy zero length at end")({ System.arraycopy(src, 5, dst, 5, 0); "ok" })

    // Covariance of arrays of references
    val strings: Array[String] = Array("x", "y")
    val asObjects: Array[AnyRef] = strings.asInstanceOf[Array[AnyRef]]
    p("covariant view shares storage")({ asObjects(0) = "z"; strings(0) })
    p("Array[String] is Array[AnyRef], Array[Int] is not")(s"${(strings: Any).isInstanceOf[Array[AnyRef]]} ${(Array(1): Any).isInstanceOf[Array[AnyRef]]} ${(Array(1): Any).isInstanceOf[Array[Long]]}")
    p("arrays are Objects")((Array(1): Any).isInstanceOf[AnyRef])

    // java.util.Arrays on primitive arrays
    val unsorted = Array(5, -1, 3, Int.MinValue, 0)
    Arrays.sort(unsorted)
    p("sort ints")(unsorted.toList)
    p("binarySearch")(s"${Arrays.binarySearch(unsorted, 3)} ${Arrays.binarySearch(unsorted, 4)} ${Arrays.binarySearch(unsorted, Int.MaxValue)}")
    val chars = "hello".toCharArray
    Arrays.sort(chars)
    p("sort chars")(chars.mkString)
    val longs = Array(3L, Long.MinValue, -1L)
    Arrays.sort(longs)
    p("sort longs")(longs.toList)
    val part = Array(9, 8, 7, 6, 5)
    Arrays.sort(part, 1, 4)
    p("sort range")(part.toList)
    val filled = new Array[Double](4)
    Arrays.fill(filled, 2.5)
    Arrays.fill(filled, 1, 3, -1.0)
    p("fill")(filled.toList)
    p("equals, hashCode")(s"${Arrays.equals(Array(1, 2), Array(1, 2))} ${Arrays.equals(Array(1.0), Array(-0.0))} ${Arrays.equals(Array(Double.NaN), Array(Double.NaN))} ${Arrays.hashCode(Array(1, 2))} ${Arrays.hashCode(Array(true, false))} ${Arrays.hashCode(null: Array[Int])}")
    p("toString")(s"${Arrays.toString(Array(1, 2))} ${Arrays.toString(Array('a', 'b'))} ${Arrays.toString(Array(1.5f))} ${Arrays.toString(Array[AnyRef]("s", null))} ${Arrays.toString(null: Array[Int])} ${Arrays.toString(new Array[Long](0))}")
    p("asList on references")(Arrays.asList("a", "b"))
    p("stream")(Arrays.stream(Array(1, 2, 3)).map(_ * 2).sum)

    // Scala operations
    val xs = Array(3, 1, 2)
    p("sorted, reverse, map")(s"${xs.sorted.toList} ${xs.reverse.toList} ${xs.map(_ * 2).toList} ${xs.toList}")
    p("primitive result types")(s"${xs.map(_.toDouble).sum} ${xs.map(_.toString).mkString} ${xs.map(_ > 1).count(identity)}")
    p("sameElements, ==")(s"${xs.sameElements(Array(3, 1, 2))} ${xs == Array(3, 1, 2)} ${xs.toSeq == Seq(3, 1, 2)}")
    p("++, :+, +:")((0 +: xs :+ 4).toList ++ (xs ++ Array(9)).toList)
    p("slice, take, drop")(s"${xs.slice(1, 3).toList} ${xs.take(5).toList} ${xs.drop(5).toList}")
    p("indexOf, contains")(s"${xs.indexOf(2)} ${xs.contains(7)} ${Array(Double.NaN).contains(Double.NaN)} ${Array(-0.0).contains(0.0)}")
    p("zip, unzip")({ val (l, r) = Array((1, 'a'), (2, 'b')).unzip; s"${l.toList} ${r.toList} ${l.zip(r).toList}" })
    p("flatten")(Array(Array(1, 2), Array(3)).flatten.toList)
    p("update via varargs")(sum(xs*))
    p("Array pattern")(xs match { case Array(a, b, rest*) => s"$a $b ${rest.toList}"; case _ => "?" })
    p("Array of case classes")({ val pts = Array(Pt(2, 1), Pt(1, 2)); pts.sortBy(_.x).toList })
    p("Array.range, iterate, fill")(s"${Array.range(0, 10, 3).toList} ${Array.iterate(1, 4)(_ * 3).toList} ${Array.fill(2)("x").toList}")
    p("String toCharArray round trip")(new String("h\u00e9!".toCharArray.reverse))
    p("Byte array from String")("AZ".getBytes("UTF-8").toList)
    p("Array.equals is identity")({ val q = Array(1); q.equals(q) && !q.equals(Array(1)) })
    p("array hashCode stable")({ val q = Array(1); q.hashCode == q.hashCode })
