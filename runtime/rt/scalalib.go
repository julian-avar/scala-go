package rt

import "math"

// Natives for Java classes of the Scala library (scala.runtime.Statics, BoxesRunTime).

// ---- scala.runtime.Statics (MurmurHash3 mixing and `##`) ----------------------------------

func rotl(x int32, n uint) int32 { return int32(uint32(x)<<n | uint32(x)>>(32-n)) }

func StaticsMixLast(hash, data int32) int32 {
	k := data
	k *= -862048943 // 0xcc9e2d51
	k = rotl(k, 15)
	k *= 461845907 // 0x1b873593
	return hash ^ k
}

func StaticsMix(hash, data int32) int32 {
	h := StaticsMixLast(hash, data)
	h = rotl(h, 13)
	return h*5 + -430675100 // 0xe6546b64
}

func StaticsAvalanche(h int32) int32 {
	h ^= int32(uint32(h) >> 16)
	h *= -2048144789 // 0x85ebca6b
	h ^= int32(uint32(h) >> 13)
	h *= -1028477387 // 0xc2b2ae35
	h ^= int32(uint32(h) >> 16)
	return h
}

func StaticsFinalizeHash(hash, length int32) int32 { return StaticsAvalanche(hash ^ length) }

func StaticsLongHash(lv int64) int32 {
	if iv := int32(lv); int64(iv) == lv {
		return iv
	}
	return HashCode(lv)
}

func StaticsDoubleHash(dv float64) int32 {
	if iv := int32(dv); float64(iv) == dv {
		return iv
	}
	if lv := int64(dv); float64(lv) == dv && !math.IsInf(dv, 0) {
		return HashCode(lv)
	}
	if fv := float32(dv); float64(fv) == dv {
		return HashCode(fv)
	}
	return HashCode(dv)
}

func StaticsFloatHash(fv float32) int32 {
	if iv := int32(fv); float32(iv) == fv {
		return iv
	}
	if lv := int64(fv); float32(lv) == fv && !math.IsInf(float64(fv), 0) {
		return HashCode(lv)
	}
	return HashCode(fv)
}

// AnyHash implements `x.##`: like hashCode, but consistent with cooperative equality (1.## == 1L.##).
func AnyHash(x any) int32 {
	switch v := x.(type) {
	case nil:
		return 0
	case int64:
		return StaticsLongHash(v)
	case float64:
		return StaticsDoubleHash(v)
	case float32:
		return StaticsFloatHash(v)
	case int8:
		return int32(v)
	case int16:
		return int32(v)
	case uint16:
		return int32(v)
	}
	return HashCode(x)
}

func IsBoxedNumber(x any) bool {
	switch x.(type) {
	case int8, int16, int32, int64, float32, float64:
		return true
	}
	return false
}

func IsBoxedNumberOrBoolean(x any) bool {
	_, isBool := x.(bool)
	return isBool || IsBoxedNumber(x)
}

// PfMarker is scala.runtime.Statics.pfMarker, a sentinel used by PartialFunction.applyOrElse.
var PfMarker any = &struct{ marker byte }{}
