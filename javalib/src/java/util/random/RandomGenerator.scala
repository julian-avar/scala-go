// Adapted from Scala Native's javalib (Apache-2.0, see javalib/LICENSE-scala-native.md).
package java.util.random

/* Licenses and selected References:
 *
 * Most of the JEP 356 RandomGenerator work is covered by the Apache license.
 * This includes work ported from Scala.js and original work for Scala Native.
 *
 * Some useful references for fine and/or obscure points of algorithm
 * implementations and specific license information.
 *
 * - JEP 356: Enhanced Pseudo-Random Number Generators
 *     https://openjdk.org/jeps/356
 *
 * - Guy L. Steele Jr. and Sebastiano Vigna. LXM: Better splittable
 *   pseudorandom number generators (and almost as fast). Proc. ACM Program.
 *   Lang., 5(OOPSLA):1−31, 2021.
 *
 *     "This work is licensed under a Creative Commons Attribution 4.0
 *      international license © 2021 Copyright held by the owner/author(s)."
 *
 *      The terms of that license requires this notice that the code
 *      from that paper has been altered by being translated to Scala.
 *
 * - Sebastiano Vigna: "Generating uniform doubles in the unit interval""
 *     https://prng.di.unimi.it/
 *
 * - Sebastiano Vigna "DSI Utilities", source code.
 *     "The DSI utilities are free software distributed under either the
 *	GNU Lesser General Public License 2.1+ or the
 *	Apache Software License 2.0. "
 *
 *     Accessed under the Apache Software License.
 *     https://dsiutils.di.unimi.it/docs/it/unimi/dsi/util/package-summary.html
 *
 * - Sebastiano Vigna source code.
 *     https://github.com/vigna/dsiutils/tree/master/src/it/unimi/dsi/util
 *	   /XoRoShiRo128PlusRandomGenerator.java
 *	   /XoShiRo256PlusRandomGenerator.java
 *     Additional source there was studied.
 *
 *     Accessed under the Apache Software License.
 *     https://dsiutils.di.unimi.it/docs/it/unimi/dsi/util/package-summary.html
 *
 *     Code in this file modifies that code by converting it to Scala.
 *
 * - David Blackman and Sebastiano Vigna, 2019
 *
 *     https://prng.di.unimi.it/xoshiro256plusplus.c and similar studied
 *
 *    The authors state in the code:
 *      To the extent possible under law, the author has dedicated all
 *      copyright and related and neighboring rights to this software to the
 *      public domain worldwide.
 *
 *      Permission to use, copy, modify, and/or distribute this software for
 *      any purpose with or without fee is hereby granted.
 *
 *      THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL
 *      WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED
 *      WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE
 *      AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL
 *      DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA
 *      OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER
 *      TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR
 *      PERFORMANCE OF THIS SOFTWARE.
 *
 * - Guy L. Steele Jr., Doug Lea, and Christine H. Flood. 2014.
 *   "Fast Splittable Pseudorandom Number Generators"". In OOPSLA
 *   ’14: Proceedings of the 2014 ACM International Conference on
 *   Object-oriented Programming, Systems, Languages, and Applications
 *   (Portland, Oregon, USA) (OOPSLA ’14). ACM, New York, New York, USA,
 *   453-472. ISBN 9781450325851
 *
 *     https://gee.cs.oswego.edu/dl/papers/oopsla14.pdf
 *
 * - Apache Commons: many studied, particularly.
 *     https://commons.apache.org/proper/commons-rng/commons-rng-core/
 *       apidocs/org/apache/commons/rng/core/source32/
 *       L32X64Mix.html?is-external=true#%3Cinit%3E(int%5B%5D)
 *
 * - David Stafford, 2011-09-28
 *   "Better Bit Mixing - Improving on MurmurHash3's 64-bit Finalizer"
 *     http://zimbry.blogspot.com/2011/09/better-bit-mixing-improving-on.html
 */

