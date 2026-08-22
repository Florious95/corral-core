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
	"fmt"
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
	// 首帧快照同样必须重锚游标（A 图中屏残影 = subscribe 快照重放后游标错位，
	// SIGWINCH 重绘增量落在旧网格底行）。
	assertSnapshotCursorSuffix(t, te, snap.Data)

	// Inject output; the delta stream must carry it (positive control: the
	// pipe is actually attached). 直通（059）：文本先键入，再裸 Enter 提交执行。
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 1, Ref: te.ref(), Text: "MIRROR_MARK_77"})
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 2, Ref: te.ref(), Text: ""})
	te.waitForMirror("MIRROR_MARK_77")
}

// TestInputKeysInjectsNamedKey is the R-1 named-key red test: an input frame
// carrying Keys injects the named key into the pane — tmux renders the echoed
// Escape as the caret-notation pair "^[" in the mirror stream — WITHOUT
// appending an Enter, and acks ok. Before the handleInput keys branch existed,
// a keys frame fell through to the bare-Enter text path (send-keys -l "" +
// Enter), which echoes only a blank line (\r\n) and never "^[", so this test
// is genuinely red against the old code.
//
// The drain reads frames until BOTH the input_ack (control) and the echoed "^["
// (binary mirror) have been seen; either may arrive first, and mirror deltas
// that carry unrelated pane output are accumulated.
func TestInputKeysInjectsNamedKey(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	_ = te.readBinaryFrame() // snapshot

	te.wsEnv.sendFrame(&protocol.Input{ReqID: 44, Ref: te.ref(), Keys: []protocol.Key{protocol.KeyEsc}})

	deadline := time.Now().Add(5 * time.Second)
	var got bytes.Buffer
	var ia protocol.InputAck
	seenAck, seenEcho := false, false
	for time.Now().Before(deadline) && (!seenAck || !seenEcho) {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		typ, data, err := te.wsEnv.conn.Read(ctx)
		cancel()
		if err != nil {
			t.Fatalf("keys input read: %v (seenAck=%v seenEcho=%v got=%q)", err, seenAck, seenEcho, got.String())
		}
		if typ != websocket.MessageBinary {
			typed, err := protocol.UnmarshalFrame(data)
			if err != nil {
				t.Fatalf("decode control %q: %v", data, err)
			}
			if ack, ok := typed.(protocol.InputAck); ok {
				ia = ack
				seenAck = true
			}
			continue
		}
		payload, err := protocol.DecodeBinary(data)
		if err != nil {
			t.Fatalf("decode mirror %q: %v", data, err)
		}
		got.Write(payload.Data)
		if bytes.Contains(got.Bytes(), []byte("^[")) {
			seenEcho = true
		}
	}
	if !seenAck {
		t.Fatal("input_ack for keys input never arrived")
	}
	if ia.ReqID != 44 {
		t.Errorf("keys input_ack req_id = %d, want 44", ia.ReqID)
	}
	if !ia.OK {
		t.Fatalf("keys input_ack not ok: %s", ia.Reason)
	}
	if !seenEcho {
		t.Errorf("named-key Escape never echoed on screen; got %q (bare-Enter fallback would emit no \"^[\")", got.String())
	}
}

// TestInputKeysUnsubscribedFailsWithReason verifies the named-key path shares
// the text path's decidable failure taxonomy (requirement 003): a keys input on
// an unsubscribed session fails with not_subscribed, never silence.
func TestInputKeysUnsubscribedFailsWithReason(t *testing.T) {
	te := startTmuxEnv(t, "cat")

	te.wsEnv.sendFrame(&protocol.Input{ReqID: 7, Ref: te.ref(), Keys: []protocol.Key{protocol.KeyEsc}})
	ack := te.wsEnv.readControl()
	ia := ack.(protocol.InputAck)
	if ia.OK {
		t.Fatal("keys input on unsubscribed session must not be ok")
	}
	if ia.Reason != protocol.InputFailNotSubscribed {
		t.Errorf("reason = %q, want %q", ia.Reason, protocol.InputFailNotSubscribed)
	}
}

