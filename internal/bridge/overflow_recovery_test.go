package bridge

// Each source byte is acknowledged by the healthy subscriber before the next
// write. This forces distinct production fanout reads without depending on OS
// pipe capacity or scheduler speed; the withheld subscriber must overflow.
// This revised oracle is also copied verbatim to frozen b98504e for base red.
import (
	"bytes"
	"crypto/sha256"
	"os"
	"testing"
	"time"
)

func TestSubscriberOverflowStopsOnlySlowSubscriber(t *testing.T) {
	reader, writer, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	const gen = uint64(1)
	slow := make(chan []byte, 1)
	fast := make(chan []byte, 1)
	s := &sharedPipe{gen: gen, subs: map[uint64]chan []byte{1: slow, 2: fast}, refs: 2}
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
	// The lock joins the final fanout iteration, including either map order.
	// No new input follows, so the slow terminal state is now stable.
	s.mu.Lock()
	actualGen, refs := s.gen, s.refs
	s.mu.Unlock()
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
	if actualGen != gen || refs != 2 {
		t.Fatalf("overflow changed shared pipe: gen=%d refs=%d", actualGen, refs)
	}
	t.Logf("healthy bytes=%d sha256=%x generation=%d", len(got), sha256.Sum256(got), actualGen)
}