/* Implementation Notes
 *  1) By convention, types scala.Double, scala.Float, and scala.Long are
 *     used where a Java primitive (AnyVal) is expected, at least on method
 *     arguments & return types.
 *
 *     This makes the code easier for, at least the author, to read and
 *     robust to changes in which files are imported. Let the compiler catch
 *     AnyVal/AnyRef mismatches.
 *
 *     Java does not have an Int type so an Int is always a scala.Int and
 *     need not be qualified.
 */

// scala-go: the generator factories are left out.

import java.util.Spliterator
import java.util.function.{DoubleConsumer, IntConsumer, LongConsumer}
import java.util.stream.{DoubleStream, IntStream, LongStream, StreamSupport}

trait RandomGenerator {

  def nextLong(): scala.Long // Abstract

  /* Default methods
   */

  /* Normally methods would be declared in roughly alphabetical order.
   * Here they are split into two sections, each in roughly alphabetical
   * order.
   *
   * RandomGenerator default methods which do not return a Stream were ported
   * from Scala.js for numerical accuracy and to ease future corrections and/or
   * re-ports.
   *
   * The intention is that section have minimal Scala Native changes.
   * Some types have been changed to ensure that Scala, not Java, types are
   * used.
   *
   * Scala.js does not implement RandomGenerator methods which return Streams.
   * The Scala Native work which does follows the Scala.js section.
   */

  import java.util.ScalaOps._

// Begin Ported from Scala.js commit: 9cb865f dated: 2025-03-16
  import scala.annotation.tailrec

  // Comments starting with `// >` are cited from the JavaDoc.
  def nextBoolean(): scala.Boolean =
    nextInt() < 0 // is the sign bit 1?

  def nextBytes(bytes: Array[Byte]): Unit = {
    val len = bytes.length // implicit NPE
    var i = 0

    for (_ <- 0 until (len >> 3)) {
      var rnd = nextLong()
      for (_ <- 0 until 8) {
        bytes(i) = rnd.toByte
        rnd >>>= 8
        i += 1
      }
    }

    if (i != len) {
      var rnd = nextLong()
      while (i != len) {
        bytes(i) = rnd.toByte
        rnd >>>= 8
        i += 1
      }
    }
  }

  def nextFloat(): scala.Float = {
    // > Uses the 24 high-order bits from a call to nextInt()
    val bits = nextInt() >>> (32 - 24)
    bits.toFloat * (1.0f / (1 << 24)) // lossless multiplication
  }

  def nextFloat(bound: scala.Float): scala.Float = {
    // false for NaN
    if (bound > 0 && bound != Float.PositiveInfinity)
      ensureBelowBound(nextFloatBoundedInternal(bound), bound)
    else
      throw new IllegalArgumentException(s"Illegal bound: $bound")
  }

  def nextFloat(origin: scala.Float, bound: scala.Float): scala.Float = {
    // `origin < bound` is false if either input is NaN
    if (origin != Float.NegativeInfinity && origin < bound &&
        bound != Float.PositiveInfinity) {
      val difference = bound - origin
      val result = if (difference != Float.PositiveInfinity) {
        // Easy case
        origin + nextFloatBoundedInternal(difference)
      } else {
        // Overflow: scale everything down by 0.5 then scale it back up by 2.0
        val halfOrigin = origin * 0.5f
        val halfBound = bound * 0.5f
        (halfOrigin + nextFloatBoundedInternal(halfBound - halfOrigin)) * 2.0f
      }

      ensureBelowBound(result, bound)
    } else {
      throw new IllegalArgumentException(s"Illegal bounds: [$origin, $bound)")
    }
  }

  @inline
  private def nextFloatBoundedInternal(bound: scala.Float): scala.Float =
    nextFloat() * bound

  @inline
  private def ensureBelowBound(
      value: scala.Float,
      bound: scala.Float
  ): scala.Float = {
    /* Based on documentation for Random.doubles to avoid issue #2144 and other
     * possible rounding up issues:
     * https://docs.oracle.com/javase/8/docs/api/java/util/Random.html#doubles-double-double-
     */
    if (value < bound) value
    else Math.nextDown(bound)
  }