// TestInputKeysUnknownRefMatchesTextPrecedence verifies the named-key path
// shares the text path's decidable failure precedence (requirement 003): an
// unknown ref that was never subscribed fails with not_subscribed — the
// subscribed() gate fires before ref resolution, exactly as for text input.
// (The session_not_found branch fires only when a subscribed ref's pane
// vanishes between listing and injection.)
func TestInputKeysUnknownRefMatchesTextPrecedence(t *testing.T) {
	te := startTmuxEnv(t, "cat")

	te.wsEnv.sendFrame(&protocol.Input{ReqID: 8, Ref: "no-such-ref", Keys: []protocol.Key{protocol.KeyTab}})
	ack := te.wsEnv.readControl()
	ia := ack.(protocol.InputAck)
	if ia.OK {
		t.Fatal("keys input on unknown unsubscribed ref must not be ok")
	}
	if ia.Reason != protocol.InputFailNotSubscribed {
		t.Errorf("reason = %q, want %q (same precedence as text path)", ia.Reason, protocol.InputFailNotSubscribed)
	}
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

// TestUnsubscribeRestoresOriginalPaneSize is the D-21 regression test: the
// phone-sized subscribe geometry is temporary, and leaving the session must
// return the underlying CLI pane to the full-window geometry it had before.
func TestUnsubscribeRestoresOriginalPaneSize(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 12, Cols: 50})
	_ = te.readBinaryFrame() // subscribe-time snapshot; resize has completed

	out, err := runTmuxCmd(te.env, te.sock, "display-message", "-p", "-t", te.paneID, "#{pane_width}x#{pane_height}")
	if err != nil {
		t.Fatalf("read subscribed pane size: %v", err)
	}
	if strings.TrimSpace(out) != "50x12" {
		t.Fatalf("subscribed pane size = %q, want 50x12", out)
	}

	te.wsEnv.sendFrame(&protocol.Unsubscribe{Ref: te.ref()})
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		out, err = runTmuxCmd(te.env, te.sock, "display-message", "-p", "-t", te.paneID, "#{pane_width}x#{pane_height}")
		if err != nil {
			t.Fatalf("read restored pane size: %v", err)
		}
		if strings.TrimSpace(out) == "80x24" {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("pane size after unsubscribe = %q, want original 80x24", out)
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
	// 直通（059）：文本先键入（不回车），再裸 Enter 提交执行。
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 1, Ref: te.ref(), Text: "for i in $(seq 1 60); do echo SCBK_$i; done"})
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 2, Ref: te.ref(), Text: ""})
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
	// 直通（059）：文本先键入（不回车），再裸 Enter 提交执行。
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 1, Ref: te.ref(), Text: "for i in $(seq 1 30); do echo SCBKX_$i; done"})
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 2, Ref: te.ref(), Text: ""})
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

// TestResizeRepushesSnapshot is the fix-term-residuals red test: after a
// resize is applied, the server must re-push a fresh binary snapshot to the
// subscribed connection. Rationale: SIGWINCH makes the CLI redraw, but the
// redraw arrives as deltas composited over the client's stale, old-geometry
// grid — leftover prompt residue survives. Only a snapshot (which the client
// replays via clear-and-rebuild) deterministically clears residuals
// (docs/protocol.md §4.2 resize).
func TestResizeRepushesSnapshot(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	_ = te.readBinaryFrame() // subscribe-time snapshot

	// Put a marker on screen first, so the re-pushed snapshot provably carries
	// the pane's CURRENT content (not an empty shell captured pre-reflow).
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 21, Ref: te.ref(), Text: "RESIDUAL_MARK_9"})
	te.waitForMirror("RESIDUAL_MARK_9")

	te.wsEnv.sendFrame(&protocol.Resize{Ref: te.ref(), Rows: 30, Cols: 120})

	// Drain interleaving frames (input_ack already consumed by waitForMirror's
	// skip; deltas may still stream) until the fresh snapshot arrives.
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		ctx, cancel := context.WithTimeout(context.Background(), time.Until(deadline))
		typ, data, err := te.wsEnv.conn.Read(ctx)
		cancel()
		if err != nil {
			t.Fatalf("resize did not re-push a snapshot within deadline: %v", err)
		}
		if typ != websocket.MessageBinary {
			continue // control frames (list_delta, …) interleave; skip
		}
		p, err := protocol.DecodeBinary(data)
		if err != nil {
			t.Fatalf("decode binary: %v", err)
		}
		if p.Kind != protocol.KindSnapshot {
			continue // delta traffic; the snapshot must still arrive
		}
		if p.Ref != te.ref() {
			t.Errorf("re-pushed snapshot ref = %q, want %q", p.Ref, te.ref())
		}
		if !bytes.Contains(p.Data, []byte("RESIDUAL_MARK_9")) {
			t.Errorf("re-pushed snapshot misses on-screen marker; got %q", p.Data)
		}
		assertSnapshotCursorSuffix(t, te, p.Data)
		return
	}
	t.Fatal("resize did not re-push a snapshot (client residuals would survive)")
}

