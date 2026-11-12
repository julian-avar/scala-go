// Package crash is used by the interop-goroutine-panic test case.
package crash

import "sync"

// Run calls f on a new goroutine and waits for it to return; nothing recovers what f panics
// with, so the program ends while Run waits.
func Run(f func()) {
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		f()
		wg.Done()
	}()
	wg.Wait()
}
