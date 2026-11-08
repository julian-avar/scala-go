package rt

import (
	"bytes"
	"compress/flate"
	endian "encoding/binary"
	"errors"
	"hash/crc32"
	"io"
	"runtime"
)

// The native side of java.util.zip (javalib/src/scalago/runtime/Zip.scala): checksums, and the
// state machines behind Deflater and Inflater on compress/flate. Deflaters and inflaters are
// held by Scala as Objects; the Java API (argument checks, exceptions, streams, the zip and gzip
// formats) is written in Scala.

func bytesOf(b *Array[int8], off, n int32) []byte {
	return Reinterpret[int8, byte](b.Data[off : off+n])
}

// ---- Checksums ------------------------------------------------------------------------------

var castagnoliTable = crc32.MakeTable(crc32.Castagnoli)

func ZipCRC32(crc int32, b *Array[int8], off, n int32) int32 {
	return int32(crc32.Update(uint32(crc), crc32.IEEETable, bytesOf(b, off, n)))
}

func ZipCRC32C(crc int32, b *Array[int8], off, n int32) int32 {
	return int32(crc32.Update(uint32(crc), castagnoliTable, bytesOf(b, off, n)))
}

func ZipAdler32(adler int32, b *Array[int8], off, n int32) int32 {
	return int32(adler32Update(uint32(adler), bytesOf(b, off, n)))
}

// adler32Update continues an Adler-32 checksum (hash/adler32 cannot start from a given value).
func adler32Update(adler uint32, p []byte) uint32 {
	const mod = 65521
	const nmax = 5552 // the most bytes before s2 could overflow
	s1, s2 := adler&0xffff, adler>>16
	for len(p) > 0 {
		chunk := p
		if len(chunk) > nmax {
			chunk = chunk[:nmax]
		}
		p = p[len(chunk):]
		for _, x := range chunk {
			s1 += uint32(x)
			s2 += s1
		}
		s1 %= mod
		s2 %= mod
	}
	return s2<<16 | s1
}

// ---- Deflater -------------------------------------------------------------------------------

// zlib's flush modes and strategies, as java.util.zip.Deflater numbers them.
const (
	zNoFlush     = 0
	zSyncFlush   = 2
	zFullFlush   = 3
	zFinish      = 4
	zHuffmanOnly = 2
)

// deflater compresses with flate writers into a buffer of pending output, which deflate calls
// drain. The zlib format's header and trailer are written here, so that a change of level or
// strategy in mid-stream can end the current writer's blocks (a sync flush) and continue the
// stream with a new writer, as zlib's deflateParams does.
type deflater struct {
	nowrap  bool
	w       *flate.Writer // the current writer, or one kept for reuse after a reset
	wLevel  int           // w's level, in Go's numbering
	wDict   bool          // w was made with a preset dictionary
	active  bool          // w writes the current stream
	started bool          // the stream has begun (the zlib header is written)
	dirty   bool          // input written since the last flush
	closed  bool          // the stream is complete (it may still have pending output)
	dict    []byte
	out     bytes.Buffer // output not yet delivered
	adler   uint32       // of the input (zlib only)
	read    int64
	written int64
}

func ZipNewDeflater(nowrap bool) any { return &deflater{nowrap: nowrap, adler: 1} }

func goDeflateLevel(level, strategy int32) int {
	if strategy == zHuffmanOnly {
		return flate.HuffmanOnly
	}
	return int(level) // -1 (default) and 0 to 9 mean the same in Go
}

// writer makes sure that a writer with the given parameters writes the stream.
func (d *deflater) writer(level, strategy int32) {
	goLevel := goDeflateLevel(level, strategy)
	if d.active && goLevel == d.wLevel {
		return
	}
	if !d.started {
		d.started = true
		if !d.nowrap {
			d.writeZlibHeader(level, strategy)
		}
	}
	if d.active && d.dirty {
		d.w.Flush() // ends the old writer's output on a byte boundary; the new one continues the stream
		d.dirty = false
	}
	first := !d.active
	switch {
	case first && d.dict != nil:
		d.w, _ = flate.NewWriterDict(&d.out, goLevel, d.dict)
		d.wDict = true
	case d.w != nil && d.wLevel == goLevel && !d.wDict:
		d.w.Reset(&d.out)
	default:
		d.w, _ = flate.NewWriter(&d.out, goLevel)
		d.wDict = false
	}
	d.wLevel = goLevel
	d.active = true
}

