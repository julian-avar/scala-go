package scalago.javalib.lang

/** java.lang.StackTraceElement: a frame of a Throwable's stack trace. A `lineNumber` of -1 means
 *  unknown, -2 a native method.
 */
final class StackTraceElement(
    private val classLoaderName: String,
    private val moduleName: String,
    private val moduleVersion: String,
    private val declaringClass: String,
    private val methodName: String,
    private val fileName: String,
    private val lineNumber: Int
) extends Object
    with java.io.Serializable:
  if declaringClass == null then throw new java.lang.NullPointerException("Declaring class is null")
  if methodName == null then throw new java.lang.NullPointerException("Method name is null")

  def this(declaringClass: String, methodName: String, fileName: String, lineNumber: Int) =
    this(null, null, null, declaringClass, methodName, fileName, lineNumber)

  def getClassLoaderName(): String = classLoaderName
  def getModuleName(): String = moduleName
  def getModuleVersion(): String = moduleVersion
  def getClassName(): String = declaringClass
  def getMethodName(): String = methodName
  def getFileName(): String = fileName
  def getLineNumber(): Int = lineNumber
  def isNativeMethod(): scala.Boolean = lineNumber == -2

  /** `Main$.deep(Main.scala:5)`, as on the JVM. */
  override def toString(): String =
    val sb = new java.lang.StringBuilder()
    if classLoaderName != null && !classLoaderName.isEmpty() then sb.append(classLoaderName).append('/')
    if moduleName != null && !moduleName.isEmpty() then
      sb.append(moduleName)
      if moduleVersion != null && !moduleVersion.isEmpty() then sb.append('@').append(moduleVersion)
    if sb.length() > 0 then sb.append('/')
    sb.append(declaringClass).append('.').append(methodName).append('(')
    if isNativeMethod() then sb.append("Native Method")
    else if fileName == null then sb.append("Unknown Source")
    else
      sb.append(fileName)
      if lineNumber >= 0 then sb.append(':').append(lineNumber)
    sb.append(')').toString()

  override def equals(obj: Any): scala.Boolean = obj match
    case e: StackTraceElement =>
      (e eq this) || (e.classLoaderName == classLoaderName && e.moduleName == moduleName &&
        e.moduleVersion == moduleVersion && e.declaringClass == declaringClass && e.lineNumber == lineNumber &&
        e.methodName == methodName && e.fileName == fileName)
    case _ => false

  override def hashCode(): Int =
    def hash(s: String): Int = if s == null then 0 else s.hashCode()
    var h = 31 * declaringClass.hashCode() + methodName.hashCode()
    h = 31 * h + hash(classLoaderName)
    h = 31 * h + hash(moduleName)
    h = 31 * h + hash(moduleVersion)
    h = 31 * h + hash(fileName)
    31 * h + lineNumber