  def nextDouble(): scala.Double = {
    // > Uses the 53 high-order bits from a call to nextLong()
    val bits = nextLong() >>> (64 - 53)
    bits.toDouble * (1.0 / (1L << 53)) // lossless multiplication
  }

  def nextDouble(bound: scala.Double): scala.Double = {
    // false for NaN
    if (bound > 0 && bound != Double.PositiveInfinity)
      ensureBelowBound(nextDoubleBoundedInternal(bound), bound)
    else
      throw new IllegalArgumentException(s"Illegal bound: $bound")
  }

  def nextDouble(origin: scala.Double, bound: scala.Double): scala.Double = {
    // `origin < bound` is false if either input is NaN
    if (origin != Double.NegativeInfinity && origin < bound &&
        bound != Double.PositiveInfinity) {
      val difference = bound - origin
      val result = if (difference != Double.PositiveInfinity) {
        // Easy case
        origin + nextDoubleBoundedInternal(difference)
      } else {
        // Overflow: scale everything down by 0.5 then scale it back up by 2.0
        val halfOrigin = origin * 0.5
        val halfBound = bound * 0.5
        (halfOrigin + nextDoubleBoundedInternal(halfBound - halfOrigin)) * 2.0
      }

      ensureBelowBound(result, bound)
    } else {
      throw new IllegalArgumentException(s"Illegal bounds: [$origin, $bound)")
    }
  }

  @inline
  private def nextDoubleBoundedInternal(bound: scala.Double): scala.Double =
    nextDouble() * bound

  @inline
  private def ensureBelowBound(
      value: scala.Double,
      bound: scala.Double
  ): Double = {
    /* Based on documentation for Random.doubles to avoid issue #2144 and other
     * possible rounding up issues:
     * https://docs.oracle.com/javase/8/docs/api/java/util/Random.html#doubles-double-double-
     */
    if (value < bound) value
    else Math.nextDown(bound)
  }

  def nextInt(): Int = {
    // > Uses the 32 high-order bits from a call to nextLong()
    (nextLong() >>> 32).toInt
  }

  /* The algorithms used in nextInt() with bounds were initially part of
   * ThreadLocalRandom. That implementation had been written by Doug Lea with
   * assistance from members of JCP JSR-166 Expert Group and released to the
   * public domain, as explained at
   * http://creativecommons.org/publicdomain/zero/1.0/
   */

  def nextInt(bound: Int): Int = {
    if (bound <= 0)
      throw new IllegalArgumentException(s"Illegal bound: $bound")

    nextIntBoundedInternal(bound)
  }

  def nextInt(origin: Int, bound: Int): Int = {
    if (bound <= origin)
      throw new IllegalArgumentException(s"Illegal bounds: [$origin, $bound)")

    val difference = bound - origin
    if (difference > 0 || difference == Int.MinValue) {
      /* Either the difference did not overflow, or it is the only power of 2
       * that overflows. In both cases, use the straightforward algorithm.
       * It works for `MinValue` because the code path for powers of 2
       * basically interprets the bound as unsigned.
       */
      origin + nextIntBoundedInternal(difference)
    } else {
      /* The interval size here is greater than Int.MaxValue,
       * so the loop will exit with a probability of at least 1/2.
       */
      @tailrec
      def loop(): Int = {
        val rnd = nextInt()
        if (rnd >= origin && rnd < bound)
          rnd
        else
          loop()
      }

      loop()
    }
  }

