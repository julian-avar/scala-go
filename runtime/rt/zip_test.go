package rt

import (
	"bytes"
	"compress/flate"
	"compress/zlib"
	"hash/adler32"
	"hash/crc32"
	"io"
	"math/rand"
	"runtime"
	"testing"
	"time"
)

func javaBytes(b []byte) *Array[int8] { return ArrayOfSlice(Reinterpret[byte, int8](b)) }

// testData is compressible text with some noise.
func testData(n int) []byte {
	r := rand.New(rand.NewSource(1))
	words := []string{"alpha ", "beta ", "gamma ", "delta\n", "epsilon "}
	var b bytes.Buffer
	for b.Len() < n {
		b.WriteString(words[r.Intn(len(words))])
		if r.Intn(10) == 0 {
			b.WriteByte(byte(r.Intn(256)))
		}
	}
	return b.Bytes()[:n]
}

// deflateAll compresses data given in chunks of inChunk bytes, draining into outChunk-byte
// buffers, as DeflaterOutputStream does.
func deflateAll(t *testing.T, nowrap bool, data []byte, inChunk, outChunk int, level int32, flush int32) []byte {
	t.Helper()
	d := ZipNewDeflater(nowrap)
	var out bytes.Buffer
	buf := make([]byte, outChunk)
	drain := func(in []byte, mode int32) {
		first := true
		for {
			var n int32
			if first {
				n = ZipDeflate(d, javaBytes(in), 0, int32(len(in)), javaBytes(buf), 0, int32(len(buf)), level, 0, mode)
				first = false
			} else {
				n = ZipDeflate(d, javaBytes(nil), 0, 0, javaBytes(buf), 0, int32(len(buf)), level, 0, mode)
			}
			out.Write(buf[:n])
			if n == 0 && (mode != zFinish || ZipFinished(d)) {
				return
			}
		}
	}
	for len(data) > 0 {
		c := min(inChunk, len(data))
		drain(data[:c], flush)
		data = data[c:]
	}
	drain(nil, zFinish)
	if !ZipFinished(d) {
		t.Fatal("deflater not finished")
	}
	return out.Bytes()
}

// inflateAll decompresses data given in chunks of inChunk bytes into outChunk-byte buffers, as
// InflaterInputStream does, and returns the output and the number of input bytes left over.
func inflateAll(t *testing.T, nowrap bool, data []byte, inChunk, outChunk int) ([]byte, int) {
	t.Helper()
	z := ZipNewInflater(nowrap)
	defer ZipEnd(z)
	var out bytes.Buffer
	buf := make([]byte, outChunk)
	for !ZipFinished(z) {
		n := ZipInflate(z, javaBytes(buf), 0, int32(len(buf)))
		if n < 0 {
			t.Fatalf("inflate failed: %s", ZipErrorMessage(z).GoString())
		}
		out.Write(buf[:n])
		if n == 0 && ZipRemaining(z) == 0 && !ZipFinished(z) {
			if len(data) == 0 {
				t.Fatal("inflater wants more input than there is")
			}
			c := min(inChunk, len(data))
			ZipSetInput(z, javaBytes(data), 0, int32(c))
			data = data[c:]
		}
	}
	return out.Bytes(), int(ZipRemaining(z)) + len(data)
}

func TestDeflateInflateRoundTrips(t *testing.T) {
	data := testData(200_000)
	for _, nowrap := range []bool{false, true} {
		for _, flush := range []int32{zNoFlush, zSyncFlush, zFullFlush} {
			for _, chunks := range [][2]int{{1 << 20, 1 << 20}, {1000, 7}, {64 << 10, 512}} {
				compressed := deflateAll(t, nowrap, data, chunks[0], chunks[1], -1, flush)
				for _, in := range []int{1, 13, 4096, 1 << 20} {
					got, left := inflateAll(t, nowrap, compressed, in, chunks[1])
					if !bytes.Equal(got, data) || left != 0 {
						t.Fatalf("nowrap=%v flush=%d chunks=%v in=%d: round trip differs (left %d)", nowrap, flush, chunks, in, left)
					}
				}
				if !nowrap {
					// Other zlib implementations read it.
					r, err := zlib.NewReader(bytes.NewReader(compressed))
					if err != nil {
						t.Fatal(err)
					}
					if got, err := io.ReadAll(r); err != nil || !bytes.Equal(got, data) {
						t.Fatalf("compress/zlib cannot read it: %v", err)
					}
				}
			}
		}
	}
}

