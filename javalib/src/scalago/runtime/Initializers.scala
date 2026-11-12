package scalago.runtime

/** The exceptions of failed class and object initializers, as the JVM makes them: the runtime
 *  (`rt.InitLock`) calls these methods.
 */
object Initializers:
  /** What the use that ran an initializer throws when the initializer threw `e`. */
  def thrown(e: Throwable): Throwable = e match
    case error: Error => error
    case _            => new ExceptionInInitializerError(e)

  /** What the runtime records when an initializer throws `e`: the cause of the
   *  `NoClassDefFoundError`s that later uses throw. As in the JDK, it stands for `e` (which it does
   *  not keep) and has its stack trace.
   */
  def recorded(e: Throwable): Throwable =
    val message = e.getMessage
    val error = new ExceptionInInitializerError("Exception " + e.getClass.getName + (if message == null then "" else ": " + message) +
      " [in thread \"" + Thread.currentThread().getName + "\"]")
    error.setStackTrace(e.getStackTrace)
    error

  /** What a use of class `className` throws after its initializer failed (`recorded`). */
  def noClassDefFound(className: String, recorded: Throwable): Throwable =
    new NoClassDefFoundError("Could not initialize class " + className).initCause(recorded)
