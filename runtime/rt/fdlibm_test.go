package rt

import (
	"math"
	"strconv"
	"strings"
	"testing"
)

// strictMathGolden are results of the JDK's StrictMath: the function, its arguments and its
// result as hexadecimal bits (a sample of tens of thousands, over every function and range).
const strictMathGolden = `
	exp bff0000000000000 3fd78b56362cef38
	exp 2abc9c0b43cfc3f6 3ff0000000000000
	exp 3ff1c367815e4e88 400847a5a8af41e6
	exp 4044fa5ad5510e71 43b7188c94a2a71a
	exp 3ed0c5badf2ba171 3ff00004316f4473
	log c0351506f0f6dd76 fff8000000000000
	log c00139b1b4531cd5 fff8000000000000
	log bfe9c7e1584a6254 fff8000000000000
	log bfa071cf6e126d40 fff8000000000000
	log10 beaa4bd50226650d fff8000000000000
	log10 41116750521780e7 4015d1facb608402
	log10 3ffe2525f97c35f4 3fd19b3007864f18
	log10 bebba99b055bf27c fff8000000000000
	log1p 1 1
	log1p b3e534551b0cd524 b3e534551b0cd524
	log1p db2c55b47b93e28 db2c55b47b93e28
	log1p bffcf03a08952080 7ff8000000000000
	log1p 3ed392ae41f08346 3ed392ab43bd4d76
	expm1 c046b13d74852972 bff0000000000000
	expm1 9c0e156ad8ea79ea 9c0e156ad8ea79ea
	expm1 41105eae23e54188 7ff0000000000000
	expm1 40fd8550b95225fc 7ff0000000000000
	sinh 4749f766dd8fce70 7ff0000000000000
	sinh 3ec00173687586ae 3ec001736875915b
	sinh 3fff13fed88502f8 400b53f32f7cad07
	sinh cef2c4ef2aa521b cef2c4ef2aa521b
	cosh bfe7fa98a3704450 3ff4b4cd55a4c87f
	cosh 403ea090a9c97b05 42a232ba395c9b40
	cosh 3f871af628b60800 3ff00042bb90aad7
	cosh 40348565913c980f 41b858c9a67396a0
	cosh 48221416ef8e8365 7ff0000000000000
	tanh 98bb4dc0ffd09285 98bb4dc0ffd09285
	tanh 6108f268d6446ab 6108f268d6446ab
	tanh 3eb626bb5a15b5f7 3eb626bb5a15a7d0
	tanh bff674bdef9bf690 bfec5afb4a87cd29
	cbrt 37012f954d368047 3cf4a51b78375bad
	cbrt 403f66b1c671e413 40093d2df15ff731
	cbrt cfbc3d871a1e2d81 c533560dd690b7f7
	cbrt c01c881c6d3b15bd bffecca6646efe17
	sin beb975ac7fa3202d beb975ac7fa3156f
	sin 4013ad97bdea120b bfef50e37d38cbb8
	sin ce6efab93f604c04 bfda80dbd1691cc9
	sin 3ebee5e9d9c540f2 3ebee5e9d9c52dbe
	sin ac37853d47825525 ac37853d47825525
	cos 3ecfbc30cd23aec7 3fefffffffff0438
	cos edff4b9ce0886615 bfebf87d88c7fd96
	cos 4100309ed8d81e41 bfe72b37b5c809a7
	cos 3ec1d1770e240bca 3fefffffffffb0a1
	tan 6372f02ebb39243c 4022cae3b7f03af9
	tan beb6cdf9f3c58630 beb6cdf9f3c595a1
	tan c043678ed325f2b1 c0001caf3a935777
	tan 4012ce6c68284370 405723bd4b7116c1
	asin 36ba7330ad490bc7 36ba7330ad490bc7
	asin 3eb034791e336bdf 3eb034791e336ea4
	asin 411e412485899b62 fff8000000000000
	asin 9abfe86854736fe 9abfe86854736fe
	asin c02b4e041c1d4cc5 fff8000000000000
	acos 9b265bb41531a958 3ff921fb54442d18
	acos bff15c25afe4d42e fff8000000000000
	acos 3ec1471cb712c3bb 3ff921f92b609636
	acos 411588715747ea61 fff8000000000000
	atan 41125396fafd8957 3ff921f7d642ef55
	atan c0f7daa8fdc4621f bff921f098e71564
	atan 5bb5d0a791016bc6 3ff921fb54442d18
	atan be9c1de0ab8c19f9 be9c1de0ab8c182a
	pow 3ff0000000000000 4086300000000000 3ff0000000000000
	pow 7e37e43c8800759c 7ff0000000000000 7ff0000000000000
	pow bfce2ed665719ca0 c0365668fd4b4788 fff8000000000000
	pow c0f32d3e343e836f c036368e7babfb8f fff8000000000000
	pow 400d99999999999a bfc78bf5a46dfe38 3fe927ae0bff97d4
	pow bfa4942d3c9aa6c0 4033c9cfbae175d7 fff8000000000000
	pow c045f917917da444 c032000000000000 39cb22a6ed110be0
	pow 4108fb76c499a7d0 4024e50348f55b24 4b73f87b52db24dc
	pow 3ec78f561d8adb1e c01c000000000000 48010e35fc627dfd
	pow bed34f22eb0e678c bff00a86d572815e fff8000000000000
	atan2 4024000000000000 400921f9f01b866e 3ff4432d746de420
	atan2 a3c346d99444bb35 4020000000000000 a39346d99444bb35
	atan2 67b5e08ed15991d4 c028000000000000 3ff921fb54442d19
	atan2 3d55b52eaf0ca0f1 4019f81e192d9935 3d2abfb7f18aac0c
	atan2 c037a8836e9e159c c020f688cb0ec84e bffea3f0a2f58933
	atan2 3ffb1cc404a0ae48 c0305ba568741ebb 40084e9569a3c819
	atan2 3fe5c0ee36282344 401ef31b0d48d6fb 3fb66f2e79f1a6e3
	atan2 3eb66122f0256ae3 c026000000000000 400921fb43fd880d
	atan2 beab3d0f8e4731f9 c018000000000000 c00921fb421b780f
	atan2 35ab346e85dc03f6 4010000000000000 358b346e85dc03f6
	hypot 7ff8000000000000 40862e3d70a3d70a 7ff8000000000000
	hypot 3ec6c1c70c2fb767 401220675c8035b4 401220675c803946
	hypot c00c02839f04a597 4021a55012ee6d38 4022fc00d717cd81
	hypot bebb9582686fa1c6 c03004cddad8d56d 403004cddad8d585
	hypot 48d09b5929768f43 403120f397166857 48d09b5929768f43
	hypot 40ea211c0f1b074f 4035b8f38cf70980 40ea211c333927da
	hypot c10258c4306903f9 bfecdf0f6be3a064 410258c4306a6f70
	hypot 3ec648b3ed374699 402a000000000000 402a000000000099
	hypot ef29a1e31f439087 c038425f87f5b1a2 6f29a1e31f439087
	hypot 6bb67741337e67da 4024f3d3cd2c371c 6bb67741337e67da
`