func TestZlibHeaderLevels(t *testing.T) {
	for level, want := range map[int32]byte{-1: 0x9c, 0: 0x01, 1: 0x01, 3: 0x5e, 6: 0x9c, 9: 0xda} {
		out := deflateAll(t, false, []byte("x"), 10, 100, level, zNoFlush)
		if out[0] != 0x78 || out[1] != want {
			t.Errorf("level %d: header %x", level, out[:2])
		}
	}
}

func TestLevelChangeMidStream(t *testing.T) {
	data := testData(100_000)
	d := ZipNewDeflater(false)
	var out bytes.Buffer
	buf := make([]byte, 1000)
	for i, level := range []int32{1, 9, 0, 5} {
		part := data[i*25_000 : (i+1)*25_000]
		mode := int32(zNoFlush)
		if i == 3 {
			mode = zFinish
		}
		n := ZipDeflate(d, javaBytes(part), 0, int32(len(part)), javaBytes(buf), 0, 1000, level, 0, mode)
		for out.Write(buf[:n]); n > 0; out.Write(buf[:n]) {
			n = ZipDeflate(d, javaBytes(nil), 0, 0, javaBytes(buf), 0, 1000, level, 0, mode)
		}
	}
	if !ZipFinished(d) || ZipBytesRead(d) != 100_000 || ZipBytesWritten(d) != int64(out.Len()) {
		t.Fatal("deflater state")
	}
	got, _ := inflateAll(t, false, out.Bytes(), 333, 4096)
	if !bytes.Equal(got, data) {
		t.Fatal("round trip differs")
	}
}

func TestInflaterRemainingAndTotals(t *testing.T) {
	data := testData(10_000)
	var b bytes.Buffer
	w, _ := flate.NewWriter(&b, 6)
	w.Write(data)
	w.Close()
	compressed := b.Len()
	b.WriteString("TRAILER!")
	z := ZipNewInflater(true)
	defer ZipEnd(z)
	ZipSetInput(z, javaBytes(b.Bytes()), 0, int32(b.Len()))
	out := make([]byte, 20_000)
	n := 0
	for !ZipFinished(z) {
		n += int(ZipInflate(z, javaBytes(out), int32(n), int32(len(out)-n)))
	}
	if n != len(data) || !bytes.Equal(out[:n], data) {
		t.Fatal("output differs")
	}
	if ZipRemaining(z) != 8 || ZipBytesRead(z) != int64(compressed) || ZipBytesWritten(z) != int64(len(data)) {
		t.Fatalf("remaining %d, read %d of %d", ZipRemaining(z), ZipBytesRead(z), compressed)
	}
	if ZipAdler(z) != 0 {
		t.Fatal("raw inflaters have no checksum")
	}
}

func TestZlibAdlerAndChecks(t *testing.T) {
	data := testData(5000)
	compressed := deflateAll(t, false, data, 100, 100, 6, zNoFlush)
	z := ZipNewInflater(false)
	ZipSetInput(z, javaBytes(compressed), 0, int32(len(compressed)))
	out := make([]byte, 6000)
	n := ZipInflate(z, javaBytes(out), 0, 6000)
	if int(n) != len(data) || !ZipFinished(z) || uint32(ZipAdler(z)) != adler32.Checksum(data) {
		t.Fatalf("inflated %d, finished %v", n, ZipFinished(z))
	}
	ZipEnd(z)

	corrupt := bytes.Clone(compressed)
	corrupt[len(corrupt)-1] ^= 1
	z = ZipNewInflater(false)
	ZipSetInput(z, javaBytes(corrupt), 0, int32(len(corrupt)))
	for ZipInflate(z, javaBytes(out), 0, 6000) > 0 {
	}
	if ZipInflate(z, javaBytes(out), 0, 6000) != -1 || ZipErrorMessage(z).GoString() != "incorrect data check" {
		t.Fatal("bad trailer not detected")
	}
	ZipEnd(z)

	z = ZipNewInflater(false)
	ZipSetInput(z, javaBytes([]byte{1, 2, 3}), 0, 3)
	if ZipInflate(z, javaBytes(out), 0, 10) != -1 || ZipErrorMessage(z).GoString() != "incorrect header check" {
		t.Fatal("bad header not detected")
	}
	ZipEnd(z)
}

