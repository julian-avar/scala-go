package rt

import "testing"

var decimal128 = int64(34)<<3 | int64(RoundHalfEven)

func BenchmarkBigDecimalAdd(b *testing.B) {
	x := NewBigDecimalFromString(Str("0.01"))
	sum := BigDecimalZero
	for range b.N {
		sum = BigDecimalAdd(sum, x)
	}
}

func BenchmarkBigDecimalAddMC(b *testing.B) {
	x := NewBigDecimalFromString(Str("0.01"))
	sum := BigDecimalZero
	for range b.N {
		sum = BigDecimalAddMC(sum, x, decimal128)
	}
}

func BenchmarkBigDecimalMultiplySetScale(b *testing.B) {
	price := NewBigDecimalFromString(Str("19.99"))
	qty := NewBigDecimalFromString(Str("0.37"))
	for range b.N {
		BigDecimalSetScale(BigDecimalMultiplyMC(price, qty, decimal128), 2, RoundHalfUp)
	}
}

func BenchmarkBigDecimalParse(b *testing.B) {
	s := Str("12345.67")
	for range b.N {
		NewBigDecimalFromString(s)
	}
}

func BenchmarkBigDecimalToString(b *testing.B) {
	x := NewBigDecimalFromString(Str("12345.67"))
	for range b.N {
		BigDecimalToString(x)
	}
}

func BenchmarkBigDecimalDivideMC(b *testing.B) {
	x := NewBigDecimalFromString(Str("12345.67"))
	y := NewBigDecimalFromString(Str("3"))
	for range b.N {
		BigDecimalDivideMC(x, y, decimal128)
	}
}

func BenchmarkBigDecimalCompareTo(b *testing.B) {
	x := NewBigDecimalFromString(Str("12345.67"))
	y := NewBigDecimalFromString(Str("12345.670"))
	for range b.N {
		BigDecimalCompareTo(x, y)
	}
}
