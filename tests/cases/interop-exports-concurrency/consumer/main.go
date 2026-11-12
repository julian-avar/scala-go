// A Go program calling exported Scala code from many goroutines, with contexts.
package main

import (
	"context"
	"fmt"
	"sync"
	"time"

	lib "scalagolib"
)

func main() {
	// 50 goroutines race to initialize a lazy val.
	results := make([]string, 50)
	var wg sync.WaitGroup
	for i := range results {
		wg.Add(1)
		go func() {
			defer wg.Done()
			results[i] = lib.Lookup(int32(i))
		}()
	}
	wg.Wait()
	fmt.Println(results[0], results[13], results[49], "initialized", lib.Inits(), "time(s)")

	// Each goroutine is a thread for Scala code, with its own ThreadLocal values.
	counts := make(chan int32)
	go func() {
		lib.CountCall()
		lib.CountCall()
		counts <- lib.CountCall()
	}()
	fmt.Println("first goroutine:", <-counts)
	go func() { counts <- lib.CountCall() }()
	fmt.Println("second goroutine:", <-counts)

	// One goroutine is one Thread, however many calls it makes.
	fmt.Println(lib.RecordThread(), lib.RecordThread())
	done := make(chan int32)
	go func() { done <- lib.RecordThread() }()
	fmt.Println(<-done)

	// Go contexts cancel Scala code.
	fmt.Println(lib.Search(context.Background(), "go", 10))
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	start := time.Now()
	_, err := lib.Search(ctx, "slow", 10000)
	fmt.Println(err != nil, err, time.Since(start) < 5*time.Second)
	cancelled, cancelNow := context.WithCancel(context.Background())
	cancelNow()
	_, err = lib.Search(cancelled, "cancelled", 10000)
	fmt.Println(err != nil)
}
