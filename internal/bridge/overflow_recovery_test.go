package bridge

// overflow_recovery_test.go locks the bridge-level loss contract. The reader
// is the production fanout goroutine; an OS pipe supplies more bytes than one
// read can hold, while a one-slot subscriber queue is deliberately withheld.
// This is deterministic backpressure, not a fixed write-count proxy for read
// chunks.

import (
	"bytes"
	"os"
	"testing"
	"time"
)

func TestSubscriberOverflowStopsOnlySlowSubscriber(t *testing.T) {
	reader, writer, err := os.Pipe()
	if err != nil {
		t.Fatalf("os.Pipe: %v", err)
	}
	defer writer.Close()

	const gen = uint64(1)
	slow := make(chan []byte, 1)
	fast := make(chan []byte, 16)
	s := &sharedPipe{gen: gen, subs: map[uint64]chan []byte{1: slow, 2: fast}, refs: 2}
	done := make(chan struct{})
	go s.fanout(reader, done, gen)

	// Read copies are capped at streamBufferBytes. This payload therefore
	// necessarily takes at least two reads and overflows the withheld one-slot
	// subscriber, while the healthy subscriber is consumed concurrently.
	want := bytes.Repeat([]byte("overflow-proof\n"), streamBufferBytes/len("overflow-proof\n")*2+1)
	fastResult := make(chan []byte, 1)
	go func() {
		var got []byte
		for chunk := range fast {
			got = append(got, chunk...)
		}
		fastResult <- got
	}()
	writeResult := make(chan struct {
		n   int
		err error
	}, 1)
	go func() {
		n, err := writer.Write(want)
		writeResult <- struct {
			n   int
			err error
		}{n: n, err: err}
	}()
	select {
	case result := <-writeResult:
		if result.err != nil || result.n != len(want) {
			t.Fatalf("controlled writer n=%d err=%v, want n=%d err=nil", result.n, result.err, len(want))
		}
	case <-time.After(5 * time.Second):
		t.Fatal("controlled reader did not receive the overflow payload")
	}

	// The slow channel must become terminal at the first overflow, before the
	// producer closes its pipe. The old implementation leaves it open forever.
	slowDeadline := time.After(2 * time.Second)
	for {
		select {
		case _, ok := <-slow:
			if !ok {
				goto slowStopped
			}
			// The pre-overflow slot is stale and may be consumed while the
			// terminal transition races; it must not prevent closure.
		case <-slowDeadline:
			t.Fatal("slow subscriber was not terminated at overflow")
		}
	}
slowStopped:
	_ = writer.Close()

	select {
	case got := <-fastResult:
		if !bytes.Equal(got, want) {
			t.Fatalf("healthy stream mismatch: got %d bytes, want %d", len(got), len(want))
		}
	case <-time.After(5 * time.Second):
		t.Fatal("healthy subscriber did not finish")
	}
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("fanout did not finish after writer close")
	}
	if s.gen != gen {
		t.Fatalf("shared pipe generation changed: got %d, want %d", s.gen, gen)
	}
}