// writeZlibHeader writes the two header bytes as zlib does (its FLEVEL bits for the level), and
// the dictionary's id if there is one.
func (d *deflater) writeZlibHeader(level, strategy int32) {
	var flevel uint
	switch {
	case strategy == zHuffmanOnly || (level >= 0 && level < 2):
		flevel = 0
	case level >= 2 && level < 6:
		flevel = 1
	case level == 6 || level == -1:
		flevel = 2
	default:
		flevel = 3
	}
	header := 0x78<<8 | flevel<<6
	if d.dict != nil {
		header |= 0x20
	}
	header += 31 - header%31
	d.out.WriteByte(byte(header >> 8))
	d.out.WriteByte(byte(header))
	if d.dict != nil {
		d.out.Write(endian.BigEndian.AppendUint32(nil, adler32Update(1, d.dict)))
		d.adler = 1
	}
}

// ZipDeflate compresses in[inOff:inOff+inLen] (all of it) with the level and strategy, flushes
// or finishes the stream as flush asks, and moves as much pending output as fits into
// out[outOff:outOff+outLen]. It returns the number of bytes written to out.
func ZipDeflate(h any, in *Array[int8], inOff, inLen int32, out *Array[int8], outOff, outLen int32,
	level, strategy, flush int32) int32 {
	d := h.(*deflater)
	if !d.closed {
		if inLen > 0 || flush != zNoFlush {
			d.writer(level, strategy)
		}
		if inLen > 0 {
			p := bytesOf(in, inOff, inLen)
			if !d.nowrap {
				d.adler = adler32Update(d.adler, p)
			}
			d.w.Write(p)
			d.read += int64(inLen)
			d.dirty = true
		}
		switch flush {
		case zSyncFlush, zFullFlush:
			// zlib emits nothing for a flush with no new input, so loops flushing until no output
			// comes end (Go's Flush would write another empty block each time).
			if d.dirty {
				d.w.Flush()
				d.dirty = false
			}
		case zFinish:
			d.w.Close()
			if !d.nowrap {
				d.out.Write(endian.BigEndian.AppendUint32(nil, d.adler))
			}
			d.closed = true
			d.active = false
		}
	}
	n, _ := d.out.Read(bytesOf(out, outOff, outLen))
	d.written += int64(n)
	return int32(n)
}

// ZipPending is the number of compressed bytes not delivered yet.
func ZipPending(h any) int32 { return int32(h.(*deflater).out.Len()) }

func (d *deflater) reset() {
	d.out.Reset()
	d.active, d.started, d.dirty, d.closed = false, false, false, false
	d.dict = nil
	d.adler = 1
	d.read, d.written = 0, 0
}

// ---- Inflater -------------------------------------------------------------------------------

// Go's flate reader pulls its input and cannot resume after running out of it, while Inflater
// is given its input piece by piece. So the reader runs on a goroutine of its own, as a
// coroutine of the inflate calls: when its input runs out, it hands control back to the caller
// ("needs input") and waits until a later call resumes it with more. Control passes over
// channels, so the two never run at the same time. The reader reads input a byte at a time
// (inflater is an io.ByteReader), so it never takes more than the compressed data: what is left
// (getRemaining) is what follows the stream, such as a gzip trailer.

// Inflater states.
const (
	inflateInput    = iota // waiting for input (or not started)
	inflateOutput          // has produced output (pending), and goes on when resumed
	inflateDict            // waiting for a dictionary
	inflateFinished        // the end of the stream
	inflateFailed
)

var errInflaterEnded = errors.New("inflater ended")

type inflater struct {
	nowrap  bool
	state   int
	message string // why it failed
	input   []byte // the rest of the input
	pending []byte // output not yet delivered
	buf     []byte // the goroutine's output buffer
	dict    []byte
	adler   uint32 // of the output delivered (zlib), or the dictionary's id while waiting for it
	read    int64
	written int64

	started bool
	stopped bool          // the caller has ended the goroutine
	ended   bool          // the goroutine has seen that (only it uses this)
	resume  chan struct{} // the caller resumes the goroutine (closed: end)
	yield   chan struct{} // the goroutine hands back control
	done    chan struct{} // closed when the goroutine has returned
}

