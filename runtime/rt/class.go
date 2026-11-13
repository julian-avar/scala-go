package rt

import (
	"strings"
	"sync"
)

// Class is java.lang.Class, identified by its JVM name ("java.lang.String", "int", "[I").
// Instances are interned, so reference equality works as on the JVM. The program's classes and
// the JDK value classes also have a ClassInfo, which the reflective methods use.
type Class struct {
	name string
	info *ClassInfo

	supertypesOnce sync.Once
	supertypes     map[*Class]bool // all but Object, for isAssignableFrom
}

// ClassInfo describes a class the program defines (or a JDK value class).
type ClassInfo struct {
	Class      *Class
	Super      *Class // nil for interfaces and Object
	Interfaces []*Class
	Interface  bool
	// A nested class has the JVM's InnerClasses entry: its simple name ("" if anonymous) and,
	// for a member class, the class it is a member of (nil for local and anonymous classes).
	Nested     bool
	SimpleName string
	Outer      *Class
	// For an enum class, its values(), if the program uses getEnumConstants.
	Values func() *Array[any]
}

// DefineClasses gives the program's classes their ClassInfo. The generated code calls it while
// the program initializes, before any Scala code runs.
func DefineClasses(infos []ClassInfo) {
	for i := range infos {
		infos[i].Class.info = &infos[i]
	}
}

// classes interns Class objects by name. Lookups are frequent (getClass, classOf in library
// code) and nearly always hits, which sync.Map serves without locking.
var classes sync.Map // string -> *Class

func ClassOf(name string) *Class {
	if c, ok := classes.Load(name); ok {
		return c.(*Class)
	}
	c, _ := classes.LoadOrStore(name, &Class{name: name})
	return c.(*Class)
}

var primitiveDescriptors = map[string]string{
	"boolean": "Z", "char": "C", "byte": "B", "short": "S", "int": "I", "long": "J", "float": "F", "double": "D",
}

// GetClass implements x.getClass().
// Classes of the hijacked types, which GetClass returns often.
var (
	classjava_lang_String        = ClassOf("java.lang.String")
	classjava_lang_Boolean       = ClassOf("java.lang.Boolean")
	classjava_lang_Character     = ClassOf("java.lang.Character")
	classjava_lang_Byte          = ClassOf("java.lang.Byte")
	classjava_lang_Short         = ClassOf("java.lang.Short")
	classjava_lang_Integer       = ClassOf("java.lang.Integer")
	classjava_lang_Long          = ClassOf("java.lang.Long")
	classjava_lang_Float         = ClassOf("java.lang.Float")
	classjava_lang_Double        = ClassOf("java.lang.Double")
	classscala_runtime_BoxedUnit = ClassOf("scala.runtime.BoxedUnit")
	classjava_lang_Class         = ClassOf("java.lang.Class")
	classjava_math_BigInteger    = ClassOf("java.math.BigInteger")
	classjava_math_BigDecimal    = ClassOf("java.math.BigDecimal")
	class_Z                      = ClassOf("[Z")
	class_C                      = ClassOf("[C")
	class_B                      = ClassOf("[B")
	class_S                      = ClassOf("[S")
	class_I                      = ClassOf("[I")
	class_J                      = ClassOf("[J")
	class_F                      = ClassOf("[F")
	class_D                      = ClassOf("[D")
	class_Ljava_lang_Object_     = ClassOf("[Ljava.lang.Object;")
	classjava_lang_Object        = ClassOf("java.lang.Object")
)

// The JDK value classes, whose instances are Go values (Hijacked.ancestors in the linker), and
// the interfaces of arrays (Hijacked.arrayAncestors).
var arrayInterfaces = []*Class{ClassOf("java.lang.Cloneable"), ClassOf("java.io.Serializable")}

func init() {
	serializable, comparable := ClassOf("java.io.Serializable"), ClassOf("java.lang.Comparable")
	constable, constantDesc := ClassOf("java.lang.constant.Constable"), ClassOf("java.lang.constant.ConstantDesc")
	number := ClassOf("java.lang.Number")
	define := func(c *Class, super *Class, interfaces ...*Class) {
		c.info = &ClassInfo{Class: c, Super: super, Interfaces: interfaces}
	}
	define(classjava_lang_String, classjava_lang_Object, serializable, comparable, ClassOf("java.lang.CharSequence"),
		constable, constantDesc)
	define(classjava_lang_Boolean, classjava_lang_Object, serializable, comparable, constable)
	define(classjava_lang_Character, classjava_lang_Object, serializable, comparable, constable)
	for _, box := range []*Class{classjava_lang_Byte, classjava_lang_Short, classjava_lang_Integer,
		classjava_lang_Long, classjava_lang_Float, classjava_lang_Double} {
		define(box, number, comparable, constable, constantDesc)
	}
	define(classscala_runtime_BoxedUnit, classjava_lang_Object, serializable)
	define(classjava_lang_Class, classjava_lang_Object, serializable,
		ClassOf("java.lang.reflect.GenericDeclaration"), ClassOf("java.lang.reflect.Type"))
	define(classjava_math_BigInteger, number, comparable)
	define(classjava_math_BigDecimal, number, comparable)
}

