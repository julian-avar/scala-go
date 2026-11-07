package rt

import (
	"fmt"
	"math"
	"strconv"
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
		for j < len(f) && strings.IndexByte("-#+ 0123456789.(", f[j]) >= 0 {
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
			if conv|0x20 == 'g' {
				s = fmt.Sprintf("%"+spec+"g", d)
			} else {
				s = formatJavaFloating(d, spec, conv|0x20)
			}
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

// formatJavaFloating formats d for %f or %e as java.util.Formatter does: the shortest decimal
// representation of d (Double.toString's digits) is rounded half-up to the precision, where Go
// would round the exact binary value. spec holds the flags, width and precision.
func formatJavaFloating(d float64, spec string, conv byte) string {
	dot := strings.IndexByte(spec, '.')
	prec, _ := strconv.Atoi(spec[dot+1:])
	flagsAndWidth := spec[:dot]
	var body string
	switch {
	case math.IsNaN(d):
		body = "NaN"
	case math.IsInf(d, 0):
		body = "Infinity"
	default:
		// digits × 10^(exp - len(digits) + 1), with 1 <= digits[0] <= 9 (or "0")
		mant, expStr, _ := strings.Cut(strconv.FormatFloat(math.Abs(d), 'e', -1, 64), "e")
		digits := strings.Replace(mant, ".", "", 1)
		exp, _ := strconv.Atoi(expStr)
		if conv == 'f' {
			body = fixedHalfUp(digits, exp, prec)
		} else {
			body = scientificHalfUp(digits, exp, prec)
		}
	}
	negative := math.Signbit(d) && !math.IsNaN(d)
	return padJavaNumber(body, negative, flagsAndWidth)
}

// roundHalfUp keeps the first n digits (n >= 0), rounding half up; it reports a carry out of
// the first digit (the result then has n+1 digits).
func roundHalfUp(digits string, n int) (string, bool) {
	if n >= len(digits) {
		return digits + strings.Repeat("0", n-len(digits)), false
	}
	kept := []byte(digits[:n])
	if digits[n] >= '5' {
		i := n - 1
		for ; i >= 0 && kept[i] == '9'; i-- {
			kept[i] = '0'
		}
		if i < 0 {
			return "1" + string(kept), true
		}
		kept[i]++
	}
	return string(kept), false
}

func fixedHalfUp(digits string, exp, prec int) string {
	intLen := exp + 1 // digits before the decimal point
	n := intLen + prec
	var kept string
	if n < 0 {
		kept = ""
	} else {
		var carry bool
		kept, carry = roundHalfUp(digits, n)
		if carry {
			intLen++
		}
	}
	// Left-pad so that there is at least one integer digit.
	if intLen <= 0 {
		kept = strings.Repeat("0", 1-intLen) + kept
		intLen = 1
	}
	kept += strings.Repeat("0", max(0, intLen+prec-len(kept)))
	if prec == 0 {
		return kept[:intLen]
	}
	return kept[:intLen] + "." + kept[intLen:intLen+prec]
}

func scientificHalfUp(digits string, exp, prec int) string {
	if digits == "0" {
		exp = 0
	}
	kept, carry := roundHalfUp(digits, prec+1)
	if carry {
		kept = kept[:prec+1]
		exp++
	}
	sign := "+"
	if exp < 0 {
		sign, exp = "-", -exp
	}
	mantissa := kept[:1]
	if prec > 0 {
		mantissa += "." + kept[1:]
	}
	return fmt.Sprintf("%se%s%02d", mantissa, sign, exp)
}

// padJavaNumber applies the sign and the flags ('-', '+', ' ', '0', '(') and width of a numeric
// conversion to the digits of a magnitude.
func padJavaNumber(body string, negative bool, flagsAndWidth string) string {
	i := 0
	for i < len(flagsAndWidth) && strings.IndexByte("-+ 0(#,", flagsAndWidth[i]) >= 0 {
		i++
	}
	flags, width := flagsAndWidth[:i], 0
	if i < len(flagsAndWidth) {
		width, _ = strconv.Atoi(flagsAndWidth[i:])
	}
	prefix, suffix := "", ""
	switch {
	case negative && strings.Contains(flags, "("):
		prefix, suffix = "(", ")"
	case negative:
		prefix = "-"
	case strings.Contains(flags, "+"):
		prefix = "+"
	case strings.Contains(flags, " "):
		prefix = " "
	}
	pad := width - len(prefix) - len(body) - len(suffix)
	switch {
	case pad <= 0:
		return prefix + body + suffix
	case strings.Contains(flags, "-"):
		return prefix + body + suffix + strings.Repeat(" ", pad)
	case strings.Contains(flags, "0"):
		return prefix + strings.Repeat("0", pad) + body + suffix
	default:
		return strings.Repeat(" ", pad) + prefix + body + suffix
	}
}
