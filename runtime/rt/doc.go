// Package rt is the runtime support library linked into every program produced by scala-go.
//
// Value representation (provisional, see docs/design.md):
//
//   - Scala primitives map to Go primitives: Boolean=bool, Char=uint16, Byte=int8, Short=int16,
//     Int=int32, Long=int64, Float=float32, Double=float64.
//   - A boxed primitive is the Go primitive stored in an `any`.
//   - java.lang.String is *String (UTF-16, like the JVM); `null` is nil.
//   - Arrays of primitives are *Array[T]; arrays of references are *Array[any].
//   - Instances of compiled classes are pointers to generated structs, viewed through generated
//     interfaces. Generated method names are exported (prefix `M_`) so that this package can
//     call the well-known java.lang.Object methods through the interfaces below.
package rt

// Mangled Go names of the java.lang.Object methods a class may override. These must match the
// linker's name mangling (scalago.linker.Mangling).
type (
	scalaToString interface {
		M_toString_p_rLjava_dlang_dString_e() *String
	}
	scalaEquals interface {
		M_equals_pLjava_dlang_dObject_e_rZ(that any) bool
	}
	scalaHashCode interface {
		M_hashCode_p_rI() int32
	}
	// scalaClassName and scalaClass are implemented by every generated concrete class.
	scalaClassName interface {
		ScalaClassName() string
	}
	scalaClass interface {
		ScalaClass() *Class
	}
)
