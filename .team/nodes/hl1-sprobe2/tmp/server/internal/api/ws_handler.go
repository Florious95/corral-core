package api

// ws_handler.go implements the per-frame protocol logic: auth, list, subscribe
// (snapshot + delta stream), unsubscribe, input (decidable ack), scrollback
// (converged range + 12-byte metadata header), and resize. Every C→S frame has
// a decidable result — an ack, a data reply, or an error frame; no frame is
// ever swallowed silently (knowledge-base red line).

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"math"

	"github.com/agentmirror/agentmirror/internal/bridge"
	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

// snapshotWithCursor captures the pane's visible screen and re-anchors the
// cursor inside the returned bytes (fix-term-residuals). Two transformations
// over the raw capture:
//
//  1. trailing blank lines are trimmed — capture-pane emits the full pane
//     height as bare LFs with no cursor state, so replaying them only walks
//     the client cursor to the bottom row (and risks a scroll-up on the last
//     terminator); a replay clears the grid first, so trailing blanks carry
//     zero information;
//  2. a cursor-position escape (CUP, 1-based) matching the pane's REAL cursor
//     is appended, so the client's VT engine lands the cursor exactly where
//     the pane's is. Without it, the next delta without absolute addressing
//     (bash's SIGWINCH prompt redraw is plain "\r ESC[K …") prints at the
//     capture's end instead of the real cursor row — the phantom-prompt
//     residual seen on device.
//
// Both stay inside the snapshot's existing "raw ANSI bytes" contract: zero
// protocol change, zero client change (docs/protocol.md §6.2).
func snapshotWithCursor(ctx context.Context, br *bridge.Pane) ([]byte, error) {
	snap, err := br.Snapshot(ctx)
	if err != nil {
		return nil, err
	}
	x, y, err := br.CursorPos(ctx)
	if err != nil {
		return nil, err
	}
	snap = bytes.TrimRight(snap, "\n")
	return append(snap, []byte(fmt.Sprintf("\x1b[%d;%dH", y+1, x+1))...), nil
}

// handleAuth validates the pairing token and answers auth_ack. On rejection the
// connection is closed right after the ack, so the client can treat
// "closed right after auth" as a rejection (docs/protocol.md §4.2). The token
// is never echoed and never logged (§9).
func (c *wsConn) handleAuth(a protocol.Auth) bool {
	if c.s.tokenValidator.ValidateToken(c.ctx, a.Token) {
		c.authed.Store(true)
		// The connection is now a live client: count it so the listing loop
		// wakes for the 0→1 transition and keeps polling (idle-gate, taskbook
		// #fix-daemon-idle-cpu). teardown un-counts it on close.
		c.s.markAuthed()
		c.send(&protocol.AuthAck{OK: true})
		return true
	}
	c.send(&protocol.AuthAck{OK: false, Reason: "invalid token"})
	c.sendClose(websocket.StatusPolicyViolation, "unauthorized")
	return false
}

// handleList answers a full listing (docs/protocol.md §5.1, requirement 069).
// It always triggers one real rescan — not ensureInitialScan, which no-ops
// once a snapshot exists. On scan failure the last snapshot is kept so the
// reply is never an empty wipe of a known world.
func (c *wsConn) handleList(l protocol.List) {
	prev, prevSeq := c.s.currentSnapshot()
	prevN := snapshotSessionCount(prev)
	err := c.s.refreshListing(c.ctx)
	snap, seq := c.s.currentSnapshot()
	curN := snapshotSessionCount(snap)
	c.s.log.Info("listing: refresh on open",
		"req_id", l.ReqID,
		"had_cache", prev != nil,
		"prev_seq", prevSeq,
		"prev_sessions", prevN,
		"cur_sessions", curN,
		"cur_seq", seq,
		"refresh_err", errString(err),
	)
	if seq == 0 {
		c.s.snapMu.Lock()
		if c.s.seq == 0 {
			c.s.seq = 1
		}
		seq = c.s.seq
		c.s.snapMu.Unlock()
	}
	listing := &protocol.Listing{ReqID: l.ReqID, Seq: seq}
	if snap != nil {
		listing.Workspaces = snap.listing()
	}
	c.send(listing)
}

func snapshotSessionCount(snap *modelSnapshot) int {
	if snap == nil {
		return 0
	}
	return len(snap.byRef)
}

func errString(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}

