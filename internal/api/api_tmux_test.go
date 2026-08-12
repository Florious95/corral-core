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
	// pipe is actually attached).
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 1, Ref: te.ref(), Text: "MIRROR_MARK_77"})
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

	// Request far more history than exists: from_line=-500, count=20. The page
	// is entirely above the oldest available line, so it must clamp to the
	// oldest history page (which the count keeps strictly in history, never
	// reaching the screen).
	te.wsEnv.sendFrame(&protocol.Scrollback{ReqID: 9, Ref: te.ref(), FromLine: -500, Count: 20})

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
	if payload.LineCount > 20 {
		t.Errorf("line_count = %d, exceeds requested count 20", payload.LineCount)
	}
	// The page must be clamped to the OLDEST available history (SCBK_1) and stay
	// strictly above the screen (no SCBK_60, which is on the visible tail).
	if !bytes.Contains(payload.Data, []byte("SCBK_1")) {
		t.Errorf("scrollback page must contain the oldest history line SCBK_1; got %q", payload.Data)
	}
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

// ---- D-36 服务端坐标红测（fix-scrollback-history-d36）----
//
// 坐标系定义（写死，防再次错位）：协议 §6.3 与 tmux capture-pane -S/-E 是**同一坐标系**，
// 顶部相对：0 = 当前屏顶行，负数 = 屏上历史行（-1 = 屏顶上一行，-2 = 再上一行…）。
// 服务端把协议 from_line/count 直传 tmux，**禁止**做 ±pane.Height 平移（平移会把当前屏
// 打成历史、把历史页锚点打偏）。historySize = capture-pane 从最老到屏顶上一行
// (-S MinInt32 -E -1) 的行数，**不得再减 pane.Height**（-E -1 已排除屏幕，减了即双计屏）。
//
// 上述两处缺陷修复前，以下两条用例必红；修复后转绿。

// scbkReadReply drains interleaving binary frames (mirror deltas of the
// injected loop's echo) until the scrollback reply (KindScrollback) arrives.
func (te *tmuxEnv) scbkReadReply() protocol.BinaryPayload {
	te.t.Helper()
	for i := 0; i < 50; i++ {
		typ, data, err := te.wsEnv.conn.Read(context.Background())
		if err != nil {
			te.t.Fatalf("read scrollback reply: %v", err)
		}
		if typ != websocket.MessageBinary {
			continue
		}
		p, err := protocol.DecodeBinary(data)
		if err != nil {
			te.t.Fatalf("decode binary: %v", err)
		}
		if p.Kind == protocol.KindScrollback {
			return p
		}
	}
	te.t.Fatal("scrollback reply never arrived")
	return protocol.BinaryPayload{}
}

// stripANSI strips CSI/SGR escape sequences from capture-pane -e output.
func stripANSI(s string) string {
	var b strings.Builder
	for i := 0; i < len(s); i++ {
		if s[i] == 0x1b {
			if i+1 < len(s) && s[i+1] == '[' {
				j := i + 2
				for j < len(s) && !((s[j] >= 'A' && s[j] <= 'Z') || (s[j] >= 'a' && s[j] <= 'z')) {
					j++
				}
				if j < len(s) {
					i = j
				}
				continue
			}
		}
		b.WriteByte(s[i])
	}
	return b.String()
}

// scbkMarkerNum parses "SBCMARK_<n>" (or any prefix_<n>) from a line.
func scbkMarkerNum(line, prefix string) (int, bool) {
	idx := strings.Index(line, prefix+"_")
	if idx < 0 {
		return 0, false
	}
	n := 0
	for _, c := range line[idx+len(prefix)+1:] {
		if c < '0' || c > '9' {
			break
		}
		n = n*10 + int(c-'0')
	}
	return n, n > 0
}

// scbkSplitLines splits capture output on newlines, dropping a trailing empty.
func scbkSplitLines(s string) []string {
	parts := strings.Split(s, "\n")
	if n := len(parts); n > 0 && parts[n-1] == "" {
		parts = parts[:n-1]
	}
	return parts
}