func GetClass(x any) *Class {
	switch v := x.(type) {
	case nil:
		ThrowKind(NullPointer, "")
	case *String:
		return classjava_lang_String
	case bool:
		return classjava_lang_Boolean
	case uint16:
		return classjava_lang_Character
	case int8:
		return classjava_lang_Byte
	case int16:
		return classjava_lang_Short
	case int32:
		return classjava_lang_Integer
	case int64:
		return classjava_lang_Long
	case float32:
		return classjava_lang_Float
	case float64:
		return classjava_lang_Double
	case *BoxedUnit:
		return classscala_runtime_BoxedUnit
	case *Class:
		return classjava_lang_Class
	case *BigInteger:
		return classjava_math_BigInteger
	case *BigDecimal:
		return classjava_math_BigDecimal
	case *Array[bool]:
		return class_Z
	case *Array[uint16]:
		return class_C
	case *Array[int8]:
		return class_B
	case *Array[int16]:
		return class_S
	case *Array[int32]:
		return class_I
	case *Array[int64]:
		return class_J
	case *Array[float32]:
		return class_F
	case *Array[float64]:
		return class_D
	case *Array[any]:
		return class_Ljava_lang_Object_
	case scalaClass:
		return v.ScalaClass()
	case scalaClassName:
		return ClassOf(v.ScalaClassName())
	case *Object:
		return classjava_lang_Object
	}
	return ClassOf(foreignClassName(x))
}

func ClassGetName(c *Class) *String { return Str(c.name) }

// ClassGetTypeName implements getTypeName: getName, but arrays are named after their component
// type ("int[]", "java.lang.String[][]").
func ClassGetTypeName(c *Class) *String {
	dims := ""
	for ClassIsArray(c) {
		c = ClassGetComponentType(c)
		dims += "[]"
	}
	return Str(c.name + dims)
}

func ClassGetSimpleName(c *Class) *String {
	if ClassIsArray(c) {
		return StringConcat(ClassGetSimpleName(ClassGetComponentType(c)), Str("[]"))
	}
	name := c.name[strings.LastIndex(c.name, ".")+1:]
	switch {
	case c.info != nil && c.info.Nested:
		return Str(c.info.SimpleName)
	case c.info == nil:
		// A class the program does not define (a JDK class it names with classOf): from the
		// name, as JDK 8 did. An anonymous class (Outer$1) has an empty simple name, and a
		// local class (Outer$1Local) loses its digits.
		if i := strings.LastIndex(strings.TrimSuffix(name, "$"), "$"); i >= 0 {
			name = strings.TrimLeft(name[i+1:], "0123456789")
		}
	}
	return Str(name)
}

// ClassGetCanonicalName implements getCanonicalName: null for local and anonymous classes (and
// arrays of them); for a member class, the canonical name of its class and its simple name.
func ClassGetCanonicalName(c *Class) *String {
	if ClassIsArray(c) {
		component := ClassGetCanonicalName(ClassGetComponentType(c))
		if component == nil {
			return nil
		}
		return StringConcat(component, Str("[]"))
	}
	if c.info == nil || !c.info.Nested {
		return Str(c.name)
	}
	if c.info.Outer == nil {
		return nil
	}
	outer := ClassGetCanonicalName(c.info.Outer)
	if outer == nil {
		return nil
	}
	return StringConcat(outer, Str("."+c.info.SimpleName))
}

func ClassIsArray(c *Class) bool { return strings.HasPrefix(c.name, "[") }

func ClassIsPrimitive(c *Class) bool {
	_, ok := primitiveDescriptors[c.name]
	return ok || c.name == "void"
}

func ClassIsInterface(c *Class) bool { return c.info != nil && c.info.Interface }

// ClassIsEnum implements isEnum: true for the classes that extend java.lang.Enum directly (not
// for the classes of enum constants with a body).
func ClassIsEnum(c *Class) bool {
	return c.info != nil && c.info.Super != nil && c.info.Super.name == "java.lang.Enum"
}

// ClassGetEnumConstants implements getEnumConstants: values() of an enum class, else null.
func ClassGetEnumConstants(c *Class) *Array[any] {
	if !ClassIsEnum(c) || c.info.Values == nil {
		return nil
	}
	return c.info.Values()
}