// handleSubscribe starts mirroring a session: capture the pane's current
// screen, resize to the client's dims, attach the pipe (bridge.Subscribe),
// send that snapshot, then relay deltas. Re-subscribing the same ref is
// idempotent: the previous subscription is torn down and a fresh snapshot is
// replayed (requirement 004 reconnect replay). A failure to subscribe is an
// error frame.
func (c *wsConn) handleSubscribe(s protocol.Subscribe) {
	// 订阅计数（含首次与重复订阅；重复订阅 = 重连或客户端重订阅 → 推完整快照 → 整屏重建）。
	c.s.sendQueue.recordSubscribe()
	c.connMetrics.recordSubscribe()
	// Ensure the catalog is populated before resolving the ref, so a client
	// that subscribes immediately after auth (before the listing loop's first
	// tick) can still address the pane it was just shown.
	c.s.ensureInitialScan(c.ctx)

	br, _, ok := c.resolvePane(s.Ref)
	if !ok {
		c.sendError(protocol.ErrCodeSessionNotFound, "unknown session ref")
		return
	}
	c.subscribeCancel(s.Ref)

	// Pane-level original-geometry accounting (fix-host-pane-geometry-accounting):
	// the first subscriber of this pane snapshots its pre-phone geometry as the
	// shared baseline; later subscribers (other connections to the same pane) only
	// bump the count and never rebase it. The restore happens when the last
	// subscriber leaves (see paneGeometry.release), so the pane always returns to
	// the same geometry regardless of how many clients came and went in between.
	geom := c.s.geometryFor(s.Ref)
	_, _, _ = geom.acquire(c.ctx, br)

	// Capture BEFORE Resize. Phone geometry ≠ host size, so Resize sends
	// SIGWINCH; an idle TUI that clears and does not redraw leaves
	// capture-pane empty, and snapshotWithCursor still emits a CUP-only
	// KindSnapshot (world B). The first frame must be the glyphs already on
	// the pane at subscribe time — not a post-clear window, and not a wait
	// for a later delta.
	snap, err := snapshotWithCursor(c.ctx, br)
	if err != nil {
		c.sendError(protocol.ErrCodeSessionNotFound, "pane unavailable")
		return
	}

	// Initial client dims reshape the pane so the CLI redraws for the phone
	// (requirement 005). A resize failure is not fatal: the mirror continues at
	// the pane's current size. Pipe attaches after capture+resize so a
	// SIGWINCH clear is not relayed as a delta that would wipe the first frame.
	if _, _, err := br.Resize(c.ctx, int(s.Cols), int(s.Rows)); err != nil {
		c.logErr("subscribe resize", err)
	}

	ch, detach, err := br.Subscribe(c.ctx)
	if err != nil {
		c.sendError(protocol.ErrCodeInternal, "cannot attach mirror")
		return
	}

	frame, err := protocol.EncodeBinary(protocol.BinaryPayload{
		Kind: protocol.KindSnapshot,
		Ref:  s.Ref,
		Data: snap,
	})
	if err != nil {
		detach()
		c.sendError(protocol.ErrCodeInternal, "cannot encode snapshot")
		return
	}
	c.sendBinary(frame)

	subCtx, cancel := context.WithCancel(c.ctx)
	sub := &subscription{ref: s.Ref, cancel: cancel, detach: detach}
	// Release hook: the pane-level geometry tracker is released when this
	// subscription ends. When it is the last subscriber the tracker restores the
	// pane to the shared original baseline (契约 2: teardown/closeSubscriptions/
	// relay exit all call restoreSize, so every exit path hits the same release →
	// last-leaver restores). geomOK==false means no baseline was captured (Size
	// failed); release is then a no-op rather than restoring a guessed size.
	sub.restoreSize = func() {
		geom.release(c.ctx, br, c.s.log, s.Ref)
	}
	c.subscribeAdd(sub)
	go c.relay(subCtx, sub, ch)
}

// handleUnsubscribe stops mirroring a session. Idempotent: unsubscribing a
// session that is not subscribed is not an error and produces no reply
// (docs/protocol.md §4.2).
func (c *wsConn) handleUnsubscribe(u protocol.Unsubscribe) {
	c.subscribeCancel(u.Ref)
}

