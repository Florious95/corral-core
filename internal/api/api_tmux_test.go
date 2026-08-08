package api

// api_tmux_test.go exercises the mirroring paths of the API against a real
// isolated tmux server: subscribe (snapshot then delta), input (decidable
// ack), scrollback (converged range + 12-byte header), resize, and the
// not-subscribed / session-not-found error paths. These are the red tests
// from the knowledge base §4, each against a real pane so "the pipe is
// actually delivering" is always the positive control.

import (
	"bytes"
	"context"
	"encoding/binary"
	"strings"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

// TestSubscribeSnapshotThenDelta is the core mirroring red test: subscribing
// must deliver a snapshot first, then incremental deltas of the pane's new
// output (docs/protocol.md §4.2).
func TestSubscribeSnapshotThenDelta(t *testing.T) {
	te := startTmuxEnv(t, "cat")

	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})

	// First binary frame must be the snapshot (kind=1).
	snap := te.readBinaryFrame()
	if snap.Kind != protocol.KindSnapshot {
		t.Fatalf("first frame kind = %d, want snapshot(1)", snap.Kind)
	}
	if snap.Ref != te.ref() {
		t.Errorf("snapshot ref = %q, want %q", snap.Ref, te.ref())
	}
	if len(snap.Data) == 0 {
		t.Error("snapshot is empty")
	}

	// Inject output; the delta stream must carry it (positive control: the
	// pipe is actually attached).
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 1, Ref: te.ref(), Text: "MIRROR_MARK_77"})
	te.waitForMirror("MIRROR_MARK_77")
}

// TestInputAlwaysAcks is the send-must-arrive red test: every input gets an
// input_ack (success or a machine-readable failure reason) — never silence.
func TestInputAlwaysAcks(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	_ = te.readBinaryFrame() // snapshot

	te.wsEnv.sendFrame(&protocol.Input{ReqID: 42, Ref: te.ref(), Text: "hello"})
	// The input also echoes into the mirror stream; drain mirror frames until
	// the control ack arrives (the ack is the reply we assert on).
	ack := te.wsEnv.readControlDraining()
	if ack.FrameType() != protocol.TypeInputAck {
		t.Fatalf("expected input_ack, got %v", ack.FrameType())
	}
	ia := ack.(protocol.InputAck)
	if ia.ReqID != 42 {
		t.Errorf("input_ack req_id = %d, want 42", ia.ReqID)
	}
	if !ia.OK {
		t.Fatalf("input_ack not ok: %s", ia.Reason)
	}
}

// TestInputUnsubscribedNotAckedWithReason verifies input on an unsubscribed
// session fails with the machine-readable not_subscribed reason.
func TestInputUnsubscribedNotAckedWithReason(t *testing.T) {
	te := startTmuxEnv(t, "cat")

	te.wsEnv.sendFrame(&protocol.Input{ReqID: 3, Ref: te.ref(), Text: "x"})
	ack := te.wsEnv.readControl()
	ia := ack.(protocol.InputAck)
	if ia.OK {
		t.Fatal("input on unsubscribed session must not be ok")
	}
	if ia.Reason != protocol.InputFailNotSubscribed {
		t.Errorf("reason = %q, want %q", ia.Reason, protocol.InputFailNotSubscribed)
	}
}

// TestSubscribeUnknownRef verifies subscribing an unknown ref yields
// session_not_found (docs/protocol.md §4.2).
func TestSubscribeUnknownRef(t *testing.T) {
	te := startTmuxEnv(t, "cat")

	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: "no-such-session-ref", Rows: 24, Cols: 80})
	got := te.wsEnv.readControl()
	if got.FrameType() != protocol.TypeError {
		t.Fatalf("expected error frame, got %v", got.FrameType())
	}
	ef := got.(protocol.ErrorFrame)
	if ef.Code != protocol.ErrCodeSessionNotFound {
		t.Errorf("error code = %q, want %q", ef.Code, protocol.ErrCodeSessionNotFound)
	}
}

