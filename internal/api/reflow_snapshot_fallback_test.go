package api

import (
	"context"
	"fmt"
	"testing"

	"github.com/agentmirror/agentmirror/internal/bridge"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

func TestReflowContentionFallsBackToSnapshotRefresh(t *testing.T) {
	gate := newReflowGate()
	epoch, started := gate.begin()
	if !started {
		t.Fatal("failed to start reflow gate")
	}

	c := &wsConn{
		s:          &Server{log: discardLogger()},
		ctx:        context.Background(),
		priorityCh: make(chan wsMsg, 2),
	}
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
	if !gate.usesSnapshots() || gate.isActive() {
		t.Fatalf("fallback state: snapshot_mode=%v active=%v", gate.usesSnapshots(), gate.isActive())
	}

	msg := <-c.priorityCh
	payload, err := protocol.DecodeBinary(msg.data)
	if err != nil {
		t.Fatalf("decode fallback snapshot: %v", err)
	}
	if payload.Kind != protocol.KindSnapshot || string(payload.Data) != "screen-4" {
		t.Fatalf("fallback payload: kind=%v data=%q", payload.Kind, payload.Data)
	}

	gate.route([]byte("post-fallback redraw"), func(uint64) { t.Fatal("snapshot mode sent raw delta") }, func() {})
	var refreshed string
	err = gate.refreshSnapshot(context.Background(), func(context.Context, int, int) ([]byte, error) {
		return []byte("screen-refresh"), nil
	}, func(_ uint64, snap []byte) error {
		refreshed = string(snap)
		return nil
	})
	if err != nil {
		t.Fatalf("snapshot refresh: %v", err)
	}
	if refreshed != "screen-refresh" {
		t.Fatalf("refreshed snapshot = %q", refreshed)
	}
}