// handleInput delivers passthrough input (requirement 059, replacing 003
// clause 1's whole-line injection) OR a set of named special keys, and MUST
// answer with input_ack (requirement 003 send-must-arrive): ok:true once the
// input entered the pane, or a machine-readable failure reason. Every failure
// class in §7.3 is decidable and surfaced.
//
// Passthrough semantics (059): the CLI input box is the draft, so a non-empty
// Text is TYPED into the pane without an Enter (TypeKeys); an empty Text with
// no attachment is a bare Enter — the send button only commits what is already
// in the CLI input box. This replaces the old "inject whole line then Enter".
//
// The Keys path (R-1 shortcut bar, requirement 017) sends named keys without
// an Enter — "press that key once". (Text or AttachmentPath) and Keys are
// mutually exclusive; the frame validator (Input.Validate) already rejected a
// frame carrying both, so at most one branch runs.
//
// AttachmentPath (feat-image-upload-inline; two-step preview added by
// requirement 057) routes one of two ways, chosen here by consumeAttachPreview:
//   - a matching AttachPreview was recorded for this ref+path ⇒
//     bridge.Pane.InjectAfterPreview, which does NOT re-paste (already done at
//     upload time) and only waits out whatever remains of
//     bridge.PasteSettleDelay since that preview — typically zero, once the
//     user's own typing covered it (requirement 057 clause 5: normal path is
//     zero wait, not "a little wait").
//   - no match (empty path, stale preview, or a client that never called
//     AttachPreview) ⇒ bridge.Pane.InjectWithAttachment, the original
//     paste-here-and-now-then-wait-the-full-delay path — the compatibility
//     fallback requirement 057 keeps rather than dropping. Byte-identical to
//     plain Inject when AttachmentPath is empty.
func (c *wsConn) handleInput(i protocol.Input) {
	ack := func(ok bool, reason protocol.InputFailReason) {
		c.send(&protocol.InputAck{ReqID: i.ReqID, OK: ok, Reason: reason})
	}

	if !c.subscribed(i.Ref) {
		ack(false, protocol.InputFailNotSubscribed)
		return
	}
	// Catalog populated before resolving (a client can address a pane shown in
	// a listing it received before the loop's first tick).
	c.s.ensureInitialScan(c.ctx)
	br, ok := c.resolveBridge(i.Ref)
	if !ok {
		ack(false, protocol.InputFailSessionNotFound)
		return
	}
	// Copy-mode safety bailout (feat-remote-scroll-forward leader Q2 mandate):
	// if the pane is in tmux copy-mode when the user types, the keystrokes
	// would be consumed by copy-mode commands rather than reaching the
	// shell/TUI. Exit copy-mode first so text arrives at its intended target.
	// This is a best-effort pre-flight: a PaneInMode failure is non-fatal
	// (the injection proceeds; the pane will likely report ErrPaneNotFound).
	// ExitCopyMode is idempotent — cancel on a normal pane is a tmux no-op.
	if inMode, modeErr := br.PaneInMode(c.ctx); modeErr == nil && inMode {
		if exitErr := br.ExitCopyMode(c.ctx); exitErr == nil {
			c.send(&protocol.PaneModeChanged{Ref: i.Ref, InCopyMode: false})
		}
	}

	// Named-key injection: no size gate (the closed key set is tiny and fixed),
	// no trailing Enter, same decidable ack.
	if len(i.Keys) > 0 {
		// bridge.SendKeys takes wire key names as strings; the protocol Key
		// values are those exact strings (protocol.Key is a string kind).
		names := make([]string, len(i.Keys))
		for n, k := range i.Keys {
			names[n] = string(k)
		}
		if err := br.SendKeys(c.ctx, names...); err != nil {
			if errors.Is(err, bridge.ErrPaneNotFound) {
				ack(false, protocol.InputFailSessionNotFound)
			} else {
				// Any tmux refusal (dead server, timeout, unknown) means the
				// send-keys did not go in.
				ack(false, protocol.InputFailInjectFailed)
			}
			return
		}
		ack(true, "")
		return
	}
	if len(i.Text) > c.s.maxInput {
		ack(false, protocol.InputFailTooLarge)
		return
	}
	// 直通输入（059，取代 003 第1条「一次性注入」）：App 键盘每键直通，CLI 输入框即草稿。
	// 三种路径：
	//   1. AttachmentPath 非空 → 提交带图（预贴路径已在 pane，发文字[若有]+Enter 提交；
	//      命中预贴记录走 InjectAfterPreview 只补沉降，未命中走 InjectWithAttachment 兼容）；
	//   2. Text 非空且无附件 → 直通：文本打到 CLI 输入框，**不追加 Enter**（TypeKeys）；
	//   3. Text 为空且无附件 → 裸 Enter：发送键只提交。
	var err error
	if i.AttachmentPath != "" {
		if elapsed, ok := c.s.consumeAttachPreview(i.Ref, i.AttachmentPath); ok {
			err = br.InjectAfterPreview(c.ctx, i.Text, remainingSettleDelay(elapsed))
		} else {
			err = br.InjectWithAttachment(c.ctx, i.Text, i.AttachmentPath)
		}
	} else if i.Text != "" {
		err = br.TypeKeys(c.ctx, i.Text)
	} else {
		err = br.Inject(c.ctx, "")
	}
	if err != nil {
		if errors.Is(err, bridge.ErrPaneNotFound) {
			ack(false, protocol.InputFailSessionNotFound)
		} else {
			// Any tmux refusal (dead server, timeout, unknown) means the
			// send-keys did not go in.
			ack(false, protocol.InputFailInjectFailed)
		}
		return
	}
	ack(true, "")
}

