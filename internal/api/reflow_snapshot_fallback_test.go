package api

import (
	"context"
	"fmt"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/bridge"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

func TestReflowContentionReturnsToDeltaAfterStableFallback(t *testing.T) {
	gate := newReflowGate()
	epoch, started := gate.begin()
	if !started {
		t.Fatal("failed to start reflow gate")
	}

	c := reflowTestConn(4)
	attempts := 0
	c.snapshotFn = func(context.Context, *bridge.Pane) ([]byte, error) {
		attempts++
		if attempts <= reflowMaxCaptures {
			gate.route([]byte("busy redraw"), func(uint64) { t.Fatal("redraw escaped") }, func() {})
		}
		return []byte(fmt.Sprintf("screen-%d", attempts)), nil
	}

	err := c.publishReflowSnapshot(context.Background(), nil, gate,
		protocol.Resize{Ref: "cursor", Cols: 46, Rows: 44}, epoch, false)
	if err != nil {
		t.Fatalf("busy reflow must degrade to snapshot mode: %v", err)
	}
	if attempts != reflowMaxCaptures+1 {
		t.Fatalf("capture attempts = %d, want %d (three contested captures plus fallback)", attempts, reflowMaxCaptures+1)
	}
	if gate.usesSnapshots() || gate.isActive() {
		t.Fatalf("stable fallback must return to delta mode: snapshot_mode=%v active=%v", gate.usesSnapshots(), gate.isActive())
	}

	payload := readSnapshotPayload(t, c)
	if string(payload.Data) != "screen-4" {
		t.Fatalf("fallback payload: kind=%v data=%q", payload.Kind, payload.Data)
	}

	var deltas [][]byte
	gate.route([]byte("post-fallback redraw"), func(uint64) { deltas = append(deltas, []byte("post-fallback redraw")) }, func() {
		t.Fatal("stable fallback must not discard raw delta")
	})
	if got := len(deltas); got != 1 {
		t.Fatalf("delta count after stable fallback = %d, want 1", got)
	}
}

func TestReflowContentionRecoveryCutsBackToDelta(t *testing.T) {
	gate := newReflowGate()
	epoch, started := gate.begin()
	if !started {
		t.Fatal("failed to start reflow gate")
	}

	c := reflowTestConn(4)
	attempts := 0
	c.snapshotFn = func(context.Context, *bridge.Pane) ([]byte, error) {
		attempts++
		// Keep the fourth (fallback) capture contended so recovery mode is entered.
		if attempts <= reflowMaxCaptures+1 {
			gate.route([]byte(fmt.Sprintf("busy-%d", attempts)), func(uint64) { t.Fatal("redraw escaped") }, func() {})
		}
		return []byte(fmt.Sprintf("screen-%d", attempts)), nil
	}

	if err := c.publishReflowSnapshot(context.Background(), nil, gate,
		protocol.Resize{Ref: "cursor", Cols: 46, Rows: 44}, epoch, false); err != nil {
		t.Fatalf("publish busy fallback: %v", err)
	}
	if !gate.usesSnapshots() || gate.isActive() {
		t.Fatalf("expected temporary snapshot recovery: snapshot_mode=%v active=%v", gate.usesSnapshots(), gate.isActive())
	}
	if got := string(readSnapshotPayload(t, c).Data); got != "screen-4" {
		t.Fatalf("initial recovery payload = %q, want screen-4", got)
	}

	var discarded int
	gate.route([]byte("during recovery"), func(uint64) { t.Fatal("recovery must not release raw delta") }, func() { discarded++ })
	if discarded != 1 {
		t.Fatalf("discarded bytes notification count = %d, want 1", discarded)
	}

	var refreshed []string
	if err := gate.refreshSnapshot(context.Background(), func(context.Context, int, int) ([]byte, error) {
		return []byte("screen-stable"), nil
	}, func(_ uint64, snap []byte) error {
		refreshed = append(refreshed, string(snap))
		return nil
	}); err != nil {
		t.Fatalf("stable recovery refresh: %v", err)
	}
	if gate.usesSnapshots() || gate.isActive() {
		t.Fatalf("stable recovery must release delta mode: snapshot_mode=%v active=%v", gate.usesSnapshots(), gate.isActive())
	}
	if want := []string{"screen-stable"}; fmt.Sprint(refreshed) != fmt.Sprint(want) {
		t.Fatalf("recovery refreshes = %v, want %v", refreshed, want)
	}

	var deltas []string
	gate.route([]byte("delta-1"), func(uint64) { deltas = append(deltas, "delta-1") }, func() { t.Fatal("delta-1 discarded") })
	gate.route([]byte("delta-2"), func(uint64) { deltas = append(deltas, "delta-2") }, func() { t.Fatal("delta-2 discarded") })
	if want := []string{"delta-1", "delta-2"}; fmt.Sprint(deltas) != fmt.Sprint(want) {
		t.Fatalf("post-recovery deltas = %v, want %v", deltas, want)
	}
}

func TestReflowSnapshotRecoveryDeduplicatesAndRateLimits(t *testing.T) {
	gate := newReflowGate()
	gate.mu.Lock()
	gate.epoch = 1
	gate.snapshotMode = true
	gate.snapshotDirty = true
	gate.snapshotCols, gate.snapshotRows = 46, 44
	gate.lastSnapshot = []byte("same")
	gate.lastSnapshotAt = time.Now()
	gate.mu.Unlock()

	var published []string
	var discarded int
	captureBusy := func(value string) func(context.Context, int, int) ([]byte, error) {
		return func(context.Context, int, int) ([]byte, error) {
			gate.route([]byte("output during capture"), func(uint64) { t.Fatal("raw delta escaped") }, func() { discarded++ })
			return []byte(value), nil
		}
	}
	publish := func(_ uint64, snap []byte) error {
		published = append(published, string(snap))
		return nil
	}

	// An identical unstable frame is fully suppressed.
	if err := gate.refreshSnapshot(context.Background(), captureBusy("same"), publish); err != nil {
		t.Fatalf("duplicate recovery refresh: %v", err)
	}
	if len(published) != 0 {
		t.Fatalf("duplicate frame published: %v", published)
	}

	// A changed unstable frame inside the one-second budget is retained as dirty,
	// not sent immediately. The next wake may still retry it.
	gate.route([]byte("new output"), func(uint64) { t.Fatal("raw delta escaped") }, func() { discarded++ })
	if err := gate.refreshSnapshot(context.Background(), captureBusy("progress"), publish); err != nil {
		t.Fatalf("rate-limited recovery refresh: %v", err)
	}
	if len(published) != 0 {
		t.Fatalf("rate-limited frame published too early: %v", published)
	}

	// Once the budget expires, the changed progress frame is admitted.
	gate.mu.Lock()
	gate.lastSnapshotAt = time.Now().Add(-reflowSnapshotInterval)
	gate.mu.Unlock()
	gate.route([]byte("new output"), func(uint64) { t.Fatal("raw delta escaped") }, func() { discarded++ })
	if err := gate.refreshSnapshot(context.Background(), captureBusy("progress"), publish); err != nil {
		t.Fatalf("expired recovery refresh: %v", err)
	}
	if want := []string{"progress"}; fmt.Sprint(published) != fmt.Sprint(want) {
		t.Fatalf("published progress = %v, want %v", published, want)
	}
	if discarded != 5 {
		t.Fatalf("discarded output notifications = %d, want 5", discarded)
	}
	if !gate.usesSnapshots() {
		t.Fatal("unstable progress must remain in temporary snapshot mode")
	}

	// A clean handoff publishes once and immediately releases the raw stream.
	gate.route([]byte("final output"), func(uint64) { t.Fatal("raw delta escaped") }, func() { discarded++ })
	if err := gate.refreshSnapshot(context.Background(), func(context.Context, int, int) ([]byte, error) {
		return []byte("final"), nil
	}, publish); err != nil {
		t.Fatalf("final stable handoff: %v", err)
	}
	if gate.usesSnapshots() || gate.isActive() {
		t.Fatalf("final handoff must leave recovery mode: snapshot_mode=%v active=%v", gate.usesSnapshots(), gate.isActive())
	}
	var deltas []string
	gate.route([]byte("delta-after"), func(uint64) { deltas = append(deltas, "delta-after") }, func() { t.Fatal("post-handoff delta discarded") })
	if len(deltas) != 1 || deltas[0] != "delta-after" {
		t.Fatalf("post-handoff deltas = %v, want [delta-after]", deltas)
	}
}

func reflowTestConn(priorityCapacity int) *wsConn {
	return &wsConn{
		s:          &Server{log: discardLogger()},
		ctx:        context.Background(),
		priorityCh: make(chan wsMsg, priorityCapacity),
	}
}

func readSnapshotPayload(t *testing.T, c *wsConn) protocol.BinaryPayload {
	t.Helper()
	msg := <-c.priorityCh
	payload, err := protocol.DecodeBinary(msg.data)
	if err != nil {
		t.Fatalf("decode snapshot: %v", err)
	}
	if payload.Kind != protocol.KindSnapshot {
		t.Fatalf("payload kind = %v, want snapshot", payload.Kind)
	}
	return payload
}
