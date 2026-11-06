package scalago.linker

import scala.collection.mutable

import scalago.ir.*

/** Go implementations of JDK (and a few Scala library) methods.
 *
 *  Natives take precedence over IR, so they can also replace library methods whose IR would
 *  drag in too much (e.g. `Predef.println`, which goes through `Console` and thread locals).
 *  Classes with real state or hierarchy (exceptions, StringBuilder, ...) are written in Scala in
 *  `javalib/` instead.
 */
object Natives:
  enum Native:
    /** Instance method: Go code from the receiver and argument expressions. */
    case Instance(gen: (String, List[String]) => String)
    /** Static method, or method of a module whose receiver need not be evaluated. */
    case Static(gen: List[String] => String)

  import Native.*

  private val methods = mutable.HashMap.empty[(ClassName, MethodName), Native]
  private val staticFields = mutable.HashMap.empty[(ClassName, String), String]

  def lookup(cls: ClassName, m: MethodName): Option[Native] = methods.get((cls, m))

  /** Methods of java.lang.Object, valid on any receiver. */
  def objectMethod(m: MethodName): Option[Native] = methods.get((ClassName.Object, m))

  def staticField(cls: ClassName, field: String): Option[String] = staticFields.get((cls, field))

  /** Constructors that need no code (`java.lang.Object.<init>`). */
  def isNoOpConstructor(cls: ClassName, method: MethodName): Boolean =
    cls == ClassName.Object && method.isConstructor && method.params.isEmpty

  // ---------------------------------------------------------------------------------------------
  // Table construction
  // ---------------------------------------------------------------------------------------------

  /** Parses `name(params)result` with JVM descriptor codes, using `.` in class names. */
  private[linker] def parseMethod(desc: String): MethodName =
    val open = desc.indexOf('(')
    val close = desc.indexOf(')')
    def types(s: String): List[Type] =
      if s.isEmpty then Nil
      else
        val (t, rest) = parseType(s)
        t :: types(rest)
    def parseType(s: String): (Type, String) = s.head match
      case 'V' => (Type.Void, s.tail)
      case 'Z' => (Type.Boolean, s.tail)
      case 'C' => (Type.Char, s.tail)
      case 'B' => (Type.Byte, s.tail)
      case 'S' => (Type.Short, s.tail)
      case 'I' => (Type.Int, s.tail)
      case 'J' => (Type.Long, s.tail)
      case 'F' => (Type.Float, s.tail)
      case 'D' => (Type.Double, s.tail)
      case '[' =>
        val (elem, rest) = parseType(s.tail)
        (Type.Array(elem), rest)
      case 'L' =>
        val end = s.indexOf(';')
        (Type.Class(s.substring(1, end)), s.substring(end + 1))
      case c => throw IllegalArgumentException(s"bad descriptor $desc at $c")
    MethodName(desc.substring(0, open), types(desc.substring(open + 1, close)), parseType(desc.substring(close + 1))._1)

  private def instance(cls: ClassName, desc: String)(gen: (String, List[String]) => String): Unit =
    methods((cls, parseMethod(desc))) = Instance(gen)

  private def static(cls: ClassName, desc: String)(gen: List[String] => String): Unit =
    methods((cls, parseMethod(desc))) = Static(gen)

  /** Instance method implemented by a Go function taking the receiver first. */
  private def instanceFn(cls: ClassName, desc: String, goFn: String): Unit =
    instance(cls, desc)((r, as) => s"$goFn(${(r :: as).mkString(", ")})")

  private def staticFn(cls: ClassName, desc: String, goFn: String): Unit =
    static(cls, desc)(as => s"$goFn(${as.mkString(", ")})")

  private def str(e: String) = s"rt.Cast[*rt.String]($e)"

  // ---- java.lang.Object -----------------------------------------------------------------------

  private val Object = ClassName.Object
  static(Object, "<init>()V")(_ => "rt.NewObject()")
  instanceFn(Object, "toString()Ljava.lang.String;", "rt.ToString")
  instanceFn(Object, "hashCode()I", "rt.HashCode")
  instanceFn(Object, "equals(Ljava.lang.Object;)Z", "rt.ObjectEquals")
  instanceFn(Object, "getClass()Ljava.lang.Class;", "rt.GetClass")
  instanceFn(Object, "clone()Ljava.lang.Object;", "rt.Clone")
  instanceFn(Object, "notify()V", "rt.Nop")
  instanceFn(Object, "notifyAll()V", "rt.Nop")

  // ---- java.lang.String -----------------------------------------------------------------------

  private val S = ClassName.String
  static(S, "<init>()V")(_ => "rt.NewStringEmpty()")
  staticFn(S, "<init>([C)V", "rt.NewStringFromChars")
  staticFn(S, "<init>([CII)V", "rt.NewStringFromCharRange")
  staticFn(S, "<init>(Ljava.lang.String;)V", "rt.NewStringCopy")
  instanceFn(S, "length()I", "rt.StringLength")
  instanceFn(S, "isEmpty()Z", "rt.StringIsEmpty")
  instanceFn(S, "charAt(I)C", "rt.StringCharAt")
  instanceFn(S, "codePointAt(I)I", "rt.StringCodePointAt")
  instanceFn(S, "substring(I)Ljava.lang.String;", "rt.StringSubstring")
  instanceFn(S, "substring(II)Ljava.lang.String;", "rt.StringSubstringRange")
  instanceFn(S, "subSequence(II)Ljava.lang.CharSequence;", "rt.StringSubstringRange")
  instanceFn(S, "equals(Ljava.lang.Object;)Z", "rt.StringEquals")
  instanceFn(S, "equalsIgnoreCase(Ljava.lang.String;)Z", "rt.StringEqualsIgnoreCase")
  instanceFn(S, "hashCode()I", "rt.StringHashCode")
  instanceFn(S, "compareTo(Ljava.lang.String;)I", "rt.StringCompareTo")
  instance(S, "compareTo(Ljava.lang.Object;)I")((r, as) => s"rt.StringCompareTo($r, ${str(as.head)})")
  instanceFn(S, "compareToIgnoreCase(Ljava.lang.String;)I", "rt.StringCompareToIgnoreCase")
  instance(S, "indexOf(I)I")((r, as) => s"rt.StringIndexOfChar($r, ${as.head}, 0)")
  instanceFn(S, "indexOf(II)I", "rt.StringIndexOfChar")
  instance(S, "indexOf(Ljava.lang.String;)I")((r, as) => s"rt.StringIndexOf($r, ${as.head}, 0)")
  instanceFn(S, "indexOf(Ljava.lang.String;I)I", "rt.StringIndexOf")
  instance(S, "lastIndexOf(I)I")((r, as) => s"rt.StringLastIndexOfChar($r, ${as.head}, rt.StringLength($r))")
  instanceFn(S, "lastIndexOf(II)I", "rt.StringLastIndexOfChar")
  instance(S, "lastIndexOf(Ljava.lang.String;)I")((r, as) => s"rt.StringLastIndexOf($r, ${as.head}, rt.StringLength($r))")
  instanceFn(S, "lastIndexOf(Ljava.lang.String;I)I", "rt.StringLastIndexOf")
  instance(S, "startsWith(Ljava.lang.String;)Z")((r, as) => s"rt.StringStartsWith($r, ${as.head}, 0)")
  instanceFn(S, "startsWith(Ljava.lang.String;I)Z", "rt.StringStartsWith")
  instanceFn(S, "endsWith(Ljava.lang.String;)Z", "rt.StringEndsWith")
  instanceFn(S, "contains(Ljava.lang.CharSequence;)Z", "rt.StringContains")
  instanceFn(S, "concat(Ljava.lang.String;)Ljava.lang.String;", "rt.StringConcat")
  instanceFn(S, "toUpperCase()Ljava.lang.String;", "rt.StringToUpperCase")
  instanceFn(S, "toLowerCase()Ljava.lang.String;", "rt.StringToLowerCase")
  instanceFn(S, "trim()Ljava.lang.String;", "rt.StringTrim")
  instanceFn(S, "strip()Ljava.lang.String;", "rt.StringStrip")
  instanceFn(S, "isBlank()Z", "rt.StringIsBlank")
  instanceFn(S, "replace(CC)Ljava.lang.String;", "rt.StringReplaceChar")
  instanceFn(S, "replace(Ljava.lang.CharSequence;Ljava.lang.CharSequence;)Ljava.lang.String;", "rt.StringReplace")
  instanceFn(S, "toCharArray()[C", "rt.StringToCharArray")
  instance(S, "toString()Ljava.lang.String;")((r, _) => r)
  instanceFn(S, "intern()Ljava.lang.String;", "rt.StringIntern")
  instanceFn(S, "repeat(I)Ljava.lang.String;", "rt.StringRepeat")
  instanceFn(S, "split(Ljava.lang.String;)[Ljava.lang.String;", "rt.StringSplit")
  staticFn(S, "valueOf(Ljava.lang.Object;)Ljava.lang.String;", "rt.StringValueOf")
  staticFn(S, "valueOf(C)Ljava.lang.String;", "rt.StringValueOfChar")
  staticFn(S, "valueOf([C)Ljava.lang.String;", "rt.NewStringFromChars")
  staticFn(S, "valueOf([CII)Ljava.lang.String;", "rt.NewStringFromCharRange")
  staticFn(S, "copyValueOf([C)Ljava.lang.String;", "rt.NewStringFromChars")
  for t <- List("Z", "I", "J", "F", "D") do staticFn(S, s"valueOf($t)Ljava.lang.String;", "rt.StringValueOf")
  staticFn(S, "format(Ljava.lang.String;[Ljava.lang.Object;)Ljava.lang.String;", "rt.StringFormat")
  instanceFn(S, "getChars(II[CI)V", "rt.StringGetChars")
  // Charsets are represented by their names (see rt.NewStringFromBytes).
  private val utf8 = "rt.Str(\"UTF-8\")"
  static(S, "<init>([B)V")(as => s"rt.NewStringFromBytes(${as.head}, $utf8)")
  staticFn(S, "<init>([BLjava.lang.String;)V", "rt.NewStringFromBytes")
  staticFn(S, "<init>([BLjava.nio.charset.Charset;)V", "rt.NewStringFromBytes")
  instance(S, "getBytes()[B")((r, _) => s"rt.StringGetBytes($r, $utf8)")
  instanceFn(S, "getBytes(Ljava.lang.String;)[B", "rt.StringGetBytes")
  instanceFn(S, "getBytes(Ljava.nio.charset.Charset;)[B", "rt.StringGetBytes")
  for (field, name) <- List("UTF_8" -> "UTF-8", "ISO_8859_1" -> "ISO-8859-1", "US_ASCII" -> "US-ASCII") do
    staticFields(("java.nio.charset.StandardCharsets", field)) = s"any(rt.Str(\"$name\"))"
  instanceFn("java.nio.charset.Charset", "name()Ljava.lang.String;", "rt.ToString")

  // ---- java.lang.Class ------------------------------------------------------------------------

  private val C = Hijacked.ClassClass
  instanceFn(C, "getName()Ljava.lang.String;", "rt.ClassGetName")
  instanceFn(C, "getTypeName()Ljava.lang.String;", "rt.ClassGetName")
  instanceFn(C, "getSimpleName()Ljava.lang.String;", "rt.ClassGetSimpleName")
  instanceFn(C, "isArray()Z", "rt.ClassIsArray")
  instanceFn(C, "isPrimitive()Z", "rt.ClassIsPrimitive")
  instanceFn(C, "getComponentType()Ljava.lang.Class;", "rt.ClassGetComponentType")
  instanceFn(C, "toString()Ljava.lang.String;", "rt.ClassToString")
  instance(C, "desiredAssertionStatus()Z")((_, _) => "false")
  instance(C, "getClassLoader()Ljava.lang.ClassLoader;")((_, _) => "nil")
  staticFn(C, "forName(Ljava.lang.String;)Ljava.lang.Class;", "rt.ClassForName")
  static(C, "forName(Ljava.lang.String;ZLjava.lang.ClassLoader;)Ljava.lang.Class;")(as => s"rt.ClassForName(${as.head})")
  staticFields(("java.lang.Void", "TYPE")) = "rt.ClassOf(\"void\")"

  // ---- Boxed primitives -----------------------------------------------------------------------

  private val numericAccessors = List("byte" -> Type.Byte, "short" -> Type.Short, "int" -> Type.Int,
    "long" -> Type.Long, "float" -> Type.Float, "double" -> Type.Double)

  for (prim, box) <- Hijacked.boxClasses do
    val goT = Hijacked.primitiveGoType(prim)
    val code = prim.descriptor
    def unboxed(r: String) = s"rt.Cast[$goT]($r)"
    if prim != Type.Boolean && prim != Type.Char then
      for (name, target) <- numericAccessors do
        instance(box, s"${name}Value()${target.descriptor}")((r, _) => convert(unboxed(r), prim, target))
    if prim == Type.Boolean then instance(box, "booleanValue()Z")((r, _) => unboxed(r))
    if prim == Type.Char then instance(box, "charValue()C")((r, _) => unboxed(r))
    val compare = prim match
      case Type.Boolean              => "rt.BoolCompare"
      case Type.Char                 => "rt.CharCompare"
      case Type.Long                 => "rt.LongCompare"
      case Type.Float | Type.Double  => "rt.DoubleCompare"
      case _                         => "rt.IntCompare"
    def compareArgs(a: String, b: String) = prim match
      case Type.Float                => s"float64($a), float64($b)"
      case Type.Byte | Type.Short    => s"int32($a), int32($b)"
      case _                         => s"$a, $b"
    instance(box, s"compareTo(L$box;)I")((r, as) => s"$compare(${compareArgs(unboxed(r), unboxed(as.head))})")
    instance(box, "compareTo(Ljava.lang.Object;)I")((r, as) => s"$compare(${compareArgs(unboxed(r), unboxed(as.head))})")
    static(box, s"compare($code$code)I")(as => s"$compare(${compareArgs(as(0), as(1))})")
    static(box, s"valueOf($code)L$box;")(as => s"any(${as.head})")
    static(box, s"toString($code)Ljava.lang.String;")(as => s"rt.StringValueOf(${as.head})")
    static(box, s"hashCode($code)I")(as => s"rt.HashCode(${as.head})")
    staticFields((box, "TYPE")) = s"""rt.ClassOf("${primitiveName(prim)}")"""

  private def primitiveName(t: Type): String = t match
    case Type.Boolean => "boolean"
    case Type.Char    => "char"
    case Type.Byte    => "byte"
    case Type.Short   => "short"
    case Type.Int     => "int"
    case Type.Long    => "long"
    case Type.Float   => "float"
    case Type.Double  => "double"
    case _            => "void"

  /** Numeric conversion with JVM semantics, for natives. */
  private def convert(e: String, from: Type, to: Type): String =
    val goTo = Hijacked.primitiveGoType(to)
    val fromFloating = from == Type.Float || from == Type.Double
    to match
      case _ if from == to                                 => e
      case Type.Int if fromFloating                        => s"rt.D2I(float64($e))"
      case Type.Long if fromFloating                       => s"rt.D2L(float64($e))"
      case Type.Byte | Type.Short | Type.Char if fromFloating => s"$goTo(rt.D2I(float64($e)))"
      case _                                               => s"$goTo($e)"

  private val Integer = "java.lang.Integer"
  private val Long = "java.lang.Long"
  static(Integer, "parseInt(Ljava.lang.String;)I")(as => s"rt.ParseInt(${as.head}, 10)")
  staticFn(Integer, "parseInt(Ljava.lang.String;I)I", "rt.ParseInt")
  static(Integer, "valueOf(Ljava.lang.String;)Ljava.lang.Integer;")(as => s"any(rt.ParseInt(${as.head}, 10))")
  static(Integer, "toString(II)Ljava.lang.String;")(as => s"rt.IntToString(${as(0)}, ${as(1)})")
  static(Integer, "toHexString(I)Ljava.lang.String;")(as => s"rt.IntToUnsignedString(${as.head}, 4)")
  static(Integer, "toOctalString(I)Ljava.lang.String;")(as => s"rt.IntToUnsignedString(${as.head}, 3)")
  static(Integer, "toBinaryString(I)Ljava.lang.String;")(as => s"rt.IntToUnsignedString(${as.head}, 1)")
  staticFn(Integer, "bitCount(I)I", "rt.IntBitCount")
  staticFn(Integer, "numberOfLeadingZeros(I)I", "rt.IntLeadingZeros")
  staticFn(Integer, "numberOfTrailingZeros(I)I", "rt.IntTrailingZeros")
  staticFn(Integer, "highestOneBit(I)I", "rt.IntHighestOneBit")
  static(Integer, "lowestOneBit(I)I")(as => s"(${as.head} & -${as.head})")
  staticFn(Integer, "reverse(I)I", "rt.IntReverse")
  staticFn(Integer, "reverseBytes(I)I", "rt.IntReverseBytes")
  staticFn(Integer, "rotateLeft(II)I", "rt.IntRotateLeft")
  static(Integer, "rotateRight(II)I")(as => s"rt.IntRotateLeft(${as(0)}, -${as(1)})")
  static(Integer, "signum(I)I")(as => s"rt.IntCompare(${as.head}, 0)")
  static(Integer, "divideUnsigned(II)I")(as => s"int32(uint32(${as(0)}) / uint32(${as(1)}))")
  static(Integer, "remainderUnsigned(II)I")(as => s"int32(uint32(${as(0)}) % uint32(${as(1)}))")
  static(Integer, "compareUnsigned(II)I")(as => s"rt.LongCompare(int64(uint32(${as(0)})), int64(uint32(${as(1)})))")
  static(Integer, "toUnsignedLong(I)J")(as => s"int64(uint32(${as.head}))")
  static(Long, "divideUnsigned(JJ)J")(as => s"int64(uint64(${as(0)}) / uint64(${as(1)}))")
  static(Long, "remainderUnsigned(JJ)J")(as => s"int64(uint64(${as(0)}) % uint64(${as(1)}))")
  static(Integer, "max(II)I")(as => s"max(${as(0)}, ${as(1)})")
  static(Integer, "min(II)I")(as => s"min(${as(0)}, ${as(1)})")
  static(Integer, "sum(II)I")(as => s"(${as(0)} + ${as(1)})")
  staticFn(Long, "parseLong(Ljava.lang.String;I)J", "rt.ParseLong")
  static(Long, "parseLong(Ljava.lang.String;)J")(as => s"rt.ParseLong(${as.head}, 10)")
  static(Long, "valueOf(Ljava.lang.String;)Ljava.lang.Long;")(as => s"any(rt.ParseLong(${as.head}, 10))")
  static(Long, "toString(JI)Ljava.lang.String;")(as => s"rt.LongToString(${as(0)}, ${as(1)})")
  static(Long, "toHexString(J)Ljava.lang.String;")(as => s"rt.LongToUnsignedString(${as.head}, 4)")
  static(Long, "toOctalString(J)Ljava.lang.String;")(as => s"rt.LongToUnsignedString(${as.head}, 3)")
  static(Long, "toBinaryString(J)Ljava.lang.String;")(as => s"rt.LongToUnsignedString(${as.head}, 1)")
  staticFn(Long, "bitCount(J)I", "rt.LongBitCount")
  staticFn(Long, "numberOfLeadingZeros(J)I", "rt.LongLeadingZeros")
  staticFn(Long, "numberOfTrailingZeros(J)I", "rt.LongTrailingZeros")
  staticFn(Long, "highestOneBit(J)J", "rt.LongHighestOneBit")
  static(Long, "lowestOneBit(J)J")(as => s"(${as.head} & -${as.head})")
  staticFn(Long, "reverse(J)J", "rt.LongReverse")
  staticFn(Long, "rotateLeft(JI)J", "rt.LongRotateLeft")
  static(Long, "rotateRight(JI)J")(as => s"rt.LongRotateLeft(${as(0)}, -${as(1)})")
  static(Long, "signum(J)I")(as => s"rt.LongCompare(${as.head}, 0)")
  static(Long, "max(JJ)J")(as => s"max(${as(0)}, ${as(1)})")
  static(Long, "min(JJ)J")(as => s"min(${as(0)}, ${as(1)})")
  static(Long, "sum(JJ)J")(as => s"(${as(0)} + ${as(1)})")

  private val Double = "java.lang.Double"
  private val Float = "java.lang.Float"
  staticFn(Double, "parseDouble(Ljava.lang.String;)D", "rt.ParseDouble")
  static(Double, "valueOf(Ljava.lang.String;)Ljava.lang.Double;")(as => s"any(rt.ParseDouble(${as.head}))")
  static(Float, "parseFloat(Ljava.lang.String;)F")(as => s"float32(rt.ParseDouble(${as.head}))")
  for (box, code, goT) <- List((Double, "D", "float64"), (Float, "F", "float32")) do
    static(box, s"isNaN($code)Z")(as => s"(${as.head} != ${as.head})")
    static(box, s"isInfinite($code)Z")(as => s"math.IsInf(float64(${as.head}), 0)")
    static(box, s"isFinite($code)Z")(as => s"!(math.IsInf(float64(${as.head}), 0) || ${as.head} != ${as.head})")
    instance(box, "isNaN()Z")((r, _) => s"func(x $goT) bool { return x != x }(rt.Cast[$goT]($r))")
    instance(box, "isInfinite()Z")((r, _) => s"math.IsInf(float64(rt.Cast[$goT]($r)), 0)")
    static(box, s"max($code$code)$code")(as => s"$goT(math.Max(float64(${as(0)}), float64(${as(1)})))")
    static(box, s"min($code$code)$code")(as => s"$goT(math.Min(float64(${as(0)}), float64(${as(1)})))")
    static(box, s"sum($code$code)$code")(as => s"(${as(0)} + ${as(1)})")
  staticFn(Double, "doubleToLongBits(D)J", "rt.DoubleToLongBits")
  static(Double, "doubleToRawLongBits(D)J")(as => s"int64(math.Float64bits(${as.head}))")
  static(Double, "longBitsToDouble(J)D")(as => s"math.Float64frombits(uint64(${as.head}))")
  staticFn(Float, "floatToIntBits(F)I", "rt.FloatToIntBits")
  static(Float, "floatToRawIntBits(F)I")(as => s"int32(math.Float32bits(${as.head}))")
  static(Float, "intBitsToFloat(I)F")(as => s"math.Float32frombits(uint32(${as.head}))")

  private val Boolean = "java.lang.Boolean"
  staticFn(Boolean, "parseBoolean(Ljava.lang.String;)Z", "rt.ParseBoolean")
  static(Boolean, "getBoolean(Ljava.lang.String;)Z")(_ => "false")

  private val Character = "java.lang.Character"
  for (name, fn) <- List(
      "isDigit" -> "rt.CharIsDigit", "isLetter" -> "rt.CharIsLetter", "isLetterOrDigit" -> "rt.CharIsLetterOrDigit",
      "isWhitespace" -> "rt.CharIsWhitespace", "isSpaceChar" -> "rt.CharIsSpaceChar",
      "isUpperCase" -> "rt.CharIsUpperCase", "isLowerCase" -> "rt.CharIsLowerCase", "isAlphabetic" -> "rt.CharIsLetter"
    )
  do
    static(Character, s"$name(C)Z")(as => s"$fn(int32(${as.head}))")
    staticFn(Character, s"$name(I)Z", fn)
  static(Character, "toUpperCase(C)C")(as => s"uint16(rt.CharToUpperCase(int32(${as.head})))")
  static(Character, "toLowerCase(C)C")(as => s"uint16(rt.CharToLowerCase(int32(${as.head})))")
  staticFn(Character, "toUpperCase(I)I", "rt.CharToUpperCase")
  staticFn(Character, "toLowerCase(I)I", "rt.CharToLowerCase")
  static(Character, "digit(CI)I")(as => s"rt.CharDigit(int32(${as(0)}), ${as(1)})")
  staticFn(Character, "digit(II)I", "rt.CharDigit")
  staticFn(Character, "forDigit(II)C", "rt.CharForDigit")
  static(Character, "isHighSurrogate(C)Z")(as => s"(${as.head} >= 0xd800 && ${as.head} <= 0xdbff)")
  static(Character, "isLowSurrogate(C)Z")(as => s"(${as.head} >= 0xdc00 && ${as.head} <= 0xdfff)")
  static(Character, "isSurrogate(C)Z")(as => s"(${as.head} >= 0xd800 && ${as.head} <= 0xdfff)")
  static(Character, "charCount(I)I")(as => s"rt.CharCount(${as.head})")

  // ---- java.lang.Math -------------------------------------------------------------------------

  for mathClass <- List("java.lang.Math", "java.lang.StrictMath") do
    def m(desc: String)(gen: List[String] => String) = static(mathClass, desc)(gen)
    def unaryD(name: String, goFn: String) = m(s"$name(D)D")(as => s"$goFn(${as.head})")
    for (name, goFn) <- List("sqrt" -> "math.Sqrt", "cbrt" -> "math.Cbrt", "exp" -> "math.Exp", "log" -> "math.Log",
        "log10" -> "math.Log10", "log1p" -> "math.Log1p", "expm1" -> "math.Expm1", "sin" -> "math.Sin",
        "cos" -> "math.Cos", "tan" -> "math.Tan", "asin" -> "math.Asin", "acos" -> "math.Acos", "atan" -> "math.Atan",
        "sinh" -> "math.Sinh", "cosh" -> "math.Cosh", "tanh" -> "math.Tanh", "floor" -> "math.Floor",
        "ceil" -> "math.Ceil", "rint" -> "rt.MathRint", "signum" -> "rt.MathSignum")
    do unaryD(name, goFn)
    m("toRadians(D)D")(as => s"(${as.head} / 180 * math.Pi)")
    m("toDegrees(D)D")(as => s"(${as.head} * 180 / math.Pi)")
    m("pow(DD)D")(as => s"math.Pow(${as(0)}, ${as(1)})")
    m("atan2(DD)D")(as => s"math.Atan2(${as(0)}, ${as(1)})")
    m("hypot(DD)D")(as => s"math.Hypot(${as(0)}, ${as(1)})")
    m("random()D")(_ => "rt.MathRandom()")
    m("round(D)J")(as => s"rt.MathRound(${as.head})")
    m("round(F)I")(as => s"rt.MathRoundFloat(${as.head})")
    m("signum(F)F")(as => s"float32(rt.MathSignum(float64(${as.head})))")
    for (code, goT) <- List("I" -> "int32", "J" -> "int64", "F" -> "float32", "D" -> "float64") do
      m(s"abs($code)$code")(as => s"rt.Abs(${as.head})")
      if code == "I" || code == "J" then
        m(s"max($code$code)$code")(as => s"max(${as(0)}, ${as(1)})")
        m(s"min($code$code)$code")(as => s"min(${as(0)}, ${as(1)})")
      else
        m(s"max($code$code)$code")(as => s"$goT(math.Max(float64(${as(0)}), float64(${as(1)})))")
        m(s"min($code$code)$code")(as => s"$goT(math.Min(float64(${as(0)}), float64(${as(1)})))")
    m("floorDiv(II)I")(as => s"rt.IntFloorDiv(${as(0)}, ${as(1)})")
    m("floorMod(II)I")(as => s"rt.IntFloorMod(${as(0)}, ${as(1)})")
    m("floorDiv(JJ)J")(as => s"rt.LongFloorDiv(${as(0)}, ${as(1)})")
    m("floorMod(JJ)J")(as => s"rt.LongFloorMod(${as(0)}, ${as(1)})")
    m("addExact(II)I")(as => s"rt.IntAddExact(${as(0)}, ${as(1)})")
    m("multiplyExact(II)I")(as => s"rt.IntMultiplyExact(${as(0)}, ${as(1)})")
    m("addExact(JJ)J")(as => s"rt.LongAddExact(${as(0)}, ${as(1)})")
    m("multiplyExact(JJ)J")(as => s"rt.LongMultiplyExact(${as(0)}, ${as(1)})")

  // ---- java.lang.System and reflection --------------------------------------------------------

  private val System = "java.lang.System"
  static(System, "currentTimeMillis()J")(_ => "rt.CurrentTimeMillis()")
  static(System, "nanoTime()J")(_ => "rt.NanoTime()")
  staticFn(System, "arraycopy(Ljava.lang.Object;ILjava.lang.Object;II)V", "rt.ArrayCopy")
  staticFn(System, "identityHashCode(Ljava.lang.Object;)I", "rt.IdentityHashCode")
  staticFn(System, "exit(I)V", "rt.Exit")
  static(System, "lineSeparator()Ljava.lang.String;")(_ => "rt.Str(\"\\n\")")
  static(System, "getProperty(Ljava.lang.String;)Ljava.lang.String;")(_ => "(*rt.String)(nil)")
  static(System, "getProperty(Ljava.lang.String;Ljava.lang.String;)Ljava.lang.String;")(as => as(1))
  staticFn(System, "getenv(Ljava.lang.String;)Ljava.lang.String;", "rt.Getenv")

  private val ReflectArray = "java.lang.reflect.Array"
  staticFn(ReflectArray, "newInstance(Ljava.lang.Class;I)Ljava.lang.Object;", "rt.NewArrayOf")
  staticFn(ReflectArray, "newInstance(Ljava.lang.Class;[I)Ljava.lang.Object;", "rt.NewMultiArrayOf")
  staticFn(ReflectArray, "getLength(Ljava.lang.Object;)I", "rt.ArrayLengthOf")
  staticFn(ReflectArray, "get(Ljava.lang.Object;I)Ljava.lang.Object;", "rt.ArrayGet")
  staticFn(ReflectArray, "set(Ljava.lang.Object;ILjava.lang.Object;)V", "rt.ArraySet")

  // ---- java.math.BigInteger (math/big) --------------------------------------------------------

  private val BI = Hijacked.BigInteger
  private val BIT = "Ljava.math.BigInteger;"
  staticFn(BI, "valueOf(J)" + BIT, "rt.BigIntegerValueOf")
  for (field, go) <- List("ZERO" -> "rt.BigZero", "ONE" -> "rt.BigOne", "TWO" -> "rt.BigTwo", "TEN" -> "rt.BigTen") do
    staticFields((BI, field)) = go
  static(BI, "<init>(Ljava.lang.String;)V")(as => s"rt.NewBigIntegerFromString(${as.head}, 10)")
  staticFn(BI, "<init>(Ljava.lang.String;I)V", "rt.NewBigIntegerFromString")
  staticFn(BI, "<init>([B)V", "rt.NewBigIntegerFromBytes")
  staticFn(BI, "<init>(I[B)V", "rt.NewBigIntegerFromMagnitude")
  for (name, fn) <- List("add" -> "Add", "subtract" -> "Subtract", "multiply" -> "Multiply", "divide" -> "Divide",
      "remainder" -> "Remainder", "mod" -> "Mod", "gcd" -> "Gcd", "and" -> "And", "or" -> "Or", "xor" -> "Xor",
      "andNot" -> "AndNot", "min" -> "Min", "max" -> "Max", "modInverse" -> "ModInverse")
  do instanceFn(BI, s"$name($BIT)$BIT", s"rt.BigInteger$fn")
  instanceFn(BI, s"divideAndRemainder($BIT)[$BIT", "rt.BigIntegerDivideAndRemainder")
  instanceFn(BI, s"modPow($BIT$BIT)$BIT", "rt.BigIntegerModPow")
  for (name, fn) <- List("negate" -> "Negate", "abs" -> "Abs", "not" -> "Not") do
    instanceFn(BI, s"$name()$BIT", s"rt.BigInteger$fn")
  for (name, fn) <- List("pow" -> "Pow", "shiftLeft" -> "ShiftLeft", "shiftRight" -> "ShiftRight",
      "setBit" -> "SetBit", "clearBit" -> "ClearBit", "flipBit" -> "FlipBit")
  do instanceFn(BI, s"$name(I)$BIT", s"rt.BigInteger$fn")
  instanceFn(BI, "testBit(I)Z", "rt.BigIntegerTestBit")
  instanceFn(BI, "isProbablePrime(I)Z", "rt.BigIntegerIsProbablePrime")
  for (name, fn) <- List("signum" -> "Signum", "bitLength" -> "BitLength", "bitCount" -> "BitCount",
      "getLowestSetBit" -> "LowestSetBit", "intValue" -> "IntValue", "hashCode" -> "HashCode")
  do instanceFn(BI, s"$name()I", s"rt.BigInteger$fn")
  instanceFn(BI, "longValue()J", "rt.BigIntegerLongValue")
  instanceFn(BI, "doubleValue()D", "rt.BigIntegerDoubleValue")
  instanceFn(BI, "floatValue()F", "rt.BigIntegerFloatValue")
  instance(BI, "byteValue()B")((r, _) => s"int8(rt.BigIntegerIntValue($r))")
  instance(BI, "shortValue()S")((r, _) => s"int16(rt.BigIntegerIntValue($r))")
  instanceFn(BI, "toByteArray()[B", "rt.BigIntegerToByteArray")
  instanceFn(BI, s"compareTo($BIT)I", "rt.BigIntegerCompareTo")
  instance(BI, "compareTo(Ljava.lang.Object;)I")((r, as) => s"rt.BigIntegerCompareTo($r, rt.Cast[*rt.BigInteger](${as.head}))")
  instanceFn(BI, "equals(Ljava.lang.Object;)Z", "rt.BigIntegerEquals")
  instance(BI, "toString()Ljava.lang.String;")((r, _) => s"rt.BigIntegerToString($r, 10)")
  instanceFn(BI, "toString(I)Ljava.lang.String;", "rt.BigIntegerToString")

  // ---- java.util ------------------------------------------------------------------------------

  private val Arrays = "java.util.Arrays"
  for elem <- List("Z", "C", "B", "S", "I", "J", "F", "D", "Ljava.lang.Object;") do
    val arr = s"[$elem"
    staticFn(Arrays, s"copyOf($arr" + "I)" + arr, "rt.ArraysCopyOf")
    if elem.startsWith("L") then
      static(Arrays, s"copyOf(${arr}ILjava.lang.Class;)$arr")(as => s"rt.ArraysCopyOf(${as(0)}, ${as(1)})")
    staticFn(Arrays, s"copyOfRange($arr" + "II)" + arr, "rt.ArraysCopyOfRange")
    // Object arrays: pass the value as `any`, or Go infers the element type from it.
    val value = (v: String) => if elem.startsWith("L") then s"any($v)" else v
    static(Arrays, s"fill($arr$elem)V")(as => s"rt.ArraysFill(${as(0)}, ${value(as(1))})")
    static(Arrays, s"fill(${arr}II$elem)V")(as => s"rt.ArraysFillRange(${as(0)}, ${as(1)}, ${as(2)}, ${value(as(3))})")
    staticFn(Arrays, s"hashCode($arr)I", "rt.ArraysHashCode")
    staticFn(Arrays, s"equals($arr$arr)Z", if elem.startsWith("L") then "rt.ArraysEqualsObjects" else "rt.ArraysEquals")
    if elem != "Z" && !elem.startsWith("L") then
      static(Arrays, s"sort($arr)V")(as => s"rt.ArraysSort(${as.head}, 0, int32(len(${as.head}.Data)))")
      staticFn(Arrays, s"sort(${arr}II)V", "rt.ArraysSort")

  private val Objects = "java.util.Objects"
  staticFn(Objects, "equals(Ljava.lang.Object;Ljava.lang.Object;)Z", "rt.ObjectsEquals")
  staticFn(Objects, "hashCode(Ljava.lang.Object;)I", "rt.ObjectsHashCode")
  staticFn(Objects, "hash([Ljava.lang.Object;)I", "rt.ArraysHashCode")
  staticFn(Objects, "toString(Ljava.lang.Object;)Ljava.lang.String;", "rt.ToString")
  static(Objects, "isNull(Ljava.lang.Object;)Z")(as => s"(${as.head} == nil)")
  static(Objects, "nonNull(Ljava.lang.Object;)Z")(as => s"(${as.head} != nil)")
  static(Objects, "requireNonNull(Ljava.lang.Object;)Ljava.lang.Object;")(as => s"rt.RequireNonNull(${as.head}, nil)")
  staticFn(Objects, "requireNonNull(Ljava.lang.Object;Ljava.lang.String;)Ljava.lang.Object;", "rt.RequireNonNull")

  // ---- Scala library --------------------------------------------------------------------------

  staticFields((Hijacked.BoxedUnit, "UNIT")) = "rt.UnitValue"

  private val Statics = "scala.runtime.Statics"
  staticFn(Statics, "mix(II)I", "rt.StaticsMix")
  staticFn(Statics, "mixLast(II)I", "rt.StaticsMixLast")
  staticFn(Statics, "finalizeHash(II)I", "rt.StaticsFinalizeHash")
  staticFn(Statics, "avalanche(I)I", "rt.StaticsAvalanche")
  staticFn(Statics, "longHash(J)I", "rt.StaticsLongHash")
  staticFn(Statics, "doubleHash(D)I", "rt.StaticsDoubleHash")
  staticFn(Statics, "floatHash(F)I", "rt.StaticsFloatHash")
  staticFn(Statics, "anyHash(Ljava.lang.Object;)I", "rt.AnyHash")
  static(Statics, "releaseFence()V")(_ => "")
  static(Statics, "ioobe(I)Ljava.lang.Object;")(as =>
    s"""func() any { rt.ThrowKind(rt.ArrayIndexOutOfBounds, "Index " + rt.ToGoString(${as.head}) + " out of bounds"); return nil }()"""
  )
  staticFields((Statics, "pfMarker")) = "rt.PfMarker"

  private val Boxes = "scala.runtime.BoxesRunTime"
  for (prim, box) <- Hijacked.boxClasses do
    val name = box.stripPrefix("java.lang.")
    val unboxName = if name == "Integer" then "Int" else if name == "Character" then "Char" else name
    val code = prim.descriptor
    static(Boxes, s"boxTo$name($code)L$box;")(as => s"any(${as.head})")
    static(Boxes, s"unboxTo$unboxName(Ljava.lang.Object;)$code")(as => s"rt.Cast[${Hijacked.primitiveGoType(prim)}](${as.head})")
  for desc <- List("equals(Ljava.lang.Object;Ljava.lang.Object;)Z", "equals2(Ljava.lang.Object;Ljava.lang.Object;)Z",
      "equalsNumNum(Ljava.lang.Number;Ljava.lang.Number;)Z", "equalsNumObject(Ljava.lang.Number;Ljava.lang.Object;)Z",
      "equalsCharObject(Ljava.lang.Character;Ljava.lang.Object;)Z", "equalsNumChar(Ljava.lang.Number;Ljava.lang.Character;)Z")
  do staticFn(Boxes, desc, "rt.Equals")
  for desc <- List("hashFromLong(Ljava.lang.Long;)I", "hashFromDouble(Ljava.lang.Double;)I",
      "hashFromFloat(Ljava.lang.Float;)I", "hashFromNumber(Ljava.lang.Number;)I", "hashFromObject(Ljava.lang.Object;)I")
  do staticFn(Boxes, desc, "rt.AnyHash")
  staticFn(Boxes, "isBoxedNumber(Ljava.lang.Object;)Z", "rt.IsBoxedNumber")
  staticFn(Boxes, "isBoxedNumberOrBoolean(Ljava.lang.Object;)Z", "rt.IsBoxedNumberOrBoolean")

  private val GoConsole = "scalago.runtime.Console$"
  staticFn(GoConsole, "writeStdout(Ljava.lang.String;)V", "rt.WriteStdout")
  staticFn(GoConsole, "writeStderr(Ljava.lang.String;)V", "rt.WriteStderr")
  static(GoConsole, "flush()V")(_ => "rt.FlushStdout()")

  // ---- scalago.go: Go strings (library/) --------------------------------------------------------

  // A top-level opaque type's companion lives in the file's package object.
  private val GoString = "scalago.go.GoString$package$GoString$"
  private def goStr(e: String) = s"$e.(string)"
  static(GoString, "apply(Ljava.lang.String;)Ljava.lang.Object;")(as => s"any(${as.head}.GoString())")
  static(GoString, "toJavaString(Ljava.lang.Object;)Ljava.lang.String;")(as => s"rt.Str(${goStr(as.head)})")
  static(GoString, "byteLength(Ljava.lang.Object;)I")(as => s"int32(len(${goStr(as.head)}))")
  static(GoString, "byteAt(Ljava.lang.Object;I)B")(as => s"int8(${goStr(as(0))}[${as(1)}])")
  static(GoString, "concat(Ljava.lang.Object;Ljava.lang.Object;)Ljava.lang.Object;")(as =>
    s"any(${goStr(as(0))} + ${goStr(as(1))})"
  )
  static(GoString, "sameAs(Ljava.lang.Object;Ljava.lang.Object;)Z")(as => s"(${goStr(as(0))} == ${goStr(as(1))})")

  // Arrays: `clone` is the only method called on array receivers.
  instanceFn("scala.Array", "clone()Ljava.lang.Object;", "rt.ArrayClone")

  // Console output without Console's DynamicVariable/ThreadLocal machinery.
  for module <- List("scala.Predef$", "scala.Console$") do
    static(module, "println(Ljava.lang.Object;)V")(as => s"rt.Println(${as.head})")
    static(module, "println()V")(_ => "rt.PrintNewline()")
    static(module, "print(Ljava.lang.Object;)V")(as => s"rt.Print(${as.head})")
end Natives