  private def nextIntBoundedInternal(bound: Int): Int = {
    // bound > 0 || bound == Int.MinValue

    if ((bound & -bound) == bound) { // i.e., bound is a power of 2
      // > If bound is a power of two then limiting is a simple masking operation.
      nextInt() & (bound - 1)
    } else {
      /* > Otherwise, the result is re-calculated by invoking nextInt() until
       * > the result is greater than or equal zero and less than bound.
       */

      /* Taken literally, that spec would lead to huge rejection rates for
       * small bounds.
       * Instead, we start from a random 31-bit (non-negative) int `rnd`, and
       * we compute `rnd % bound`.
       * In order to get a uniform distribution, we must reject and retry if
       * we get an `rnd` that is >= the largest int multiple of `bound`.
       */

      @tailrec
      def loop(): Int = {
        val rnd = nextInt() >>> 1
        val value = rnd % bound // candidate result

        // largest multiple of bound that is <= rnd
        val multiple = rnd - value

        // if multiple + bound overflows
        if (multiple + bound < 0) {
          /* then `multiple` is the largest multiple of bound, and
           * `rnd >= multiple`, so we must retry.
           */
          loop()
        } else {
          value
        }
      }

      loop()
    }
  }

  /* The algorithms for nextLong() with bounds are copy-pasted from the ones
   * for nextInt(), mutatis mutandis.
   */

  def nextLong(bound: scala.Long): scala.Long = {
    if (bound <= 0)
      throw new IllegalArgumentException(s"Illegal bound: $bound")

    nextLongBoundedInternal(bound)
  }

  def nextLong(origin: scala.Long, bound: scala.Long): scala.Long = {
    if (bound <= origin)
      throw new IllegalArgumentException(s"Illegal bounds: [$origin, $bound)")

    val difference = bound - origin
    if (difference > 0 || difference == Long.MinValue) {
      /* Either the difference did not overflow, or it is the only power of 2
       * that overflows. In both cases, use the straightforward algorithm.
       * It works for `MinValue` because the code path for powers of 2
       * basically interprets the bound as unsigned.
       */
      origin + nextLongBoundedInternal(difference)
    } else {
      /* The interval size here is greater than Long.MaxValue,
       * so the loop will exit with a probability of at least 1/2.
       */
      @tailrec
      def loop(): scala.Long = {
        val rnd = nextLong()
        if (rnd >= origin && rnd < bound)
          rnd
        else
          loop()
      }

      loop()
    }
  }

  private def nextLongBoundedInternal(bound: scala.Long): scala.Long = {
    // bound > 0 || bound == Long.MinValue

    if ((bound & -bound) == bound) { // i.e., bound is a power of 2
      // > If bound is a power of two then limiting is a simple masking operation.
      nextLong() & (bound - 1L)
    } else {
      /* > Otherwise, the result is re-calculated by invoking nextLong() until
       * > the result is greater than or equal zero and less than bound.
       */

      /* Taken literally, that spec would lead to huge rejection rates for
       * small bounds.
       * Instead, we start from a random 63-bit (non-negative) int `rnd`, and
       * we compute `rnd % bound`.
       * In order to get a uniform distribution, we must reject and retry if
       * we get an `rnd` that is >= the largest int multiple of `bound`.
       */

      @tailrec
      def loop(): scala.Long = {
        val rnd = nextLong() >>> 1
        val value = rnd % bound // candidate result

        // largest multiple of bound that is <= rnd
        val multiple = rnd - value

        // if multiple + bound overflows
        if (multiple + bound < 0L) {
          /* then `multiple` is the largest multiple of bound, and
           * `rnd >= multiple`, so we must retry.
           */
          loop()
        } else {
          value
        }
      }

      loop()
    }
  }

// End Ported from Scala.js commit: 9cb865f dated: 2025-03-16

  def isDeprecated(): Boolean = false

  /** This implementation uses inverse transform sampling.
   *
   *  Java 17 through 23 state the "implementation uses McFarland's fast
   *  modified ziggurat algorithm".
   */

  def nextExponential(): scala.Double =
    -Math.log(nextDouble())

