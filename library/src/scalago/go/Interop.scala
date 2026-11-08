package scalago.go

import scala.annotation.StaticAnnotation

/** Declares a Go facade: Scala declarations that stand for Go code.
 *
 *  On an `object`, it stands for the Go package `path`. Its methods are the package's functions
 *  (or, when declared without a parameter list, its variables and constants). Classes and traits
 *  nested in the object are the package's types:
 *  {{{
 *  @GoImport("strings")
 *  object strings:
 *    def Repeat(s: String, count: Int): String = native
 *    def NewReplacer(oldnew: String*): Replacer = native
 *
 *    class Builder:                      // Go type strings.Builder
 *      def WriteString(s: String): Int = native
 *      def String(): String = native
 *    class Replacer:
 *      def Replace(s: String): String = native
 *  }}}
 *  On a class or trait, it stands for the Go type `name` (by default, the class's own name) of
 *  package `path`. Traits stand for Go interface types; Scala classes may implement them.
 *
 *  Facade members are `= native` (abstract members are fine in traits). Use `def`, not `val`.
 *  The linker checks every facade member it uses against the Go package's API, and converts
 *  values according to the Scala types declared here and the Go types of the real declaration:
 *
 *  | Scala                          | Go                                                         |
 *  |--------------------------------|------------------------------------------------------------|
 *  | `Int`, `Long`, `Double`, ...   | any numeric type (`int`, `uint8`, ...), converted as Go does |
 *  | `Boolean`                      | `bool`                                                     |
 *  | `String`                       | `string` (copied and transcoded)                           |
 *  | [[GoString]]                   | `string` (no conversion)                                   |
 *  | [[GoError]]                    | `error`                                                    |
 *  | a facade class or trait        | its Go type (struct types also as pointers)               |
 *  | `Array[T]`                     | `[]T` (shared when the element types match, else copied)  |
 *  | `T*` (last parameter)          | a variadic parameter `...T`                                |
 *  | `(A, B) => C`                  | `func(A, B) C`                                             |
 *  | `(A, B)` (result)              | two results                                                |
 *  | `Any`, `AnyRef`                | `any` and other interface types                            |
 *
 *  When a Go function returns an `error` that the facade's result type leaves out, a non-nil
 *  error is thrown as a [[GoException]].
 *
 *  On the JVM, calling a facade member throws `UnsupportedOperationException`.
 */
final class GoImport(path: String, name: String = "") extends StaticAnnotation

/** Exports Scala code to Go, for library builds (Scala.js's `@JSExportTopLevel`/`@JSExport`).
 *
 *  On a method of a top-level `object`, it becomes a Go function of the library's package. On a
 *  class, it becomes a Go interface type of the same name, with a constructor function
 *  `New<Name>` for its primary constructor; its methods marked `@GoExport` are the interface's
 *  methods. Go names are the Scala names capitalized, unless `name` is given:
 *  {{{
 *  object Api:
 *    @GoExport def greet(name: String): String = s"Hello, \$name"   // func Greet(name string) string
 *
 *  @GoExport class Counter(start: Int):                            // type Counter interface { ... }
 *    private var n = start                                         // func NewCounter(start int32) Counter
 *    @GoExport def inc(): Unit = synchronized { n += 1 }           // Inc()
 *    @GoExport("Value") def current: Int = synchronized(n)         // Value() int32
 *  }}}
 *  Signatures are converted with the same rules as facades, with Go types chosen from the Scala
 *  types: `Int` is `int32`, `GoInt` is `int`, `String` and `GoString` are `string`, `GoSlice[T]`
 *  is `[]T`, exported classes are their interface types, Scala functions are Go funcs. A method
 *  annotated with `@throws` gets a trailing `error` result, which Scala exceptions become;
 *  otherwise exceptions are Go panics. Exported code may be called from any goroutine.
 */
final class GoExport(name: String = "") extends StaticAnnotation

/** The Go name of a facade member or type, when it differs from the Scala name. */
final class GoName(name: String) extends StaticAnnotation

/** The body of Go facade members. */
def native: Nothing =
  throw UnsupportedOperationException("Go facade member called on the JVM: it only exists in programs compiled to Go")

/** Go's predeclared `error` interface. */
@GoImport("", "error")
trait GoError:
  def Error(): String

/** A Go `error` returned by a facade call that does not return it to Scala. */
class GoException(val error: GoError) extends RuntimeException(error.Error())
