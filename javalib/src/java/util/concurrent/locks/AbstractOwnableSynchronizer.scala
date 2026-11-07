// Adapted from Scala Native's javalib (Apache-2.0, see javalib/LICENSE-scala-native.md).
/*
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent
package locks

abstract class AbstractOwnableSynchronizer protected ()
    extends java.io.Serializable {

  // Volatile: other threads read it (racily on the JVM, which Go does not allow for references).
  @volatile private var exclusiveOwnerThread: Thread = _

  protected final def setExclusiveOwnerThread(t: Thread): Unit =
    exclusiveOwnerThread = t

  protected final def getExclusiveOwnerThread(): Thread =
    exclusiveOwnerThread
}