func TestStrictMathMatchesJDK(t *testing.T) {
	unary := map[string]func(float64) float64{"exp": StrictExp, "log": StrictLog, "log10": StrictLog10,
		"log1p": StrictLog1p, "expm1": StrictExpm1, "sinh": StrictSinh, "cosh": StrictCosh, "tanh": StrictTanh,
		"cbrt": StrictCbrt, "sin": StrictSin, "cos": StrictCos, "tan": StrictTan, "asin": StrictAsin,
		"acos": StrictAcos, "atan": StrictAtan}
	binary := map[string]func(a, b float64) float64{"pow": StrictPow, "atan2": StrictAtan2, "hypot": StrictHypot}
	bits := func(s string) float64 {
		v, err := strconv.ParseUint(s, 16, 64)
		if err != nil {
			t.Fatal(err)
		}
		return math.Float64frombits(v)
	}
	for _, line := range strings.Split(strings.TrimSpace(strictMathGolden), "\n") {
		f := strings.Fields(line)
		var got, want float64
		if fn, ok := unary[f[0]]; ok {
			got, want = fn(bits(f[1])), bits(f[2])
		} else {
			got, want = binary[f[0]](bits(f[1]), bits(f[2])), bits(f[3])
		}
		if math.Float64bits(got) != math.Float64bits(want) && !(got != got && want != want) {
			t.Errorf("%s: got %x", line, math.Float64bits(got))
		}
	}
}
