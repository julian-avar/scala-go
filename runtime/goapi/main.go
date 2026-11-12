// Command goapi prints the exported API of Go packages for the scala-go linker, which uses it to
// check Go facades and to convert values at the boundary between Scala and Go.
//
// Usage:
//
//	goapi -dir <module dir> [-mod mod] <import path>...
//
// The packages are resolved in the context of the Go module in <module dir> (so that its
// requirements apply) and read from compiler export data (`go list -export`), which the Go build
// cache keeps.
//
// Output: one S-expression per line.
//
//	(package <path> <name>)
//	(func <path> <Name> <signature>)
//	(var <path> <Name> <type>)
//	(const <path> <Name> <type> <value>)        value: the exact value, as a string
//	(type <path> <Name> <kind> [<underlying>])   kind: struct, interface, or other (whose
//	                                             underlying type follows: func, basic, slice, ...);
//	                                             (interface) follows interfaces without methods
//	(tparams <path> <Type> (<name> <constraint>)...)  the type parameters of a generic type
//	(method <path> <Type> <Name> <signature> [pointer])
//	                                            method set of *Type (of Type for interfaces);
//	                                            pointer marks the methods that only *Type has, for
//	                                            types that are neither structs nor interfaces
//	(field <path> <Type> <Name> <type>)         struct fields, including promoted ones (not those
//	                                            that a method or a shallower field hides)
//	(opaque <path> <Type> <interface type>...)  a type that the APIs use but code outside its
//	                                            package cannot name (unexported, or of an internal
//	                                            package), with the exported interfaces of its
//	                                            package and of the packages it imports that it (or
//	                                            a pointer to it) implements, those with more
//	                                            methods first
//	(error <message>)
//
// Signatures are (sig (<param type>...) (<result type>...) <variadic: true/false> [generic]
// (names <param name>...) [(tparams (<name> <constraint>)...)]); unnamed parameters are "_".
// Constraints are types: (interface) for any, a named interface, or (constraint [(core T)]
// [(terms T...)] [comparable]) for those with type sets (~int | string, comparable), which only
// instantiation with concrete types satisfies: core is the type all of the set's types have as
// underlying type (~[]E), terms are the types of a union (tildes dropped).
// Types:
//
//	(basic <name>)  (named <path> <Name>)  (ptr T)  (slice T)  (array <n> T)  (map K V)
//	(chan T)  (func <signature>)  (interface)  (empty-struct)  (struct)  (tparam <name>)
//
// Builtin named types (error) have the path "".
package main

import (
	"bufio"
	"bytes"
	"flag"
	"fmt"
	"go/importer"
	"go/token"
	"go/types"
	"io"
	"os"
	"os/exec"
	"sort"
	"strconv"
	"strings"
)

func main() {
	dir := flag.String("dir", ".", "directory of the Go module the packages are resolved in")
	mod := flag.String("mod", "", "value of go list's -mod flag (e.g. mod, to update go.mod)")
	flag.Parse()
	out := bufio.NewWriter(os.Stdout)
	defer out.Flush()
	if flag.NArg() == 0 {
		return
	}

	exports, err := exportData(*dir, *mod, flag.Args())
	if err != nil {
		fmt.Fprintf(out, "(error %s)\n", strconv.Quote(err.Error()))
		return
	}
	imp := importer.ForCompiler(token.NewFileSet(), "gc", func(path string) (io.ReadCloser, error) {
		file, ok := exports[path]
		if !ok || file == "" {
			return nil, fmt.Errorf("no export data for %q", path)
		}
		return os.Open(file)
	})
	for _, path := range flag.Args() {
		requested[path] = true
	}
	for _, path := range flag.Args() {
		pkg, err := imp.Import(path)
		if err != nil {
			fmt.Fprintf(out, "(error %s)\n", strconv.Quote(fmt.Sprintf("cannot load Go package %q: %v", path, err)))
			continue
		}
		printPackage(out, pkg)
	}
	// The exported types of other packages that the printed APIs use, transitively: only them, for
	// their kinds, methods and fields (a package line first).
	printed := map[*types.Package]bool{}
	for len(referenced) > 0 {
		obj := referenced[0]
		referenced = referenced[1:]
		if !printed[obj.Pkg()] {
			printed[obj.Pkg()] = true
			fmt.Fprintf(out, "(package %s %s)\n", strconv.Quote(obj.Pkg().Path()), obj.Pkg().Name())
		}
		printType(out, strconv.Quote(obj.Pkg().Path()), obj)
	}
	for _, obj := range opaque {
		printOpaque(out, obj)
	}
}

