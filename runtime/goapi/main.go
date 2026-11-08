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
//	(method <path> <Type> <Name> <signature>)   method set of *Type (of Type for interfaces)
//	(field <path> <Type> <Name> <type>)         struct fields, including promoted ones
//	(error <message>)
//
// Signatures are (sig (<param type>...) (<result type>...) <variadic: true/false> [generic]
// (names <param name>...) [(tparams (<name> <constraint>)...)]); unnamed parameters are "_".
// Constraints are types: (interface) for any, a named interface, or (constraint) for those with
// type sets (~int | string, comparable), which only instantiation with concrete types satisfies.
// Types:
//
//	(basic <name>)  (named <path> <Name>)  (ptr T)  (slice T)  (array <n> T)  (map K V)
//	(chan T)  (func <signature>)  (interface)  (struct)  (tparam <name>)
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
		pkg, err := imp.Import(path)
		if err != nil {
			fmt.Fprintf(out, "(error %s)\n", strconv.Quote(fmt.Sprintf("cannot load Go package %q: %v", path, err)))
			continue
		}
		printPackage(out, pkg)
	}
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
	_, isInterface := named.Underlying().(*types.Interface)
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
	for i := 0; i < methodSet.Len(); i++ {
		m := methodSet.At(i).Obj().(*types.Func)
		if m.Exported() {
			fmt.Fprintf(out, "(method %s %s %s %s)\n", path, name, m.Name(), signature(m.Type().(*types.Signature)))
		}
	}
	if st, ok := named.Underlying().(*types.Struct); ok {
		for _, f := range fields(st) {
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
		return "(constraint)"
	}
	if iface.Empty() {
		return "(interface)"
	}
	if named, ok := types.Unalias(c).(*types.Named); ok && named.TypeArgs().Len() == 0 {
		return typ(named)
	}
	return "(constraint)"
}

func typ(t types.Type) string {
	switch t := types.Unalias(t).(type) {
	case *types.Basic:
		return "(basic " + strings.ReplaceAll(t.Name(), " ", "-") + ")"
	case *types.Named:
		path := ""
		if t.Obj().Pkg() != nil {
			path = t.Obj().Pkg().Path()
		}
		if t.TypeArgs().Len() > 0 {
			return "(generic " + strconv.Quote(path) + " " + t.Obj().Name() + ")"
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
		return "(struct)"
	case *types.TypeParam:
		return "(tparam " + t.Obj().Name() + ")"
	}
	return "(unknown " + strconv.Quote(t.String()) + ")"
}