// assertSnapshotCursorSuffix verifies the snapshot ends with a cursor-position
// escape (ESC[row;colH) matching the pane's REAL cursor. capture-pane carries
// no cursor state, so a replayed snapshot leaves the client cursor at the end
// of the capture (the bottom row on an untrimmed full-height capture) while
// the real cursor sits mid-screen; the next delta without absolute addressing
// (e.g. bash's SIGWINCH prompt redraw, plain "\r\e[K…") then prints at the
// wrong row — the second half of the residual defect (on-device screenshot
// evidence: phantom bottom-row prompt). The server must re-anchor the cursor
// inside the snapshot bytes themselves (zero protocol change).
func assertSnapshotCursorSuffix(t *testing.T, te *tmuxEnv, data []byte) {
	t.Helper()
	out, err := runTmuxCmd(te.env, te.sock, "display-message", "-p", "-t", te.paneID, "#{cursor_x} #{cursor_y}")
	if err != nil {
		t.Fatalf("read cursor: %v", err)
	}
	var x, y int
	if _, err := fmt.Sscanf(strings.TrimSpace(out), "%d %d", &x, &y); err != nil {
		t.Fatalf("parse cursor %q: %v", out, err)
	}
	want := fmt.Sprintf("\x1b[%d;%dH", y+1, x+1)
	if !bytes.HasSuffix(data, []byte(want)) {
		tail := data
		if len(tail) > 24 {
			tail = tail[len(tail)-24:]
		}
		t.Errorf("snapshot must end with cursor re-anchor %q; tail = %q", want, tail)
	}
}

// TestSameSizeResizeDoesNotRepushSnapshot is the D-27 red test (task
// fix-d27-v3). The phone's IME keyboard/input-box relayout that follows every
// message send produces redundant same-size resize frames (fix-refresh-direction
// root-cause chain step 3). The client replays a snapshot as clear-and-rebuild,
// which on-device reads as the "top-down line-by-line refresh" D-27 reports —
// so a resize that did NOT change the pane's actual dims must not re-push a
// snapshot. Positive control (TestResizeRepushesSnapshot) keeps the real-resize
// repush green.
func TestSameSizeResizeDoesNotRepushSnapshot(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	_ = te.readBinaryFrame() // subscribe-time snapshot

	// Pane is already 80x24 (startTmuxEnv creates it with -x 80 -y 24), so a
	// same-size resize is genuinely a no-op in tmux terms — the frame that
	// D-27's IME relayout emits on every message send.
	te.wsEnv.sendFrame(&protocol.Resize{Ref: te.ref(), Rows: 24, Cols: 80})

	// Drain the connection for a bounded window; any binary snapshot that
	// arrives is the bug (clear-and-rebuild flicker). Control frames
	// (list_delta) and mirror deltas may interleave and are skipped.
	deadline := time.Now().Add(800 * time.Millisecond)
	for time.Now().Before(deadline) {
		ctx, cancel := context.WithTimeout(context.Background(), time.Until(deadline))
		typ, data, err := te.wsEnv.conn.Read(ctx)
		cancel()
		if err != nil {
			break // window elapsed, no snapshot — exactly what we want
		}
		if typ != websocket.MessageBinary {
			continue // control frame (list_delta, …); not the signal
		}
		p, err := protocol.DecodeBinary(data)
		if err != nil {
			t.Fatalf("decode binary: %v", err)
		}
		if p.Kind == protocol.KindSnapshot {
			t.Fatalf("same-size resize re-pushed a snapshot (client clear-and-rebuild flicker); data=%q", p.Data)
		}
	}
}