// requested are the packages whose whole APIs are printed; referenced, the exported types of other
// packages that they use (seen, those already queued).
var (
	requested  = map[string]bool{}
	referenced []*types.TypeName
	seen       = map[*types.TypeName]bool{}
)

// reference queues the declaration of a named type of another package, if it is exported, or
// describes it as opaque if code outside its package cannot name it.
func reference(obj *types.TypeName) {
	if obj.Pkg() == nil || seen[obj] {
		return
	}
	if !obj.Exported() || isInternal(obj.Pkg().Path()) {
		seen[obj] = true
		opaque = append(opaque, obj)
		return
	}
	if requested[obj.Pkg().Path()] {
		return
	}
	seen[obj] = true
	referenced = append(referenced, obj)
}

var opaque []*types.TypeName

func isInternal(path string) bool {
	for _, e := range strings.Split(path, "/") {
		if e == "internal" || e == "vendor" {
			return true
		}
	}
	return false
}

// printOpaque describes a type that code outside its package cannot name.
func printOpaque(out io.Writer, obj *types.TypeName) {
	t := obj.Type()
	var ifaces []*types.TypeName
	var names []*types.TypeName
	for _, pkg := range append([]*types.Package{obj.Pkg()}, obj.Pkg().Imports()...) {
		if isInternal(pkg.Path()) {
			continue
		}
		for _, name := range pkg.Scope().Names() {
			if tn, ok := pkg.Scope().Lookup(name).(*types.TypeName); ok {
				names = append(names, tn)
			}
		}
	}
	for _, tn := range names {
		if !tn.Exported() || tn == obj {
			continue
		}
		iface, ok := tn.Type().Underlying().(*types.Interface)
		if !ok || iface.NumMethods() == 0 {
			continue
		}
		if named, ok := tn.Type().(*types.Named); ok && named.TypeParams().Len() > 0 {
			continue
		}
		if types.Implements(t, iface) || types.Implements(types.NewPointer(t), iface) {
			ifaces = append(ifaces, tn)
		}
	}
	sort.SliceStable(ifaces, func(i, j int) bool {
		mi := ifaces[i].Type().Underlying().(*types.Interface).NumMethods()
		mj := ifaces[j].Type().Underlying().(*types.Interface).NumMethods()
		if mi != mj {
			return mi > mj
		}
		// The type's own package first, then by path and name.
		oi, oj := ifaces[i].Pkg() == obj.Pkg(), ifaces[j].Pkg() == obj.Pkg()
		if oi != oj {
			return oi
		}
		return ifaces[i].Pkg().Path()+"."+ifaces[i].Name() < ifaces[j].Pkg().Path()+"."+ifaces[j].Name()
	})
	fmt.Fprintf(out, "(opaque %s %s", strconv.Quote(obj.Pkg().Path()), obj.Name())
	for _, i := range ifaces {
		fmt.Fprintf(out, " (named %s %s)", strconv.Quote(i.Pkg().Path()), i.Name())
	}
	fmt.Fprintln(out, ")")
}

// exportData runs `go list -export` and returns the export data file of each package and dependency.
func exportData(dir, mod string, paths []string) (map[string]string, error) {
	args := []string{"list", "-e", "-export", "-deps", "-f", "{{.ImportPath}}\t{{.Export}}\t{{if .Error}}{{.Error}}{{end}}"}
	if mod != "" {
		args = append(args, "-mod="+mod)
	}
	cmd := exec.Command("go", append(args, paths...)...)
	cmd.Dir = dir
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	output, err := cmd.Output()
	if err != nil {
		return nil, fmt.Errorf("go list failed: %v\n%s", err, stderr.String())
	}
	exports := map[string]string{}
	var problems []string
	for _, line := range strings.Split(string(output), "\n") {
		parts := strings.SplitN(line, "\t", 3)
		if len(parts) < 3 {
			continue
		}
		exports[parts[0]] = parts[1]
		if parts[2] != "" {
			problems = append(problems, strings.TrimSpace(parts[2]))
		}
	}
	if len(problems) > 0 {
		return nil, fmt.Errorf("%s", strings.Join(problems, "\n"))
	}
	return exports, nil
}

func printPackage(out io.Writer, pkg *types.Package) {
	path := strconv.Quote(pkg.Path())
	fmt.Fprintf(out, "(package %s %s)\n", path, pkg.Name())
	scope := pkg.Scope()
	for _, name := range scope.Names() {
		obj := scope.Lookup(name)
		if !obj.Exported() {
			continue
		}
		switch obj := obj.(type) {
		case *types.Func:
			fmt.Fprintf(out, "(func %s %s %s)\n", path, name, signature(obj.Type().(*types.Signature)))
		case *types.Var:
			fmt.Fprintf(out, "(var %s %s %s)\n", path, name, typ(obj.Type()))
		case *types.Const:
			fmt.Fprintf(out, "(const %s %s %s %s)\n", path, name, typ(obj.Type()), strconv.Quote(obj.Val().ExactString()))
		case *types.TypeName:
			printType(out, path, obj)
		}
	}
}