// handleAttachPreview pastes an image path into a pane ahead of send
// (requirement 057): the moment upload succeeds, not at send time, so Claude
// Code's async decode/cache-write runs in the background while the user
// keeps typing. No ack on success (the mirror delta stream carries the
// `[Image #N]` result, same doctrine as ScrollWheel); TypeError on failure.
// Never clears anything already in the pane (requirement 057 clause 3): an
// unconfirmed preview is left visible, not silently wiped.
func (c *wsConn) handleAttachPreview(m protocol.AttachPreview) {
	if !c.subscribed(m.Ref) {
		c.sendError(protocol.ErrCodeSessionNotFound, "not subscribed to session")
		return
	}
	c.s.ensureInitialScan(c.ctx)
	br, ok := c.resolveBridge(m.Ref)
	if !ok {
		c.sendError(protocol.ErrCodeSessionNotFound, "unknown session ref")
		return
	}
	if err := br.PastePreview(c.ctx, m.Path); err != nil {
		if errors.Is(err, bridge.ErrPaneNotFound) {
			c.sendError(protocol.ErrCodeSessionNotFound, "pane unavailable")
		} else {
			c.sendError(protocol.ErrCodeInternal, "attach preview failed")
		}
		return
	}
	c.s.recordAttachPreview(m.Ref, m.Path)
}

// handleScrollWheel delivers one scroll-wheel gesture to a remote pane
// (feat-remote-scroll-forward). No ack on success — the mirror delta stream
// carries the visual result, and a per-notch round-trip at ~123ms RTT would
// make the gesture feel sticky. TypeError on failure (pane gone / tmux error).
// When the pane enters copy-mode, pushes TypePaneModeChanged so the App can
// show a minimal indicator (leader Q2 mandate: user must know when copy-mode
// is active to avoid "typed but nothing happened" confusion).
func (c *wsConn) handleScrollWheel(sw protocol.ScrollWheel) {
	if !c.subscribed(sw.Ref) {
		c.sendError(protocol.ErrCodeSessionNotFound, "not subscribed to session")
		return
	}
	c.s.ensureInitialScan(c.ctx)
	br, ok := c.resolveBridge(sw.Ref)
	if !ok {
		c.sendError(protocol.ErrCodeSessionNotFound, "unknown session ref")
		return
	}
	enteredCopyMode, err := br.InjectScroll(c.ctx, sw.Delta)
	if err != nil {
		if errors.Is(err, bridge.ErrPaneNotFound) {
			c.sendError(protocol.ErrCodeSessionNotFound, "pane unavailable")
		} else {
			c.sendError(protocol.ErrCodeInternal, "scroll injection failed")
		}
		return
	}
	if enteredCopyMode {
		c.send(&protocol.PaneModeChanged{Ref: sw.Ref, InCopyMode: true})
	}
}

