package bridge

import (
	"bytes"
	"crypto/sha256"
	"os"
	"testing"
	"time"
)

// TestSubscriberOverflowStopsOnlySlowSubscriber drives the production fanout
// loop with a controlled pipe and a per-byte fast-consumer barrier. The
// barrier makes each source write observable without assuming one write is one
// reader chunk; the slow subscriber's bounded queue must be closed and
// cleared, while the healthy subscriber receives every byte in order.
func TestSubscriberOverflowStopsOnlySlowSubscriber(t *testing.T) {
	reader, writer, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	const gen = uint64(1)
	slow := make(chan []byte, 1)
	fast := make(chan []byte, 1)
	s := &sharedPipe{gen: gen, subs: map[uint64]*streamSubscriber{1: {data: slow, loss: make(chan error, 1)}, 2: {data: fast, loss: make(chan error, 1)}}, refs: 2}
	done := make(chan struct{})
	go s.fanout(reader, done, gen)
	t.Cleanup(func() {
		_ = writer.Close()
		_ = reader.Close()
		select {
		case <-done:
		case <-time.After(2 * time.Second):
			t.Error("fanout cleanup did not finish")
		}
	})

	want := []byte("0123456789abcdefghijklmnopqrstuvwxyz")
	var got []byte
	for _, b := range want {
		if n, err := writer.Write([]byte{b}); n != 1 || err != nil {
			t.Fatalf("source write n=%d err=%v", n, err)
		}
		select {
		case chunk, ok := <-fast:
			if !ok || len(chunk) != 1 {
				t.Fatalf("healthy byte acknowledgement: open=%v bytes=%d", ok, len(chunk))
			}
			got = append(got, chunk...)
		case <-time.After(2 * time.Second):
			t.Fatal("healthy subscriber did not acknowledge source byte")
		}
	}

	select {
	case chunk, ok := <-slow:
		if ok {
			t.Fatalf("overflow retained stale data: %d bytes", len(chunk))
		}
	case <-time.After(2 * time.Second):
		t.Fatal("slow subscriber was not terminated at overflow")
	}
	if !bytes.Equal(got, want) || sha256.Sum256(got) != sha256.Sum256(want) {
		t.Fatalf("healthy stream mismatch: bytes=%d want=%d", len(got), len(want))
	}

	// Repeated detach of the affected subscriber must not decrement refs twice.
	s.drop(1)
	s.drop(1)
	s.mu.Lock()
	actualGen, refs, members := s.gen, s.refs, len(s.subs)
	s.mu.Unlock()
	if actualGen != gen || refs != 1 || members != 1 {
		t.Fatalf("overflow/detach changed shared pipe: gen=%d refs=%d members=%d", actualGen, refs, members)
	}

	// The shared pipe and healthy subscriber remain live after the slow one is
	// removed; no pane-level pipe rebuild is allowed.
	if n, err := writer.Write([]byte("!")); n != 1 || err != nil {
		t.Fatalf("post-detach source write n=%d err=%v", n, err)
	}
	select {
	case chunk, ok := <-fast:
		if !ok || !bytes.Equal(chunk, []byte("!")) {
			t.Fatalf("healthy subscriber stopped after slow detach: open=%v chunk=%q", ok, chunk)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("healthy subscriber did not survive slow detach")
	}
	t.Logf("healthy bytes=%d sha256=%x generation=%d", len(got), sha256.Sum256(got), actualGen)
}
