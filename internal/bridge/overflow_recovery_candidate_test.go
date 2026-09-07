package bridge

import (
	"bytes"
	"errors"
	"os"
	"testing"
	"time"
)

func TestSubscriberOverflowEmitsOneLossSignal(t *testing.T) {
	reader, writer, err := os.Pipe()
	if err != nil {
		t.Fatalf("os.Pipe: %v", err)
	}
	defer writer.Close()

	slow := make(chan []byte, 1)
	loss := make(chan error, 1)
	s := &sharedPipe{
		gen:        1,
		subs:       map[uint64]chan []byte{1: slow},
		losses:     map[uint64]chan error{1: loss},
		lossClosed: map[uint64]bool{1: false},
		failed:     map[uint64]bool{1: false},
		refs:       1,
	}
	done := make(chan struct{})
	go s.fanout(reader, done, 1)

	payload := bytes.Repeat([]byte("loss-once\n"), streamBufferBytes/len("loss-once\n")*2+1)
	written := make(chan struct{})
	go func() {
		_, _ = writer.Write(payload)
		close(written)
	}()
	select {
	case <-written:
	case <-time.After(5 * time.Second):
		t.Fatal("controlled reader did not receive the overflow payload")
	}

	select {
	case got, ok := <-loss:
		if !ok || !errors.Is(got, ErrSubscriberOverflow) {
			t.Fatalf("loss signal=(%v,%v), want one ErrSubscriberOverflow", got, ok)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("overflow did not emit loss signal")
	}
	select {
	case got, ok := <-loss:
		if ok {
			t.Fatalf("second loss signal=%v", got)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("loss channel remained open after one-shot signal")
	}
	if got := len(slow); got != 0 {
		t.Fatalf("slow queue retained %d stale chunks", got)
	}
	_ = writer.Close()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("fanout did not stop after writer close")
	}
}