func printType(out io.Writer, path string, obj *types.TypeName) {
	name := obj.Name()
	t := types.Unalias(obj.Type())
	named, ok := t.(*types.Named)
	if !ok || named.Obj() != obj {
		// An alias of another type: describe it as that type's kind, with its methods.
		fmt.Fprintf(out, "(alias %s %s %s)\n", path, name, typ(t))
		return
	}
	if tps := named.TypeParams(); tps.Len() > 0 {
		fmt.Fprintf(out, "(tparams %s %s", path, name)
		for i := 0; i < tps.Len(); i++ {
			fmt.Fprintf(out, " (%s %s)", tps.At(i).Obj().Name(), constraint(tps.At(i).Constraint()))
		}
		fmt.Fprintln(out, ")")
	}
	_, isInterface := named.Underlying().(*types.Interface)
	_, isStruct := named.Underlying().(*types.Struct)
	switch u := named.Underlying().(type) {
	case *types.Interface:
		if u.Empty() {
			// Without methods (unexported ones included), so it holds any value.
			fmt.Fprintf(out, "(type %s %s interface (interface))\n", path, name)
		} else {
			fmt.Fprintf(out, "(type %s %s interface)\n", path, name)
		}
	case *types.Struct:
		fmt.Fprintf(out, "(type %s %s struct)\n", path, name)
	default:
		fmt.Fprintf(out, "(type %s %s other %s)\n", path, name, typ(u))
	}

	methodSet := types.NewMethodSet(types.NewPointer(named))
	if isInterface {
		methodSet = types.NewMethodSet(named)
	}
	// Values of other types are copies (not pointers, as structs are): their methods with pointer
	// receivers would change a copy.
	valueMethods := types.NewMethodSet(named)
	for i := 0; i < methodSet.Len(); i++ {
		m := methodSet.At(i).Obj().(*types.Func)
		if m.Exported() {
			marker := ""
			if !isInterface && !isStruct && valueMethods.Lookup(m.Pkg(), m.Name()) == nil {
				marker = " pointer"
			}
			fmt.Fprintf(out, "(method %s %s %s %s%s)\n", path, name, m.Name(), signature(m.Type().(*types.Signature)), marker)
		}
	}
	if st, ok := named.Underlying().(*types.Struct); ok {
		for _, f := range fields(st) {
			// A method of the same name at the same or a shallower depth hides the field.
			if obj, _, _ := types.LookupFieldOrMethod(named, true, f.Pkg(), f.Name()); obj != f {
				continue
			}
			fmt.Fprintf(out, "(field %s %s %s %s)\n", path, name, f.Name(), typ(f.Type()))
		}
	}
}

// fields returns the exported fields of a struct, including those promoted from embedded structs
// (the shallowest one wins, as in Go's selector rules; ambiguous names are dropped).
func fields(st *types.Struct) []*types.Var {
	var result []*types.Var
	seen := map[string]bool{}
	level := []*types.Struct{st}
	visited := map[*types.Struct]bool{}
	for len(level) > 0 {
		counts := map[string]int{}
		found := map[string]*types.Var{}
		var next []*types.Struct
		for _, s := range level {
			if visited[s] {
				continue
			}
			visited[s] = true
			for i := 0; i < s.NumFields(); i++ {
				f := s.Field(i)
				if f.Embedded() {
					et := types.Unalias(f.Type())
					if p, ok := et.(*types.Pointer); ok {
						et = p.Elem()
					}
					if es, ok := et.Underlying().(*types.Struct); ok {
						next = append(next, es)
					}
				}
				if f.Exported() && !seen[f.Name()] {
					counts[f.Name()]++
					found[f.Name()] = f
				}
			}
		}
		for i := 0; i < len(level); i++ {
			s := level[i]
			for j := 0; j < s.NumFields(); j++ {
				f := s.Field(j)
				if found[f.Name()] == f && counts[f.Name()] == 1 {
					result = append(result, f)
				}
			}
		}
		for name := range counts {
			seen[name] = true
		}
		level = next
	}
	return result
}