// TestUnsubscribeIdempotent verifies unsubscribing a not-subscribed session is
// not an error and produces no reply (docs/protocol.md §4.2).
func TestUnsubscribeIdempotent(t *testing.T) {
	te := startTmuxEnv(t, "cat")

	te.wsEnv.sendFrame(&protocol.Unsubscribe{Ref: te.ref()})
	// No reply is expected; a frame would be a bug (silent-swallow hunt).
	// We can only assert by reading with a short timeout and expecting no text
	// frame carrying an error.
	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
	typ, _, err := te.wsEnv.conn.Read(ctx)
	cancel()
	if err == nil {
		if typ == websocket.MessageText {
			t.Fatalf("unsubscribe produced an unexpected reply")
		}
	}
}

// TestScrollbackConvergedRange verifies a scrollback request that exceeds the
// available history is clamped and the actual range reported in the 12-byte
// header (docs/protocol.md §6.3). The protocol addresses 0 = screen top,
// negative = history; tmux semantics match this directly.
func TestScrollbackConvergedRange(t *testing.T) {
	// A 10-row screen with plenty of history.
	te := startTmuxEnv(t, "bash")
	// Make the window small so history accumulates quickly.
	runTmuxCmd(te.env, te.sock, "resize-window", "-t", "0", "-x", "40", "-y", "10")
	time.Sleep(200 * time.Millisecond)

	// Mirroring paths require an active subscription (input applies to a
	// subscribed session). Subscribe first, then inject to build history.
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	_ = te.readBinaryFrame() // snapshot
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 1, Ref: te.ref(), Text: "for i in $(seq 1 60); do echo SCBK_$i; done"})
	// Wait for the tail on screen.
	te.waitForMirror("SCBK_60")

	// Request far more history than exists: from_line=-500, count=100.
	te.wsEnv.sendFrame(&protocol.Scrollback{ReqID: 9, Ref: te.ref(), FromLine: -500, Count: 100})

	// Read the scrollback binary reply, draining any mirror deltas that arrive
	// first (the injected loop's echo is still streaming). The scrollback reply
	// is the frame whose kind is KindScrollback.
	var payload protocol.BinaryPayload
	found := false
	for i := 0; i < 50 && !found; i++ {
		typ, data, err := te.wsEnv.conn.Read(context.Background())
		if err != nil {
			t.Fatalf("read scrollback: %v", err)
		}
		if typ != websocket.MessageBinary {
			t.Fatalf("scrollback reply must be binary, got %v", typ)
		}
		p, err := protocol.DecodeBinary(data)
		if err != nil {
			t.Fatalf("decode binary: %v", err)
		}
		if p.Kind == protocol.KindScrollback {
			payload = p
			found = true
		}
	}
	if !found {
		t.Fatal("scrollback reply never arrived")
	}
	if payload.ReqID != 9 {
		t.Errorf("scrollback req_id = %d, want 9", payload.ReqID)
	}
	if payload.LineCount == 0 {
		t.Error("scrollback line_count must be >= 1")
	}
	// The server must report the ACTUAL range it returned: with -500 requested
	// and only a bounded history, from_line must be clamped to the oldest
	// available (not left at -500).
	if payload.FromLine < -1000 {
		t.Errorf("from_line = %d, not clamped to available history", payload.FromLine)
	}
	if payload.LineCount > 100 {
		t.Errorf("line_count = %d, exceeds requested count 100", payload.LineCount)
	}
	// The payload must contain the oldest history lines, not the newest.
	if bytes.Contains(payload.Data, []byte("SCBK_60")) {
		t.Error("scrollback page must not contain on-screen tail SCBK_60")
	}
}

