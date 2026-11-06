package rt

import (
	"fmt"
	"strings"
)

// StringFormat implements String.format for the common conversions (%s %d %f %e %g %x %o %c %b %n %%)
// with flags, width and precision. Locale-specific forms (grouping with ',') are not supported.
func StringFormat(format *String, args *Array[any]) *String {
	f := format.GoString()
	var out strings.Builder
	argIndex := 0
	for i := 0; i < len(f); i++ {
		c := f[i]
		if c != '%' {
			out.WriteByte(c)
			continue
		}
		j := i + 1
		for j < len(f) && strings.IndexByte("-#+ 0123456789.", f[j]) >= 0 {
			j++
		}
		if j >= len(f) {
			ThrowKind(IllegalArgument, "UnknownFormatConversionException: '%'")
		}
		spec, conv := f[i+1:j], f[j]
		i = j
		switch conv {
		case '%':
			out.WriteByte('%')
			continue
		case 'n':
			out.WriteByte('\n')
			continue
		}
		if argIndex >= len(args.Data) {
			ThrowKind(IllegalArgument, "MissingFormatArgumentException: Format specifier '%"+spec+string(conv)+"'")
		}
		arg := args.Data[argIndex]
		argIndex++
		upper := conv >= 'A' && conv <= 'Z'
		var s string
		switch conv | 0x20 {
		case 's':
			s = fmt.Sprintf("%"+spec+"s", ToGoString(arg))
		case 'b':
			s = fmt.Sprintf("%"+spec+"s", fmt.Sprint(arg != nil && arg != false))
		case 'c':
			s = fmt.Sprintf("%"+spec+"c", rune(Cast[uint16](arg)))
		case 'd', 'x', 'o':
			verb := string(conv | 0x20)
			s = fmt.Sprintf("%"+spec+verb, arg)
		case 'f', 'e', 'g':
			var d float64
			switch v := arg.(type) {
			case float32:
				d = float64(v)
			case float64:
				d = v
			default:
				ThrowKind(IllegalArgument, "IllegalFormatConversionException: "+string(conv)+" != "+GetClass(arg).name)
			}
			if !strings.Contains(spec, ".") {
				spec += ".6"
			}
			s = fmt.Sprintf("%"+spec+string(conv|0x20), d)
		default:
			ThrowKind(IllegalArgument, "UnknownFormatConversionException: Conversion = '"+string(conv)+"'")
		}
		if upper {
			s = strings.ToUpper(s)
		}
		out.WriteString(s)
	}
	return Str(out.String())
}