func signature(sig *types.Signature) string {
	var sb strings.Builder
	sb.WriteString("(sig (")
	for i := 0; i < sig.Params().Len(); i++ {
		if i > 0 {
			sb.WriteString(" ")
		}
		sb.WriteString(typ(sig.Params().At(i).Type()))
	}
	sb.WriteString(") (")
	for i := 0; i < sig.Results().Len(); i++ {
		if i > 0 {
			sb.WriteString(" ")
		}
		sb.WriteString(typ(sig.Results().At(i).Type()))
	}
	sb.WriteString(") ")
	sb.WriteString(strconv.FormatBool(sig.Variadic()))
	if sig.TypeParams().Len() > 0 {
		sb.WriteString(" generic")
	}
	sb.WriteString(" (names")
	for i := 0; i < sig.Params().Len(); i++ {
		name := sig.Params().At(i).Name()
		if name == "" {
			name = "_"
		}
		sb.WriteString(" " + name)
	}
	sb.WriteString(")")
	if tps := sig.TypeParams(); tps.Len() > 0 {
		sb.WriteString(" (tparams")
		for i := 0; i < tps.Len(); i++ {
			tp := tps.At(i)
			sb.WriteString(" (" + tp.Obj().Name() + " " + constraint(tp.Constraint()) + ")")
		}
		sb.WriteString(")")
	}
	sb.WriteString(")")
	return sb.String()
}

// constraint describes a type parameter's constraint: a type when it is a basic interface (only
// methods), which the interface type itself satisfies; (constraint) otherwise.
func constraint(c types.Type) string {
	iface, ok := c.Underlying().(*types.Interface)
	if !ok || !iface.IsMethodSet() {
		return typeSet(iface)
	}
	if iface.Empty() {
		return "(interface)"
	}
	if named, ok := types.Unalias(c).(*types.Named); ok && named.TypeArgs().Len() == 0 {
		return typ(named)
	}
	return typeSet(iface)
}

// typeSet describes a constraint with a type set: its core type, if it has one, and the terms of
// its unions.
func typeSet(iface *types.Interface) string {
	var sb strings.Builder
	sb.WriteString("(constraint")
	if iface == nil {
		return sb.String() + ")"
	}
	var terms []types.Type
	for i := 0; i < iface.NumEmbeddeds(); i++ {
		switch e := iface.EmbeddedType(i).(type) {
		case *types.Union:
			for j := 0; j < e.Len(); j++ {
				terms = append(terms, e.Term(j).Type())
			}
		default:
			if _, isInterface := e.Underlying().(*types.Interface); !isInterface {
				terms = append(terms, e)
			}
		}
	}
	if len(terms) > 0 {
		core := terms[0].Underlying()
		for _, t := range terms[1:] {
			if !types.Identical(t.Underlying(), core) {
				core = nil
				break
			}
		}
		if core != nil {
			sb.WriteString(" (core " + typ(core) + ")")
		}
		sb.WriteString(" (terms")
		for _, t := range terms {
			sb.WriteString(" " + typ(t))
		}
		sb.WriteString(")")
	}
	if iface.IsComparable() {
		sb.WriteString(" comparable")
	}
	return sb.String() + ")"
}

func typ(t types.Type) string {
	switch t := types.Unalias(t).(type) {
	case *types.Basic:
		return "(basic " + strings.ReplaceAll(t.Name(), " ", "-") + ")"
	case *types.Named:
		reference(t.Origin().Obj())
		path := ""
		if t.Obj().Pkg() != nil {
			path = t.Obj().Pkg().Path()
		}
		if args := t.TypeArgs(); args.Len() > 0 {
			s := "(generic " + strconv.Quote(path) + " " + t.Obj().Name()
			for i := 0; i < args.Len(); i++ {
				s += " " + typ(args.At(i))
			}
			return s + ")"
		}
		return "(named " + strconv.Quote(path) + " " + t.Obj().Name() + ")"
	case *types.Pointer:
		return "(ptr " + typ(t.Elem()) + ")"
	case *types.Slice:
		return "(slice " + typ(t.Elem()) + ")"
	case *types.Array:
		return "(array " + strconv.FormatInt(t.Len(), 10) + " " + typ(t.Elem()) + ")"
	case *types.Map:
		return "(map " + typ(t.Key()) + " " + typ(t.Elem()) + ")"
	case *types.Chan:
		switch t.Dir() {
		case types.RecvOnly:
			return "(recv-chan " + typ(t.Elem()) + ")"
		case types.SendOnly:
			return "(send-chan " + typ(t.Elem()) + ")"
		}
		return "(chan " + typ(t.Elem()) + ")"
	case *types.Signature:
		return "(func " + signature(t) + ")"
	case *types.Interface:
		if t.Empty() {
			return "(interface)"
		}
		return "(interface-literal)"
	case *types.Struct:
		if t.NumFields() == 0 {
			return "(empty-struct)"
		}
		return "(struct)"
	case *types.TypeParam:
		return "(tparam " + t.Obj().Name() + ")"
	}
	return "(unknown " + strconv.Quote(t.String()) + ")"
}
