package api

import (
	"testing"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

// Resizing advances the writer's stale-frame floor. A replacement subscription
// on the same WebSocket must still deliver new PTY output, while queued bytes
// from the old subscription stay invalidated.
func TestResubscribeAfterResizeDeliversFreshDelta(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	_ = te.readBinaryFrame()
	te.wsEnv.sendFrame(&protocol.Resize{Ref: te.ref(), Rows: 25, Cols: 81})
	_ = te.readBinaryFrame()
	te.wsEnv.srv.trackersMu.Lock()
	var conn *wsConn
	for c := range te.wsEnv.srv.trackers {
		conn = c
	}
	te.wsEnv.srv.trackersMu.Unlock()
	if conn == nil {
		t.Fatal("missing active connection")
	}
	conn.staleMu.Lock()
	oldEpoch := conn.staleBefore[te.ref()]
	conn.staleMu.Unlock()
	te.wsEnv.sendFrame(&protocol.Unsubscribe{Ref: te.ref()})
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 25, Cols: 81})
	if snapshot := te.readBinaryFrame(); snapshot.Kind != protocol.KindSnapshot {
		t.Fatalf("reopened pane has no initial snapshot: %v", snapshot.Kind)
	}
	if !conn.staleDelta(wsMsg{droppable: true, streamRef: te.ref(), epoch: oldEpoch}) {
		t.Error("replacement snapshot must invalidate queued output from the old subscription")
	}
	before := activeConnMetrics(t, te.wsEnv)
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 201, Ref: te.ref(), Text: "FRESH_AFTER_REOPEN"})
	ack := te.waitForMirrorAndInputAck("FRESH_AFTER_REOPEN", 201)
	if !ack.OK {
		t.Fatal("input did not reach the real PTY")
	}
	if activeConnMetrics(t, te.wsEnv).DeltaWireBytes <= before.DeltaWireBytes {
		t.Fatal("a snapshot alone does not prove the reopened output stream is live")
	}
}