// inflaterHandle is what Scala holds. The goroutine only refers to the inflater, so an Inflater
// that a program drops without calling end becomes unreachable, and its finalizer stops the
// goroutine.
type inflaterHandle struct{ *inflater }

func newInflater(nowrap bool, buf []byte) *inflater {
	if buf == nil {
		buf = make([]byte, 32<<10)
	}
	z := &inflater{nowrap: nowrap, buf: buf}
	if !nowrap {
		z.adler = 1 // zlib's checksum of nothing; raw streams have none, so 0
	}
	return z
}

func ZipNewInflater(nowrap bool) any {
	h := &inflaterHandle{newInflater(nowrap, nil)}
	runtime.SetFinalizer(h, func(h *inflaterHandle) { h.stop() })
	return h
}

func inflaterOf(h any) *inflater { return h.(*inflaterHandle).inflater }

// suspend hands control back to the caller in the given state, and reports whether the
// goroutine was resumed (rather than ended). Once ended, it returns false at once: flate may still
// return output it had decoded before the error that ending causes.
func (z *inflater) suspend(state int) bool {
	if z.ended {
		return false
	}
	z.state = state
	z.yield <- struct{}{}
	_, ok := <-z.resume
	z.ended = !ok
	return ok
}

func (z *inflater) ReadByte() (byte, error) {
	for len(z.input) == 0 {
		if !z.suspend(inflateInput) {
			return 0, errInflaterEnded
		}
	}
	b := z.input[0]
	z.input = z.input[1:]
	z.read++
	return b, nil
}

func (z *inflater) Read(p []byte) (int, error) {
	if len(p) == 0 {
		return 0, nil
	}
	for len(z.input) == 0 {
		if !z.suspend(inflateInput) {
			return 0, errInflaterEnded
		}
	}
	n := copy(p, z.input)
	z.input = z.input[n:]
	z.read += int64(n)
	return n, nil
}

// run is the goroutine.
func (z *inflater) run() {
	defer close(z.done)
	fail := func(message string) {
		if z.ended {
			return
		}
		z.message = message
		z.state = inflateFailed
		z.yield <- struct{}{}
	}
	var header [4]byte
	if !z.nowrap {
		if _, err := io.ReadFull(z, header[:2]); err != nil {
			return
		}
		cmf, flg := header[0], header[1]
		switch {
		case (uint(cmf)<<8|uint(flg))%31 != 0:
			fail("incorrect header check")
			return
		case cmf&0x0f != 8:
			fail("unknown compression method")
			return
		case cmf>>4 > 7:
			fail("invalid window size")
			return
		}
		if flg&0x20 != 0 {
			if _, err := io.ReadFull(z, header[:]); err != nil {
				return
			}
			z.adler = endian.BigEndian.Uint32(header[:])
			if !z.suspend(inflateDict) {
				return
			}
		}
		z.adler = 1
	}
	var fr io.ReadCloser
	if z.dict != nil {
		fr = flate.NewReaderDict(z, z.dict)
	} else {
		fr = flate.NewReader(z)
	}
	for {
		n, err := fr.Read(z.buf)
		if n > 0 {
			z.pending = z.buf[:n]
			if !z.suspend(inflateOutput) {
				return
			}
		}
		if err == io.EOF {
			break
		}
		if err != nil {
			if !errors.Is(err, errInflaterEnded) {
				fail(err.Error())
			}
			return
		}
	}
	if !z.nowrap {
		if _, err := io.ReadFull(z, header[:]); err != nil {
			return
		}
		if endian.BigEndian.Uint32(header[:]) != z.adler {
			fail("incorrect data check")
			return
		}
	}
	if !z.ended {
		z.state = inflateFinished
		z.yield <- struct{}{}
	}
}