  /** This implementation uses the polar Box-Muller algorithm from Random.scala
   *  and concurrent.ThreadLocalRandom.scala
   *
   *  The Java 17 through 23 docs say the "implementation uses McFarland's fast
   *  modified ziggurat algorithm".
   *
   *  Eventually both nextExponential() and this method should use the ziggurat
   *  algorithm. Random.scala is documented as continuing to use its historical
   *  Box-Muller.
   *
   *  Providing the ziggurate algorithm here is left as an exercise for the
   *  reader.
   */

  def nextGaussian(): scala.Double = {
    var nextNextGaussian: Double = 0.0
    var haveNextNextGaussian: Boolean = false

    /* The Box-Muller algorithm produces two random numbers at once. We save
     * the second one in `nextNextGaussian` to be used by the next call to
     * nextGaussian().
     *
     * See http://www.protonfish.com/jslib/boxmuller.shtml
     */

    if (haveNextNextGaussian) {
      haveNextNextGaussian = false
      return nextNextGaussian
    }

    var x, y, rds: Double = 0.0

    /* Get two random numbers from -1.0 to 1.0.
     * If the radius is zero or greater than 1, throw them out and pick two new
     * ones.
     * Rejection sampling throws away about 20% of the pairs.
     */
    while ({
      x = nextDouble() * 2.0 - 1
      y = nextDouble() * 2.0 - 1
      rds = x * x + y * y
      rds == 0.0 || rds > 1
    }) ()

    val c = Math.sqrt(-2.0 * Math.log(rds) / rds)

    // Save y*c for next time
    nextNextGaussian = y * c
    haveNextNextGaussian = true

    // And return x*c
    x * c
  }

  def nextGaussian(mean: scala.Double, stddev: scala.Double): scala.Double =
    mean + (stddev * nextGaussian())

  /* Streams, as the JDK's: each element is nextInt() (nextLong(), nextDouble()) or, with bounds,
   * nextInt(origin, bound) (...), drawn as the element is needed.
   */

  def ints(): IntStream = RandomGenerator.ints(this, Long.MaxValue, 0, 0, bounded = false)

  def ints(streamSize: scala.Long): IntStream = {
    RandomGenerator.checkStreamSize(streamSize)
    RandomGenerator.ints(this, streamSize, 0, 0, bounded = false)
  }

  def ints(randomNumberOrigin: Int, randomNumberBound: Int): IntStream = {
    RandomGenerator.checkRange(randomNumberOrigin < randomNumberBound)
    RandomGenerator.ints(this, Long.MaxValue, randomNumberOrigin, randomNumberBound, bounded = true)
  }

  def ints(streamSize: scala.Long, randomNumberOrigin: Int, randomNumberBound: Int): IntStream = {
    RandomGenerator.checkStreamSize(streamSize)
    RandomGenerator.checkRange(randomNumberOrigin < randomNumberBound)
    RandomGenerator.ints(this, streamSize, randomNumberOrigin, randomNumberBound, bounded = true)
  }

  def longs(): LongStream = RandomGenerator.longs(this, Long.MaxValue, 0L, 0L, bounded = false)

  def longs(streamSize: scala.Long): LongStream = {
    RandomGenerator.checkStreamSize(streamSize)
    RandomGenerator.longs(this, streamSize, 0L, 0L, bounded = false)
  }

  def longs(randomNumberOrigin: scala.Long, randomNumberBound: scala.Long): LongStream = {
    RandomGenerator.checkRange(randomNumberOrigin < randomNumberBound)
    RandomGenerator.longs(this, Long.MaxValue, randomNumberOrigin, randomNumberBound, bounded = true)
  }

  def longs(streamSize: scala.Long, randomNumberOrigin: scala.Long, randomNumberBound: scala.Long): LongStream = {
    RandomGenerator.checkStreamSize(streamSize)
    RandomGenerator.checkRange(randomNumberOrigin < randomNumberBound)
    RandomGenerator.longs(this, streamSize, randomNumberOrigin, randomNumberBound, bounded = true)
  }

  def doubles(): DoubleStream = RandomGenerator.doubles(this, Long.MaxValue, 0.0, 0.0, bounded = false)