// handleScrollback fetches one line range of history (docs/protocol.md §4.2,
// §6.3). The request's from_line is addressed in capture-pane semantics: 0 =
// the visible screen's top row, negative = history above it. The server clamps
// the request to the pane's available range and reports the ACTUAL range in
// the binary reply's 12-byte header so the client can anchor its scroll
// viewport without guessing.
func (c *wsConn) handleScrollback(sc protocol.Scrollback) {
	c.s.ensureInitialScan(c.ctx)
	br, pane, ok := c.resolvePane(sc.Ref)
	if !ok {
		c.sendError(protocol.ErrCodeSessionNotFound, "unknown session ref")
		return
	}

	start, end, err := c.scrollbackRange(c.ctx, br, pane, int(sc.FromLine), int(sc.Count))
	if err != nil {
		c.sendError(protocol.ErrCodeSessionNotFound, "pane unavailable")
		return
	}

	// Protocol scrollback coordinates are top-relative (0 = screen top, negative =
	// history above) — identical to tmux capture-pane -S/-E. Pass them straight
	// through (D-36): the old `- pane.Height` translation assumed bottom-relative
	// tmux semantics and shifted every page into history (current-screen requests
	// returned stale history, history pages reported wrong anchors).
	data, err := br.Scrollback(c.ctx, start, end)
	if err != nil {
		if errors.Is(err, bridge.ErrPaneNotFound) {
			c.sendError(protocol.ErrCodeSessionNotFound, "pane unavailable")
		} else {
			c.sendError(protocol.ErrCodeInternal, "scrollback failed")
		}
		return
	}

	// Trim trailing blank rows (consistent with snapshotWithCursor): capture-pane
	// emits a pane's blank bottom rows as bare LFs past the content. Trimming keeps
	// the reported line_count (§6.3 实际区间) equal to the actual non-blank lines,
	// which the client uses to anchor its scrollback buffer.
	data = bytes.TrimRight(data, "\n")
	lineCount := uint32(countLines(data))
	if lineCount == 0 {
		// Degenerate fully-blank page: report one empty line (EncodeBinary requires
		// LineCount >= 1); a blank page carries no content either way.
		lineCount = 1
		data = []byte("\n")
	}

	frame, err := protocol.EncodeBinary(protocol.BinaryPayload{
		Kind:      protocol.KindScrollback,
		Ref:       sc.Ref,
		ReqID:     sc.ReqID,
		FromLine:  int32(start),
		LineCount: lineCount,
		Data:      data,
	})
	if err != nil {
		c.sendError(protocol.ErrCodeInternal, "cannot encode scrollback")
		return
	}
	c.sendBinary(frame)
}

// handleResize reports the client's terminal dims (docs/protocol.md §4.2). It
// applies only to subscribed sessions (requirement 005: whoever last operated
// the pane wins). An unknown ref is an error; resize on an unsubscribed but
// known session is a no-op, and there is no resize ack frame — the fresh
// snapshot pushed after a successful resize is the de-facto receipt.
func (c *wsConn) handleResize(r protocol.Resize) {
	c.s.ensureInitialScan(c.ctx)
	br, ok := c.resolveBridge(r.Ref)
	if !ok {
		c.sendError(protocol.ErrCodeSessionNotFound, "unknown session ref")
		return
	}
	if !c.subscribed(r.Ref) {
		return
	}
	// D-27 (fix-d27-v3): detect no-op resizes by comparing the pane's ACTUAL
	// dims before and after the resize-window call (both fresh reads, never
	// the request values — tmux may converge a same-size request to the same
	// pane size). A resize that did not change the pane must NOT re-push a
	// snapshot: the client replays a snapshot as clear-and-rebuild, which on
	// the phone reads as the "top-down line-by-line refresh" D-27 reports.
	// The IME keyboard/input-box relayout that follows every message send
	// produces exactly these same-size resizes (fix-refresh-direction
	// root-cause chain step 3), so skipping the no-op repush closes the only
	// production path to the flicker without touching the protocol.
	beforeW, beforeH, err := br.Size(c.ctx)
	if err != nil {
		c.logErr("resize read before", err)
		// A size read failure should not silently abort: fall through and let
		// the resize attempt itself decide (Resize re-reads below).
		beforeW, beforeH = -1, -1
	}
	if _, _, err := br.Resize(c.ctx, int(r.Cols), int(r.Rows)); err != nil {
		if errors.Is(err, bridge.ErrPaneNotFound) {
			c.sendError(protocol.ErrCodeSessionNotFound, "pane unavailable")
		} else {
			c.sendError(protocol.ErrCodeInternal, "resize failed")
		}
		return
	}
	afterW, afterH, err := br.Size(c.ctx)
	if err != nil {
		c.logErr("resize read after", err)
		afterW, afterH = -1, -1
	}
	if beforeW >= 0 && beforeW == afterW && beforeH == afterH {
		// Pane dims unchanged by the resize: no reflow happened, so there is
		// no new geometry to converge. Skip the snapshot repush — the client
		// keeps its grid and the delta stream stays authoritative (004).
		c.s.log.Debug("ws: resize no-op, skip snapshot", "conn", c.id, "ref", r.Ref, "dims", fmt.Sprintf("%dx%d", beforeW, beforeH))
		return
	}
	// Re-push a full snapshot after a REAL reflow (fix-term-residuals): the
	// CLI's SIGWINCH redraw arrives only as deltas composited over the
	// client's stale old-geometry grid, so leftover residue can never be
	// cleared deterministically by the stream alone. A snapshot is replayed
	// by the client as clear-and-rebuild (same semantics as the subscribe
	// first frame), which is the single convergence point. tmux reflows the
	// pane synchronously on resize-window, so capturing right after Resize is
	// content-correct; any in-flight pre-resize delta the relay still sends
	// afterwards is redundant repaint bytes, not residue (docs/protocol.md
	// §4.2 resize).
	// 溯源计数：handleResize 真实 reflow 补发的快照（非首帧快照的路径来源，见 sendq_metrics）。
	c.s.sendQueue.recordResizeSnapshot()
	c.connMetrics.recordResizeSnapshot()
	snap, err := snapshotWithCursor(c.ctx, br)
	if err != nil {
		c.sendError(protocol.ErrCodeSessionNotFound, "pane unavailable")
		return
	}
	frame, err := protocol.EncodeBinary(protocol.BinaryPayload{
		Kind: protocol.KindSnapshot,
		Ref:  r.Ref,
		Data: snap,
	})
	if err != nil {
		c.sendError(protocol.ErrCodeInternal, "cannot encode snapshot")
		return
	}
	c.sendBinary(frame)
}

