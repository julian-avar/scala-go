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

var (
	classesMu sync.Mutex
	classes   = map[string]*Class{}
)

func ClassOf(name string) *Class {
	classesMu.Lock()
	defer classesMu.Unlock()
	c, ok := classes[name]
	if !ok {
		c = &Class{name: name}
		classes[name] = c
	}
	return c
}

var primitiveDescriptors = map[string]string{
	"boolean": "Z", "char": "C", "byte": "B", "short": "S", "int": "I", "long": "J", "float": "F", "double": "D",
}

// GetClass implements x.getClass().
func GetClass(x any) *Class {
	switch v := x.(type) {
	case nil:
		ThrowKind(NullPointer, "")
	case *String:
		return ClassOf("java.lang.String")
	case bool:
		return ClassOf("java.lang.Boolean")
	case uint16:
		return ClassOf("java.lang.Character")
	case int8:
		return ClassOf("java.lang.Byte")
	case int16:
		return ClassOf("java.lang.Short")
	case int32:
		return ClassOf("java.lang.Integer")
	case int64:
		return ClassOf("java.lang.Long")
	case float32:
		return ClassOf("java.lang.Float")
	case float64:
		return ClassOf("java.lang.Double")
	case *BoxedUnit:
		return ClassOf("scala.runtime.BoxedUnit")
	case *Class:
		return ClassOf("java.lang.Class")
	case *BigInteger:
		return ClassOf("java.math.BigInteger")
	case *BigDecimal:
		return ClassOf("java.math.BigDecimal")
	case *Array[bool]:
		return ClassOf("[Z")
	case *Array[uint16]:
		return ClassOf("[C")
	case *Array[int8]:
		return ClassOf("[B")
	case *Array[int16]:
		return ClassOf("[S")
	case *Array[int32]:
		return ClassOf("[I")
	case *Array[int64]:
		return ClassOf("[J")
	case *Array[float32]:
		return ClassOf("[F")
	case *Array[float64]:
		return ClassOf("[D")
	case *Array[any]:
		return ClassOf("[Ljava.lang.Object;")
	case scalaClassName:
		return ClassOf(v.ScalaClassName())
	case *Object:
		return ClassOf("java.lang.Object")
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
		name = name[i+1:]
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