func TestDictionaries(t *testing.T) {
	dict := []byte("alpha beta gamma delta epsilon")
	data := testData(3000)
	for _, nowrap := range []bool{false, true} {
		d := ZipNewDeflater(nowrap)
		if !ZipSetDictionary(d, javaBytes(dict), 0, int32(len(dict))) {
			t.Fatal("deflater refuses dictionary")
		}
		buf := make([]byte, 10_000)
		n := ZipDeflate(d, javaBytes(data), 0, int32(len(data)), javaBytes(buf), 0, 10_000, 9, 0, zFinish)
		if !ZipFinished(d) {
			t.Fatal("not finished")
		}
		z := ZipNewInflater(nowrap)
		if nowrap {
			ZipSetDictionary(z, javaBytes(dict), 0, int32(len(dict)))
		}
		ZipSetInput(z, javaBytes(buf), 0, n)
		out := make([]byte, 4000)
		got := ZipInflate(z, javaBytes(out), 0, 4000)
		if !nowrap {
			if got != 0 || !ZipNeedsDictionary(z) || uint32(ZipAdler(z)) != adler32.Checksum(dict) {
				t.Fatal("dictionary not asked for")
			}
			if ZipSetDictionary(z, javaBytes([]byte("wrong")), 0, 5) {
				t.Fatal("wrong dictionary accepted")
			}
			ZipSetDictionary(z, javaBytes(dict), 0, int32(len(dict)))
			got = ZipInflate(z, javaBytes(out), 0, 4000)
		}
		if !bytes.Equal(out[:got], data) || !ZipFinished(z) {
			t.Fatalf("nowrap=%v: dictionary round trip differs: %d %v %q", nowrap, got, ZipFinished(z), ZipErrorMessage(z).GoString())
		}
		ZipEnd(z)
	}
}

// A sync flush makes everything written so far available without more input.
func TestSyncFlushDeliversOutput(t *testing.T) {
	d := ZipNewDeflater(true)
	z := ZipNewInflater(true)
	defer ZipEnd(z)
	buf := make([]byte, 1000)
	out := make([]byte, 1000)
	for _, msg := range []string{"hello", "world, again"} {
		n := ZipDeflate(d, javaBytes([]byte(msg)), 0, int32(len(msg)), javaBytes(buf), 0, 1000, -1, 0, zSyncFlush)
		if ZipDeflate(d, javaBytes(nil), 0, 0, javaBytes(buf), n, 1000-n, -1, 0, zSyncFlush) != 0 {
			t.Fatal("an empty flush wrote output")
		}
		ZipSetInput(z, javaBytes(buf), 0, n)
		got := ZipInflate(z, javaBytes(out), 0, 1000)
		if string(out[:got]) != msg {
			t.Fatalf("got %q", out[:got])
		}
		if ZipInflate(z, javaBytes(out), 0, 1000) != 0 || ZipRemaining(z) != 0 {
			t.Fatal("expected to need input")
		}
	}
}

func TestInflaterGoroutines(t *testing.T) {
	compressed := deflateAll(t, false, testData(100_000), 1<<20, 1<<20, 6, zNoFlush)
	before := runtime.NumGoroutine()
	out := make([]byte, 100)
	// Ended, reset and abandoned inflaters in the middle of a stream.
	for i := 0; i < 50; i++ {
		z := ZipNewInflater(false)
		ZipSetInput(z, javaBytes(compressed), 0, 1000)
		ZipInflate(z, javaBytes(out), 0, 100)
		switch i % 3 {
		case 0:
			ZipEnd(z)
			ZipEnd(z)
		case 1:
			ZipReset(z)
			got, _ := inflateAll(t, false, compressed, 5000, 5000)
			if len(got) != 100_000 {
				t.Fatal("after reset")
			}
			ZipEnd(z)
		}
	}
	deadline := time.Now().Add(5 * time.Second)
	for runtime.NumGoroutine() > before && time.Now().Before(deadline) {
		runtime.GC()
		time.Sleep(10 * time.Millisecond)
	}
	if n := runtime.NumGoroutine(); n > before {
		t.Fatalf("%d goroutines left over", n-before)
	}
}

func TestChecksums(t *testing.T) {
	data := testData(100_000)
	var crc, crcc, adler int32
	adler = 1
	for i := 0; i < len(data); i += 7777 {
		end := min(i+7777, len(data))
		crc = ZipCRC32(crc, javaBytes(data), int32(i), int32(end-i))
		crcc = ZipCRC32C(crcc, javaBytes(data), int32(i), int32(end-i))
		adler = ZipAdler32(adler, javaBytes(data), int32(i), int32(end-i))
	}
	if uint32(crc) != crc32.ChecksumIEEE(data) || uint32(crcc) != crc32.Checksum(data, castagnoliTable) ||
		uint32(adler) != adler32.Checksum(data) {
		t.Fatal("checksums differ")
	}
}
