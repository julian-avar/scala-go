import scalago.go.*

@GoImport("strings")
object strings:
  def Repeat(s: String, count: Int): String = native
  @GoName("Fields") def goFields(s: String): GoSlice[GoString] = native
  def ToUpper(s: GoString): GoString = native
  def Contains(s: String, substr: String): Boolean = native
  def Fields(s: String): Array[String] = native
  def FieldsFunc(s: String, f: Int => Boolean): Array[String] = native
  def Join(elems: Array[String], sep: String): String = native
  def NewReplacer(oldnew: String*): Replacer = native
  def Map(mapping: Int => Int, s: String): String = native

  class Builder:
    def WriteString(s: String): Int = native
    def Len(): Int = native
    def String(): String = native

  class Replacer:
    def Replace(s: String): String = native

@GoImport("strconv")
object strconv:
  def Atoi(s: String): Int = native
  def ParseBool(str: String): (Boolean, GoError) = native
  def Itoa(i: Int): String = native
  def Quote(s: String): String = native

@GoImport("math")
object math:
  def Pi: Double = native
  def MaxInt32: Int = native
  def Sqrt(x: Double): Double = native
  def Float64bits(f: Double): Long = native

@GoImport("errors")
object errors:
  def New(text: String): GoError = native

@GoImport("io")
object io:
  trait Writer:
    def Write(p: Array[Byte]): Int

@GoImport("fmt")
object fmt:
  def Println(args: Any*): Unit = native
  def Sprintf(format: String, args: Any*): String = native
  def Fprintf(w: io.Writer, format: String, args: Any*): Int = native
  def Errorf(format: String, args: Any*): GoError = native

@GoImport("time")
object time:
  def Second: Duration = native
  @GoName("Duration")
  class Dur:
    def Seconds(): Double = native
    def String(): String = native
  type Duration = Dur

@GoImport("bytes")
object bytes:
  class Buffer extends io.Writer:
    def Write(p: Array[Byte]): Int = native
    def WriteString(s: String): Int = native
    def String(): String = native
    def Len(): Int = native
  def ToUpper(s: Array[Byte]): Array[Byte] = native

@GoImport("net/url")
object url:
  def Parse(rawURL: String): URL = native
  class URL:
    def Scheme: String = native
    def Host: String = native
    def Path: String = native
    def Path_=(p: String): Unit = native
    def Hostname(): String = native
    def Query(): Values = native
    @GoName("String") def show(): String = native
  class Values:
    def Get(key: String): String = native

// Generic Go functions, through monomorphic facades (Go infers the type arguments).
@GoImport("slices")
object slices:
  def Max(xs: GoSlice[GoInt]): GoInt = native
  def Contains(xs: GoSlice[GoString], v: GoString): Boolean = native
  def Sort(xs: GoSlice[Long]): Unit = native
  @GoName("Index") def indexOf(xs: GoSlice[GoString], v: GoString): Int = native

@GoImport("sort")
object sort:
  def Ints(xs: GoSlice[GoInt]): Unit = native