// TestScrollbackCurrentScreenMatchesVisible is the D-36 red test A: a
// scrollback(0, count) request — the current-screen page the client pulls when
// it starts scrolling — must return the pane's CURRENT VISIBLE screen, with the
// data line count matching the metadata line_count. Today the server translates
// protocol 0 by subtracting pane.Height, so the page lands on history above the
// screen (content never reaches the visible bottom, and line_count lies). RED.
func TestScrollbackCurrentScreenMatchesVisible(t *testing.T) {
	te := startTmuxEnv(t, "bash")
	// Small screen so the 60-line marker run overflows into history quickly.
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 8, Cols: 60})
	_ = te.readBinaryFrame() // snapshot
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 1, Ref: te.ref(),
		Text: "for i in $(seq 1 60); do echo SBCMARK_$i; done"})
	te.waitForMirror("SBCMARK_60")

	// Read the actual visible screen via tmux (capture-pane) to know its
	// top/bottom markers — the screen the current-screen page MUST cover.
	// (Using runTmuxCmd, not readBinaryFrame: the subscribe snapshot was already
	// consumed above and the mirror deltas carry loop echo, not a clean screen.)
	out, err := runTmuxCmd(te.env, te.sock, "capture-pane", "-e", "-p", "-t", te.paneID)
	if err != nil {
		t.Fatalf("capture visible screen: %v\n%s", err, out)
	}
	vis := scbkSplitLines(stripANSI(out))
	var topNum, botNum int
	for _, l := range vis {
		if n, ok := scbkMarkerNum(l, "SBCMARK"); ok {
			if topNum == 0 || n < topNum {
				topNum = n
			}
			if n > botNum {
				botNum = n
			}
		}
	}
	if botNum == 0 {
		t.Fatalf("visible screen has no SBCMARK; capture=%q", out)
	}
	if botNum != 60 {
		t.Fatalf("visible screen bottom = SBCMARK_%d, want SBCMARK_60 (loop not flushed?)", botNum)
	}

	te.wsEnv.sendFrame(&protocol.Scrollback{ReqID: 9, Ref: te.ref(), FromLine: 0, Count: 8})
	payload := te.scbkReadReply()
	pageLines := scbkSplitLines(stripANSI(string(payload.Data)))
	// [red 1] 数据行数必须等于元数据 line_count（§6.3 自洽）。
	if len(pageLines) != int(payload.LineCount) {
		t.Errorf("D-36: current-screen page data lines = %d, metadata line_count = %d (mismatch)",
			len(pageLines), payload.LineCount)
	}
	// [red 2] 页必须到达可见屏底部（含最末输出 SBCMARK_60）。
	lastNum := 0
	for _, l := range pageLines {
		if n, ok := scbkMarkerNum(l, "SBCMARK"); ok {
			lastNum = n
		}
	}
	if lastNum < botNum {
		t.Errorf("D-36: current-screen page last = SBCMARK_%d, must reach visible bottom SBCMARK_%d (page is history, not screen)",
			lastNum, botNum)
	}
	// [red 3] 页首行必须在可见屏顶（或紧邻其上方 1 行），而非历史深处。
	firstNum := 0
	for _, l := range pageLines {
		if n, ok := scbkMarkerNum(l, "SBCMARK"); ok {
			firstNum = n
			break
		}
	}
	if firstNum < topNum-1 {
		t.Errorf("D-36: current-screen page first = SBCMARK_%d, must start at/near visible top SBCMARK_%d (page is history)",
			firstNum, topNum)
	}
}

// TestScrollbackHistoryMetaMatchesContent is the D-36 red test B: a history
// page's metadata from_line must match the page's actual content — the client
// anchors its scroll viewport on that metadata. Today the pane.Height
// translation shifts the anchor by one screen. RED.
func TestScrollbackHistoryMetaMatchesContent(t *testing.T) {
	te := startTmuxEnv(t, "bash")
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 5, Cols: 60})
	_ = te.readBinaryFrame() // snapshot
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 1, Ref: te.ref(),
		Text: "for i in $(seq 1 40); do echo SBHIST_$i; done"})
	te.waitForMirror("SBHIST_40")

	// Visible screen top marker T → protocol 0 is at SBHIST_T.
	out, err := runTmuxCmd(te.env, te.sock, "capture-pane", "-e", "-p", "-t", te.paneID)
	if err != nil {
		t.Fatalf("capture visible screen: %v\n%s", err, out)
	}
	vis := scbkSplitLines(stripANSI(out))
	topNum := 0
	for _, l := range vis {
		if n, ok := scbkMarkerNum(l, "SBHIST"); ok {
			topNum = n
			break
		}
	}
	if topNum == 0 {
		t.Fatalf("visible screen top has no SBHIST marker; capture=%q", out)
	}

	// 请求完全在历史之上的页：from_line=-30 count=5 → 收敛到最老页。
	te.wsEnv.sendFrame(&protocol.Scrollback{ReqID: 5, Ref: te.ref(), FromLine: -30, Count: 5})
	payload := te.scbkReadReply()
	pageLines := scbkSplitLines(stripANSI(string(payload.Data)))
	if len(pageLines) == 0 {
		t.Fatal("history page empty")
	}
	firstNum := 0
	for _, l := range pageLines {
		if n, ok := scbkMarkerNum(l, "SBHIST"); ok {
			firstNum = n
			break
		}
	}
	if firstNum == 0 {
		t.Fatalf("history page first line has no SBHIST marker; page=%q", payload.Data)
	}
	// 协议坐标推导：协议 0 = 屏顶 topNum，页首行 firstNum 的协议行 = firstNum - topNum。
	expected := firstNum - topNum
	if payload.FromLine != int32(expected) {
		t.Errorf("D-36: history page meta.from_line = %d, content implies %d (SBHIST_%d at protocol coord %d−%d); anchor shifted",
			payload.FromLine, expected, firstNum, firstNum, topNum)
	}
	// 数据行数自洽。
	if len(pageLines) != int(payload.LineCount) {
		t.Errorf("D-36: history page data lines = %d, metadata line_count = %d (mismatch)",
			len(pageLines), payload.LineCount)
	}
}