func ClassGetComponentType(c *Class) *Class {
	if !ClassIsArray(c) {
		return nil
	}
	elem := c.name[1:]
	for name, d := range primitiveDescriptors {
		if elem == d {
			return ClassOf(name)
		}
	}
	if strings.HasPrefix(elem, "L") {
		return ClassOf(strings.TrimSuffix(elem[1:], ";"))
	}
	return ClassOf(elem)
}

func ClassToString(c *Class) *String {
	switch {
	case ClassIsPrimitive(c):
		return Str(c.name)
	case ClassIsInterface(c):
		return Str("interface " + c.name)
	}
	return Str("class " + c.name)
}

// ClassGetSuperclass implements getSuperclass: null for Object, interfaces and primitives;
// Object for arrays and for the classes of Go values.
func ClassGetSuperclass(c *Class) *Class {
	switch {
	case c == classjava_lang_Object || ClassIsPrimitive(c) || ClassIsInterface(c):
		return nil
	case c.info != nil:
		return c.info.Super
	}
	return classjava_lang_Object
}

// ClassGetInterfaces implements getInterfaces: the interfaces the class declares.
func ClassGetInterfaces(c *Class) *Array[any] {
	interfaces := arrayInterfaces
	if !ClassIsArray(c) {
		interfaces = nil
		if c.info != nil {
			interfaces = c.info.Interfaces
		}
	}
	result := make([]any, len(interfaces))
	for i, ic := range interfaces {
		result[i] = ic
	}
	return &Array[any]{Data: result}
}

// allSupertypes returns the classes c extends or implements, directly or not, but Object.
func (c *Class) allSupertypes() map[*Class]bool {
	c.supertypesOnce.Do(func() {
		c.supertypes = map[*Class]bool{}
		var add func(*ClassInfo)
		add = func(info *ClassInfo) {
			for _, s := range append([]*Class{info.Super}, info.Interfaces...) {
				if s != nil && s != classjava_lang_Object && !c.supertypes[s] {
					c.supertypes[s] = true
					if s.info != nil {
						add(s.info)
					}
				}
			}
		}
		if c.info != nil {
			add(c.info)
		}
	})
	return c.supertypes
}

// ClassIsAssignableFrom implements c.isAssignableFrom(other). Arrays of references are of class
// Object[] whatever their elements (see Array).
func ClassIsAssignableFrom(c, other *Class) bool {
	if other == nil {
		ThrowKind(NullPointer, "")
	}
	switch {
	case c == other:
		return true
	case ClassIsPrimitive(c) || ClassIsPrimitive(other):
		return false
	case c == classjava_lang_Object:
		return true
	case ClassIsArray(other):
		if ClassIsArray(c) {
			return ClassIsAssignableFrom(ClassGetComponentType(c), ClassGetComponentType(other))
		}
		return c == arrayInterfaces[0] || c == arrayInterfaces[1]
	}
	return other.allSupertypes()[c]
}

// ClassIsInstance implements c.isInstance(x). As with isInstanceOf, an array of references is an
// instance of every class of arrays of references.
func ClassIsInstance(c *Class, x any) bool {
	if x == nil {
		return false
	}
	if _, ok := x.(*Array[any]); ok && ClassIsArray(c) {
		return !ClassIsPrimitive(ClassGetComponentType(c))
	}
	return ClassIsAssignableFrom(c, GetClass(x))
}

// ClassCastValue implements c.cast(x).
func ClassCastValue(c *Class, x any) any {
	if x != nil && !ClassIsInstance(c, x) {
		ThrowKind(ClassCast, "Cannot cast "+GetClass(x).name+" to "+c.name)
	}
	return x
}

// ArrayClassOf is the class of arrays whose elements have class c.
func ArrayClassOf(c *Class) *Class {
	if d, ok := primitiveDescriptors[c.name]; ok {
		return ClassOf("[" + d)
	}
	if ClassIsArray(c) {
		return ClassOf("[" + c.name)
	}
	return ClassOf("[L" + c.name + ";")
}

// String implements fmt.Stringer.
func (c *Class) String() string { return c.name }

// ClassForName implements Class.forName: it finds the classes with a ClassInfo, which are those
// the linked program can use, and arrays of them or of primitives. It does not initialize the
// class.
func ClassForName(name *String) *Class {
	if name == nil {
		ThrowKind(NullPointer, "")
	}
	n := name.GoString()
	elem, missing := strings.TrimLeft(n, "["), n
	switch {
	case len(elem) == len(n):
	case len(elem) == 1 && strings.Contains("ZCBSIJFD", elem):
		return ClassOf(n)
	case len(elem) > 2 && elem[0] == 'L' && elem[len(elem)-1] == ';':
		elem = elem[1 : len(elem)-1]
		missing = elem // as on the JVM, the message names the element class
	default:
		elem = ""
	}
	if c, ok := classes.Load(elem); ok && c.(*Class).info != nil {
		return ClassOf(n)
	}
	ThrowKind(ClassNotFound, missing)
	return nil
}
