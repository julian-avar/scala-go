package rt

import (
	"strings"
	"sync"
)

// Class is java.lang.Class, identified by its JVM name ("java.lang.String", "int", "[I").
// Instances are interned, so reference equality works as on the JVM.
type Class struct {
	name string
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

func ClassGetSimpleName(c *Class) *String {
	name := c.name
	if strings.HasPrefix(name, "[") {
		return StringConcat(ClassGetSimpleName(ClassGetComponentType(c)), Str("[]"))
	}
	name = name[strings.LastIndex(name, ".")+1:]
	if i := strings.LastIndex(strings.TrimSuffix(name, "$"), "$"); i >= 0 {
		// As on the JVM, an anonymous class (Outer$1) has an empty simple name, and a local
		// class (Outer$1Local) loses its digits.
		name = strings.TrimLeft(name[i+1:], "0123456789")
	}
	return Str(name)
}

func ClassIsArray(c *Class) bool { return strings.HasPrefix(c.name, "[") }

func ClassIsPrimitive(c *Class) bool {
	_, ok := primitiveDescriptors[c.name]
	return ok || c.name == "void"
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
	if ClassIsPrimitive(c) {
		return Str(c.name)
	}
	return Str("class " + c.name)
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

// ClassForName implements Class.forName. There is no class loading, so any name succeeds.
func ClassForName(name *String) *Class { return ClassOf(name.GoString()) }