// step runs the goroutine until it hands back control.
func (z *inflater) step() {
	if !z.started {
		z.started = true
		z.resume = make(chan struct{})
		z.yield = make(chan struct{})
		z.done = make(chan struct{})
		go z.run()
	} else {
		z.resume <- struct{}{}
	}
	<-z.yield
}

// stop ends the goroutine, if it is waiting to be resumed, and waits until it has returned.
func (z *inflater) stop() {
	if z.started && !z.stopped {
		z.stopped = true
		close(z.resume)
		<-z.done
	}
}

func ZipSetInput(h any, b *Array[int8], off, n int32) {
	inflaterOf(h).input = bytesOf(b, off, n)
}

func ZipRemaining(h any) int32 { return int32(len(inflaterOf(h).input)) }

// ZipInflate decompresses into b[off:off+n]: the number of bytes written, 0 if the inflater
// needs input or a dictionary or is finished, or -1 if the data is corrupt (ZipErrorMessage).
// Like zlib, it fills b as far as the input allows, and goes on while it can without input once
// b is full, so that the end of the stream is seen as soon as possible (finished() is true right
// after the call that delivers the last bytes, when the input holds the stream's end).
func ZipInflate(h any, b *Array[int8], off, n int32) int32 {
	z := inflaterOf(h)
	out := bytesOf(b, off, n)
	written := 0
	for {
		if len(z.pending) > 0 && written < len(out) {
			count := copy(out[written:], z.pending)
			if !z.nowrap {
				z.adler = adler32Update(z.adler, z.pending[:count])
			}
			z.pending = z.pending[count:]
			written += count
		}
		if len(z.pending) > 0 || !(z.state == inflateOutput || z.state == inflateInput && len(z.input) > 0) {
			break
		}
		z.step()
	}
	z.written += int64(written)
	if z.state == inflateFailed {
		return -1 // as zlib, even if this call wrote output before finding the error
	}
	return int32(written)
}

func ZipErrorMessage(h any) *String { return Str(inflaterOf(h).message) }

// ---- Both -----------------------------------------------------------------------------------

// ZipFinished reports whether the stream is complete and all its output delivered.
func ZipFinished(h any) bool {
	switch d := h.(type) {
	case *deflater:
		return d.closed && d.out.Len() == 0
	default:
		z := inflaterOf(h)
		return z.state == inflateFinished && len(z.pending) == 0
	}
}

func ZipNeedsDictionary(h any) bool { return inflaterOf(h).state == inflateDict }

// ZipSetDictionary sets the preset dictionary: false if it cannot be used (a zlib stream that
// has started, or does not ask for this dictionary; a raw inflater that has started).
func ZipSetDictionary(h any, b *Array[int8], off, n int32) bool {
	dict := bytes.Clone(bytesOf(b, off, n))
	switch d := h.(type) {
	case *deflater:
		if d.started {
			return false
		}
		d.dict = dict
		if !d.nowrap {
			d.adler = adler32Update(1, dict)
		}
	default:
		z := inflaterOf(h)
		switch {
		case z.state == inflateDict:
			if adler32Update(1, dict) != z.adler {
				return false
			}
			z.dict = dict
			z.state = inflateInput
		case z.nowrap && !z.started:
			z.dict = dict
		default:
			return false
		}
	}
	return true
}

func ZipAdler(h any) int32 {
	if d, ok := h.(*deflater); ok {
		return int32(d.adler)
	}
	return int32(inflaterOf(h).adler)
}

func ZipBytesRead(h any) int64 {
	if d, ok := h.(*deflater); ok {
		return d.read
	}
	return inflaterOf(h).read
}

func ZipBytesWritten(h any) int64 {
	if d, ok := h.(*deflater); ok {
		return d.written
	}
	return inflaterOf(h).written
}

// ZipReset starts a new stream with the same settings.
func ZipReset(h any) {
	if d, ok := h.(*deflater); ok {
		d.reset()
		return
	}
	handle := h.(*inflaterHandle)
	z := handle.inflater
	z.stop()
	handle.inflater = newInflater(z.nowrap, z.buf)
}

// ZipEnd releases the inflater's goroutine (a deflater holds nothing that needs releasing).
func ZipEnd(h any) {
	if handle, ok := h.(*inflaterHandle); ok {
		handle.stop()
	}
}
