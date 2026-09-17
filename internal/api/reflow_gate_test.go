package api

import (
	"context"
	"errors"
	"testing"
	"time"
)

// The old implementation returned its cached capture after discarding a later
// redraw. This deterministic oracle places that redraw *inside* capture; it is
// independent of tmux scheduling and checks the real gate used by both paths.
func TestReflowCaptureInvalidatedByDrain(t *testing.T) {
	g := newReflowGate()
	g.begin()
	captures := 0
	var published string
	err := g.captureAndPublish(context.Background(), func(context.Context) ([]byte, error) {
		captures++
		if captures == 1 {
			g.route([]byte("new screen"), func(uint64) { t.Fatal("redraw escaped") }, func() {})
			return []byte("old screen"), nil
		}
		return []byte("new screen"), nil
	}, func(snap []byte) error {
		published = string(snap)
		return nil
	})
	if err != nil || captures != 2 || published != "new screen" || g.isActive() {
		t.Fatalf("capture=%d published=%q active=%v err=%v", captures, published, g.isActive(), err)
	}
}

// A relay already selected the next chunk when publication starts. It must
// route after the snapshot, never be discarded in a capture->enqueue gap.
func TestReflowPublishAndOpenAreAtomic(t *testing.T) {
	g := newReflowGate()
	g.begin()
	wire := make(chan string, 2)
	routed := make(chan struct{})
	err := g.captureAndPublish(context.Background(), func(context.Context) ([]byte, error) {
		return []byte("snapshot"), nil
	}, func(snap []byte) error {
		started := make(chan struct{})
		go func() {
			close(started)
			g.route([]byte("post-capture"), func(uint64) { wire <- "delta" }, func() { wire <- "LOST" })
			close(routed)
		}()
		<-started
		wire <- string(snap)
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	select {
	case <-routed:
	case <-time.After(time.Second):
		t.Fatal("relay deadlocked at snapshot publication")
	}
	if first, second := <-wire, <-wire; first != "snapshot" || second != "delta" {
		t.Fatalf("bad stream cut: %q, %q", first, second)
	}
}

func TestReflowContendedCaptureFailsClosed(t *testing.T) {
	g := newReflowGate()
	g.begin()
	captures := 0
	err := g.captureAndPublish(context.Background(), func(context.Context) ([]byte, error) {
		captures++
		g.route([]byte("new output"), func(uint64) { t.Fatal("gate opened early") }, func() {})
		return []byte("stale"), nil
	}, func([]byte) error {
		t.Fatal("unstable capture published")
		return nil
	})
	if !errors.Is(err, errReflowUnstable) || captures != reflowMaxCaptures || !g.isActive() {
		t.Fatalf("err=%v captures=%d active=%v", err, captures, g.isActive())
	}
}

func TestReflowUnchangedGeometryCannotLoseDrainedOutput(t *testing.T) {
	g := newReflowGate()
	g.begin()
	if !g.endIfClean() || g.isActive() {
		t.Fatal("clean no-op resize did not release")
	}
	g.begin()
	g.route([]byte("live output"), func(uint64) { t.Fatal("gate not active") }, func() {})
	if g.endIfClean() || !g.isActive() {
		t.Fatal("same geometry resumed deltas after losing a chunk")
	}
}

func TestReflowPublishFailureAndCancellationKeepGateClosed(t *testing.T) {
	for _, cancelDuringCapture := range []bool{false, true} {
		g := newReflowGate()
		g.begin()
		ctx, cancel := context.WithCancel(context.Background())
		calls := 0
		err := g.captureAndPublish(ctx, func(context.Context) ([]byte, error) {
			if cancelDuringCapture {
				cancel()
			}
			return []byte("snapshot"), nil
		}, func([]byte) error {
			calls++
			return errReflowBackpressure
		})
		cancel()
		want, wantCalls := errReflowBackpressure, 1
		if cancelDuringCapture {
			want, wantCalls = context.Canceled, 0
		}
		if !errors.Is(err, want) || calls != wantCalls || !g.isActive() {
			t.Fatalf("cancel=%v err=%v publish=%d active=%v", cancelDuringCapture, err, calls, g.isActive())
		}
	}
}