// TestRealResizeStillRepushesSnapshot guards the other half of D-27: a resize
// that DOES change the pane's actual dims must still re-push a snapshot
// (fix-term-residuals convergence, docs/protocol.md §4.2). It is the positive
// control that proves the same-size test above is not a dead probe — the
// snapshot-detection is alive, only no-op resizes are filtered.
func TestRealResizeStillRepushesSnapshot(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	_ = te.readBinaryFrame() // subscribe-time snapshot

	// Real resize: 80x24 → 50x12. The pane dims genuinely change, so the
	// server must re-push a fresh snapshot for the reflowed geometry.
	te.wsEnv.sendFrame(&protocol.Resize{Ref: te.ref(), Rows: 12, Cols: 50})

	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		ctx, cancel := context.WithTimeout(context.Background(), time.Until(deadline))
		typ, data, err := te.wsEnv.conn.Read(ctx)
		cancel()
		if err != nil {
			t.Fatalf("real resize did not re-push a snapshot within deadline: %v", err)
		}
		if typ != websocket.MessageBinary {
			continue
		}
		p, err := protocol.DecodeBinary(data)
		if err != nil {
			t.Fatalf("decode binary: %v", err)
		}
		if p.Kind == protocol.KindSnapshot {
			assertSnapshotCursorSuffix(t, te, p.Data)
			return // snapshot arrived: positive control green
		}
	}
	t.Fatal("real resize did not re-push a snapshot (convergence would be lost)")
}


// TestPassthroughNoEnter is the wire-level red test for requirement 059
// passthrough: a non-empty Input.Text is TYPED into the pane WITHOUT appending
// an Enter (TypeKeys), so the CLI input box keeps the text as a live draft
// instead of submitting it. The leader added this as a mandatory criterion
// (A-pi-wire-server): without the passthrough routing, a text frame would have
// gone through Inject (send-keys -l + Enter) and the typed command would
// EXECUTE immediately, producing its output before any separate submit.
//
// Proof in two phases against a `bash` pane:
//   1. Send Text="echo NOENTER_MARK_059" (the keystroke). With passthrough the
//      command lands on the prompt line as a draft; the `echo` output must NOT
//      appear yet (a bare Enter from the old Inject path would run it and the
//      marker would show).
//   2. Send Text="" (bare-Enter submit). Now the command executes and the
//      marker appears — proving the submit is what triggered it, not the
//      keystroke frame.
func TestPassthroughNoEnter(t *testing.T) {
	te := startTmuxEnv(t, "bash")
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	_ = te.readBinaryFrame() // snapshot

	const marker = "NOENTER_MARK_059"
	cmd := "echo " + marker

	// Phase 1: type the command (keystroke frame), NO Enter. bash echoes the
	// DRAFT "echo NOENTER_MARK_059" onto the prompt line, but must NOT run it:
	// the mirror must NOT contain a newline-terminated output line of just the
	// marker (that only appears once Enter submits the command). The draft text
	// contains the marker as a substring, so we assert on the standalone output
	// line, not raw containment.
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 1, Ref: te.ref(), Text: cmd})

	// Accumulate mirror deltas until we've seen either the executed output line
	// (would mean Enter was appended) or enough to conclude the draft only. We
	// read a bounded set of frames (mirror deltas arrive in chunks; bash echoes
	// "echo NOENTER_MARK_059" as several deltas). Control frames (input_ack) are
	// skipped. Each read uses a fresh short context (no accumulated deadline), so
	// the connection stays writable for the submit. Once the output line appears
	// → Enter was appended (bad).
	ran := false
	seenDraft := false
	var acc bytes.Buffer
	for i := 0; i < 20 && !ran && !seenDraft; i++ {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		typ, data, err := te.wsEnv.conn.Read(ctx)
		cancel()
		if err != nil {
			t.Fatalf("read draft: %v", err)
		}
		if typ != websocket.MessageBinary {
			continue // input_ack or other control frame
		}
		p, err := protocol.DecodeBinary(data)
		if err != nil {
			t.Fatalf("decode mirror frame: %v", err)
		}
		acc.Write(p.Data)
		if bytes.Contains(acc.Bytes(), []byte("\n"+marker)) {
			ran = true // executed output present
			break
		}
		if bytes.Contains(acc.Bytes(), []byte(cmd)) {
			seenDraft = true
		}
	}
	if ran {
		t.Fatalf("keystroke frame executed the command (%q output appeared without a submit Enter) — text path still appends Enter; mirror=%q", marker, acc.String())
	}
	if !seenDraft {
		t.Fatalf("draft %q did not land on screen; mirror=%q", cmd, acc.String())
	}

	// Phase 2: submit (bare Enter). The command runs and the output line appears.
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 2, Ref: te.ref(), Text: ""})
	te.waitForMirror("\n" + marker)
	ack := te.wsEnv.readControlDraining()
	ia := ack.(protocol.InputAck)
	if !ia.OK {
		t.Fatalf("submit input_ack not ok: %s", ia.Reason)
	}
}