// scrollbackRange converges a scrollback request (protocol from_line/count,
// 0 = screen top, negative = history) to the pane's available range and
// returns the actual [start, end] in protocol coordinates. Convergence policy:
// clamp to the available range; a request entirely above the history (or
// entirely below the screen) is shifted to the nearest available edge so the
// client receives a useful page instead of a single degenerate line.
func (c *wsConn) scrollbackRange(ctx context.Context, br *bridge.Pane, pane discovery.Pane, fromLine, count int) (int, int, error) {
	// historySize = how many lines of history tmux retains above the screen.
	// Measured by capturing from the oldest possible line to the line just above
	// the screen top (-1) — top-relative semantics, so the capture is exactly the
	// history, no screen rows, no height subtraction needed (D-36: the old
	// `- pane.Height` double-counted the screen against tmux's top-relative coords
	// and under-reported history).
	oldestToBottom, err := br.Scrollback(ctx, math.MinInt32, -1)
	if err != nil {
		return 0, 0, err
	}
	historySize := countLines(oldestToBottom)
	if historySize < 0 {
		historySize = 0
	}

	// Available range in protocol coordinates (0 = screen top, negative = history).
	oldest := -historySize
	bottom := pane.Height - 1

	requestEnd := fromLine + count - 1
	switch {
	case requestEnd <= oldest:
		// Entirely above the history (or ending exactly at the oldest line): shift
		// so the page starts at the oldest available line and grab count lines —
		// a useful full page, not a degenerate sliver. Cap at the last history line
		// (-1 = line above screen top), never onto the visible screen: an above-history
		// request asks for history, so the reply must not leak on-screen rows
		// (TestScrollbackConvergedRange: scrollback(-500,100) must return only history).
		// D-36: scrollback(-30,5) with 26 history lines → (-26,-22) = the 5 oldest.
		start, end := oldest, oldest+count-1
		if end > -1 {
			end = -1
		}
		return start, end, nil
	case fromLine > bottom:
		// Entirely below the screen: shift so the page ends at the bottom row.
		start, end := bottom-count+1, bottom
		if start < oldest {
			start = oldest
		}
		return start, end, nil
	default:
		// Overlap: clamp both edges.
		start := fromLine
		if start < oldest {
			start = oldest
		}
		end := requestEnd
		if end > bottom {
			end = bottom
		}
		if start > end {
			// Degenerate single row at the boundary.
			end = start
		}
		return start, end, nil
	}
}

// countLines counts the newline-delimited lines in a capture-pane result,
// tolerating a missing trailing newline.
func countLines(data []byte) int {
	n := 0
	for _, b := range data {
		if b == '\n' {
			n++
		}
	}
	if n > 0 && data[len(data)-1] != '\n' {
		n++
	}
	return n
}
