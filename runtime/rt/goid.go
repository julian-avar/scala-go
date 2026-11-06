package rt

import (
	"runtime"
	"unsafe"
)

// Goroutine ids. Go does not expose them, but the runtime needs one for every monitor operation,
// Thread.currentThread and interruptible channel operation, so this must be fast.
//
// The fast path reads the id from the runtime's g structure of the current goroutine (see
// getg). The field's offset differs between Go versions, so it is found at startup: the offset
// at which the g of several goroutines holds the id that runtime.Stack reports (the slow,
// documented path) for each of them. If there is not exactly one such offset, or getg is not
// available on this architecture, every call takes the slow path (a few microseconds).

// goidOffset is the offset of the id in g, or -1 if unknown.
var goidOffset = findGoidOffset()

// GoID returns the current goroutine's id.
func GoID() uint64 {
	if goidOffset >= 0 {
		return *(*uint64)(unsafe.Add(getg(), goidOffset))
	}
	return slowGoID()
}

// slowGoID parses the id from the stack header ("goroutine 18 [running]:").
func slowGoID() uint64 {
	var buf [32]byte
	n := runtime.Stack(buf[:], false)
	var id uint64
	for _, c := range buf[len("goroutine "):n] {
		if c < '0' || c > '9' {
			break
		}
		id = id*10 + uint64(c-'0')
	}
	return id
}

// gScanBytes bounds the search: the id is near the start of g, which is much larger.
const gScanBytes = 384

func findGoidOffset() int {
	if getg() == nil {
		return -1
	}
	// Candidates are the offsets where this goroutine's g holds its id, kept only if the same
	// holds in other goroutines (with other ids). Each goroutine checks its own g while running.
	candidates := goidCandidates(nil)
	for i := 0; i < 3 && len(candidates) > 1; i++ {
		done := make(chan []int)
		go func() { done <- goidCandidates(candidates) }()
		candidates = <-done
	}
	if len(candidates) != 1 {
		return -1
	}
	return candidates[0]
}

// goidCandidates returns the offsets (among from, or all aligned offsets if from is nil) where
// the current goroutine's g holds its id.
func goidCandidates(from []int) []int {
	g, id := getg(), slowGoID()
	if from == nil {
		for off := 0; off+8 <= gScanBytes; off += 8 {
			from = append(from, off)
		}
	}
	var matches []int
	for _, off := range from {
		if *(*uint64)(unsafe.Add(g, off)) == id {
			matches = append(matches, off)
		}
	}
	return matches
}