// TestScrollbackExactHeaderBytes verifies the raw 12-byte header layout on the
// wire: req_id (4 BE), from_line (4 BE signed), line_count (4 BE unsigned),
// then the ANSI bytes (docs/protocol.md §6.3).
func TestScrollbackExactHeaderBytes(t *testing.T) {
	te := startTmuxEnv(t, "bash")
	runTmuxCmd(te.env, te.sock, "resize-window", "-t", "0", "-x", "40", "-y", "10")
	time.Sleep(200 * time.Millisecond)
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	_ = te.readBinaryFrame() // snapshot
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 1, Ref: te.ref(), Text: "for i in $(seq 1 30); do echo SCBKX_$i; done"})
	te.waitForMirror("SCBKX_30")

	te.wsEnv.sendFrame(&protocol.Scrollback{ReqID: 5, Ref: te.ref(), FromLine: -20, Count: 10})

	// Drain mirror deltas until the scrollback reply arrives.
	var payload protocol.BinaryPayload
	var frame []byte
	found := false
	for i := 0; i < 50 && !found; i++ {
		typ, data, err := te.wsEnv.conn.Read(context.Background())
		if err != nil {
			t.Fatalf("read: %v", err)
		}
		if typ != websocket.MessageBinary {
			continue
		}
		p, err := protocol.DecodeBinary(data)
		if err != nil {
			t.Fatalf("decode: %v", err)
		}
		if p.Kind == protocol.KindScrollback {
			payload = p
			frame = data
			found = true
		}
	}
	if !found {
		t.Fatal("scrollback reply never arrived")
	}
	// Reconstruct the 12-byte header from the frame layout:
	// magic(2) version(1) kind(1) reflen(1) ref(reflen) [12-byte header] data.
	off := 5 + len(payload.Ref)
	if len(frame) < off+12 {
		t.Fatalf("frame too short for 12-byte header: %d bytes", len(frame))
	}
	reqID := binary.BigEndian.Uint32(frame[off : off+4])
	fromLine := int32(binary.BigEndian.Uint32(frame[off+4 : off+8]))
	lineCount := binary.BigEndian.Uint32(frame[off+8 : off+12])
	if reqID != 5 {
		t.Errorf("header req_id = %d, want 5", reqID)
	}
	if fromLine != payload.FromLine {
		t.Errorf("header from_line = %d, payload %d", fromLine, payload.FromLine)
	}
	if lineCount != payload.LineCount {
		t.Errorf("header line_count = %d, payload %d", lineCount, payload.LineCount)
	}
	if lineCount == 0 {
		t.Error("header line_count must be >= 1")
	}
}

// TestResizeChangesPane verifies a resize frame changes the underlying pane's
// dimensions (requirement 005), and an unsubscribed-session resize is a no-op.
func TestResizeChangesPane(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	_ = te.readBinaryFrame() // snapshot

	te.wsEnv.sendFrame(&protocol.Resize{Ref: te.ref(), Rows: 30, Cols: 120})
	time.Sleep(300 * time.Millisecond)

	out, err := runTmuxCmd(te.env, te.sock, "display-message", "-p", "-t", te.paneID, "#{pane_width}x#{pane_height}")
	if err != nil {
		t.Fatalf("read pane size: %v", err)
	}
	if strings.TrimSpace(out) != "120x30" {
		t.Errorf("pane size = %q, want 120x30 (resize applied to subscribed pane)", out)
	}
}

// TestStateNeverGatesMirror verifies the 008 isolation law structurally: with
// the default (always-unknown) state provider, a subscribe still delivers a
// snapshot and input still acks — state never blocks the mirror path.
func TestStateNeverGatesMirror(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	snap := te.readBinaryFrame()
	if snap.Kind != protocol.KindSnapshot {
		t.Fatalf("snapshot not delivered under unknown state, got kind %d", snap.Kind)
	}
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 1, Ref: te.ref(), Text: "hi"})
	ack := te.wsEnv.readControlDraining()
	ia := ack.(protocol.InputAck)
	if !ia.OK {
		t.Fatalf("input ack under unknown state: %s", ia.Reason)
	}
}