  def doubles(streamSize: scala.Long): DoubleStream = {
    RandomGenerator.checkStreamSize(streamSize)
    RandomGenerator.doubles(this, streamSize, 0.0, 0.0, bounded = false)
  }

  def doubles(randomNumberOrigin: scala.Double, randomNumberBound: scala.Double): DoubleStream = {
    RandomGenerator.checkRange(randomNumberOrigin, randomNumberBound)
    RandomGenerator.doubles(this, Long.MaxValue, randomNumberOrigin, randomNumberBound, bounded = true)
  }

  def doubles(
      streamSize: scala.Long,
      randomNumberOrigin: scala.Double,
      randomNumberBound: scala.Double
  ): DoubleStream = {
    RandomGenerator.checkStreamSize(streamSize)
    RandomGenerator.checkRange(randomNumberOrigin, randomNumberBound)
    RandomGenerator.doubles(this, streamSize, randomNumberOrigin, randomNumberBound, bounded = true)
  }
}

object RandomGenerator {
  private def checkStreamSize(streamSize: scala.Long): Unit =
    if (streamSize < 0L) throw new IllegalArgumentException("size must be non-negative")

  private def checkRange(ok: Boolean): Unit =
    if (!ok) throw new IllegalArgumentException("bound must be greater than origin")

  private def checkRange(origin: scala.Double, bound: scala.Double): Unit =
    checkRange(Double.NegativeInfinity < origin && origin < bound && bound < Double.PositiveInfinity)

  private final val StreamCharacteristics =
    Spliterator.SIZED | Spliterator.SUBSIZED | Spliterator.NONNULL | Spliterator.IMMUTABLE

  /** `size` random numbers, generated as they are needed. */
  private abstract class RandomSpliterator(size: scala.Long) {
    protected var index = 0L
    def estimateSize(): scala.Long = size - index
    def characteristics(): Int = StreamCharacteristics
  }

  private def ints(rng: RandomGenerator, size: scala.Long, origin: Int, bound: Int, bounded: Boolean): IntStream = {
    val spl = new RandomSpliterator(size) with Spliterator.OfInt {
      def trySplit(): Spliterator.OfInt = null
      def tryAdvance(action: IntConsumer): Boolean = {
        java.util.Objects.requireNonNull(action)
        if (index < size) {
          action.accept(if (bounded) rng.nextInt(origin, bound) else rng.nextInt())
          index += 1
          true
        } else false
      }
    }
    StreamSupport.intStream(spl, false)
  }

  private def longs(rng: RandomGenerator, size: scala.Long, origin: scala.Long, bound: scala.Long,
      bounded: Boolean): LongStream = {
    val spl = new RandomSpliterator(size) with Spliterator.OfLong {
      def trySplit(): Spliterator.OfLong = null
      def tryAdvance(action: LongConsumer): Boolean = {
        java.util.Objects.requireNonNull(action)
        if (index < size) {
          action.accept(if (bounded) rng.nextLong(origin, bound) else rng.nextLong())
          index += 1
          true
        } else false
      }
    }
    StreamSupport.longStream(spl, false)
  }

  private def doubles(rng: RandomGenerator, size: scala.Long, origin: scala.Double, bound: scala.Double,
      bounded: Boolean): DoubleStream = {
    val spl = new RandomSpliterator(size) with Spliterator.OfDouble {
      def trySplit(): Spliterator.OfDouble = null
      def tryAdvance(action: DoubleConsumer): Boolean = {
        java.util.Objects.requireNonNull(action)
        if (index < size) {
          action.accept(if (bounded) rng.nextDouble(origin, bound) else rng.nextDouble())
          index += 1
          true
        } else false
      }
    }
    StreamSupport.doubleStream(spl, false)
  }

  trait SplittableGenerator extends RandomGenerator {
    def split(): RandomGenerator.SplittableGenerator

    def split(
        source: RandomGenerator.SplittableGenerator
    ): RandomGenerator.SplittableGenerator = source.split()
  }
}
