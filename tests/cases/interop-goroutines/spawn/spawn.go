// Package spawn is used by the interop-goroutines test case: Scala code called from goroutines
// that Go code started (docs/_docs/spec/interop.md, "Goroutines and threads").
package spawn

import (
	"sync"
	"time"
)

// Parallel calls f(i) for i < n, each on its own goroutine, and waits for them.
func Parallel(n int, f func(int)) {
	var wg sync.WaitGroup
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			f(i)
		}()
	}
	wg.Wait()
}

// OnGoroutine calls f twice on one new goroutine and returns the results.
func OnGoroutine(f func() string) (string, string) {
	ch := make(chan string)
	go func() {
		a := f()
		b := f()
		ch <- a
		ch <- b
	}()
	return <-ch, <-ch
}

// Fire starts f on a goroutine and returns at once.
func Fire(f func()) { go f() }

// Produce sends 1..n on c from a goroutine, then closes it.
func Produce(c chan<- int64, n int) {
	go func() {
		for i := 1; i <= n; i++ {
			c <- int64(i)
		}
		close(c)
	}()
}

// Sum receives from c until it is closed.
func Sum(c <-chan int64) int64 {
	var s int64
	for x := range c {
		s += x
	}
	return s
}

// Locked runs f(i) for i < n on goroutines, holding mu.
func Locked(mu *sync.Mutex, n int, f func(int)) {
	var wg sync.WaitGroup
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			mu.Lock()
			defer mu.Unlock()
			f(i)
		}()
	}
	wg.Wait()
}

// Sleep sleeps on the calling goroutine.
func Sleep(ms int) { time.Sleep(time.Duration(ms) * time.Millisecond) }
