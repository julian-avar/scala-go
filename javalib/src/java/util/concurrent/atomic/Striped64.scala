// Adapted from Scala Native's javalib (Apache-2.0, see javalib/LICENSE-scala-native.md).
// Ported from JSR 166 revision 1.28

/*
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */
package java.util.concurrent.atomic

import scalago.runtime.VolatileArray

import scalago.runtime.Intrinsics

import java.lang.Double._
import java.util.Arrays
import java.util.concurrent.ThreadLocalRandom
import java.util.function.{DoubleBinaryOperator, LongBinaryOperator}

@SuppressWarnings(Array("serial"))
private[atomic] object Striped64 {
  private[atomic] final class Cell private[atomic] (
      @volatile private[atomic] var value: Long
  ) {
    // Padding: a cache line of its own, as @Contended gives the JDK's cells.
    private var pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7 = 0L

    private[atomic] final def cas(cmp: Long, `val`: Long) =
      Intrinsics.compareAndSet(this.value, cmp, `val`)

    private[atomic] final def reset(): Unit =
      this.value = 0L

    private[atomic] final def reset(identity: Long): Unit =
      this.value = identity

    private[atomic] final def getAndSet(`val`: Long) =
      Intrinsics.getAndSet(this.value, `val`)
  }

  private[atomic] val NCPU: Int = Runtime.getRuntime().availableProcessors()

  private[atomic] def getProbe(): Int = ThreadLocalRandom.getProbe()

  private[atomic] def advanceProbe(probe: Int): Int = ThreadLocalRandom.advanceProbe(probe)

  private def _apply(fn: DoubleBinaryOperator, v: Long, x: Double) = {
    var d = longBitsToDouble(v)
    d =
      if (fn == null) d + x
      else fn.applyAsDouble(d, x)
    doubleToRawLongBits(d)
  }
}

@SuppressWarnings(Array("serial"))
private[atomic] abstract class Striped64 private[atomic] () extends Number {
  import Striped64._

  @transient @volatile private[atomic] var cells: VolatileArray[Striped64.Cell] = scala.compiletime.uninitialized

  @transient @volatile private[atomic] var base: Long = 0L

  @transient @volatile private[atomic] var cellsBusy: Int = 0

  private[atomic] final def casBase(cmp: Long, `val`: Long) =
    Intrinsics.compareAndSet(this.base, cmp, `val`)

  private[atomic] final def getAndSetBase(`val`: Long) =
    Intrinsics.getAndSet(this.base, `val`)

  private[atomic] final def casCellsBusy() =
    Intrinsics.compareAndSet(this.cellsBusy, 0, 1)

  private[atomic] final def longAccumulate(
      x: Long,
      fn: LongBinaryOperator,
      _wasUncontended: Boolean,
      _index: Int
  ): Unit = {
    var index = _index
    var wasUncontended = _wasUncontended

    if (index == 0) {
      ThreadLocalRandom.current() // force initialization
      index = getProbe()
      wasUncontended = true
    }
    var collide = false
    while (true) {
      var cs: VolatileArray[Cell] = null
      var c: Cell = null
      var n: Int = 0
      var v: Long = 0

      if (cells != null && { n = cells.length; n > 0 }) {
        c = cells((n - 1) & index)
        if (c == null) {
          var continue = false
          if (cellsBusy == 0) {
            val r = new Cell(x)
            if (cellsBusy == 0 && casCellsBusy()) {
              try {
                var rs: VolatileArray[Cell] = null
                var m: Int = 0
                var j: Int = 0
                rs = cells
                if (rs != null && { m = rs.length; m > 0 } &&
                    rs({ j = (m - 1) & index; j }) == null) {
                  rs(j) = r
                  return
                }
              } finally {
                cellsBusy = 0
              }
              continue = true
            }
          }
          if (!continue) collide = false
        } else if (!wasUncontended) { // CAS already known to fail
          wasUncontended = true // Continue after rehash
        } else if (c.cas(
              { v = c.value; v },
              if (fn == null) v + x else fn.applyAsLong(v, x)
            )) {
          return
        } else if (n >= NCPU || cells != cs)
          collide = false // At max size or stale
        else if (!collide) collide = true
        else if (cellsBusy == 0 && casCellsBusy()) {
          try {
            if (cells == cs) { // Expand table unless stale
              cells = cs.copyOf(n << 1)
            }
          } finally {
            cellsBusy = 0
          }
          collide = false
          return
        }
        index = advanceProbe(index)
      } else if (cellsBusy == 0 && cells == cs && casCellsBusy()) {
        try { // Initialize table
          if (cells == cs) {
            val rs = new VolatileArray[Cell](2)
            rs(index & 1) = new Cell(x)
            cells = rs
            return
          }
        } finally cellsBusy = 0
      } else if (casBase( // Fall back on using base
            { v = base; v },
            if (fn == null) v + x else fn.applyAsLong(v, x)
          )) return
    }
  }

  private[atomic] final def doubleAccumulate(
      x: Double,
      fn: DoubleBinaryOperator,
      _wasUncontended: Boolean,
      _index: Int
  ): Unit = {
    var index = _index
    var wasUncontended = _wasUncontended

    if (index == 0) {
      ThreadLocalRandom.current() // force initialization
      index = getProbe()
      wasUncontended = true
    }
    var collide = false

    while (true) {
      var cs: VolatileArray[Cell] = null
      var c: Cell = null
      var n: Int = 0
      var v: Long = 0

      if (cells != null && { n = cells.length; n > 0 }) {
        c = cells((n - 1) & index)
        if (c == null) {
          var continue = false
          if (cellsBusy == 0) {
            val r = new Cell(doubleToRawLongBits(x))
            if (cellsBusy == 0 && casCellsBusy()) {
              try {
                var rs: VolatileArray[Cell] = null
                var m: Int = 0
                var j: Int = 0
                rs = cells
                if (rs != null && { m = rs.length; m > 0 } &&
                    rs({ j = (m - 1) & index; j }) == null) {
                  rs(j) = r
                  return
                }
              } finally {
                cellsBusy = 0
              }
              continue = true
            }
          }
          if (!continue) collide = false
        } else if (!wasUncontended) { // CAS already known to fail
          wasUncontended = true // Continue after rehash
        } else if (c.cas({ v = c.value; v }, Striped64._apply(fn, v, x)))
          return
        else if (n >= NCPU || cells != cs)
          collide = false // At max size or stale
        else if (!collide) collide = true
        else if (cellsBusy == 0 && casCellsBusy()) {
          try {
            if (cells == cs) { // Expand table unless stale
              cells = cs.copyOf(n << 1)
            }
          } finally {
            cellsBusy = 0
          }
          collide = false
          return
        }
        index = advanceProbe(index)
      } else if (cellsBusy == 0 && cells == cs && casCellsBusy()) {
        try { // Initialize table
          if (cells == cs) {
            val rs = new VolatileArray[Cell](2)
            rs(index & 1) = new Cell(doubleToLongBits(x))
            cells = rs
            return
          }
        } finally cellsBusy = 0
      } else if (casBase( // Fall back on using base
            { v = base; v },
            Striped64._apply(fn, v, x)
          )) return
    }
  }
}
