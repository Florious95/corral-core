package api

// ws_handler.go implements the per-frame protocol logic: auth, list, subscribe
// (snapshot + delta stream), unsubscribe, input (decidable ack), scrollback
// (converged range + 12-byte metadata header), and resize. Every C→S frame has
// a decidable result — an ack, a data reply, or an error frame; no frame is
// ever swallowed silently (knowledge-base red line).

import (
	"context"
	"errors"
	"math"

	"github.com/agentmirror/agentmirror/internal/bridge"
	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

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

// handleList answers a full listing (docs/protocol.md §5.1). It performs the
// first scan synchronously so the reply is real, not an empty shell, and
// carries the current shared seq.
func (c *wsConn) handleList(l protocol.List) {
	c.s.ensureInitialScan(c.ctx)
	snap, seq := c.s.currentSnapshot()
	listing := &protocol.Listing{ReqID: l.ReqID, Seq: seq}
	if snap != nil {
		listing.Workspaces = snap.listing()
	}
	c.send(listing)
}

// handleSubscribe starts mirroring a session: resize the pane to the client's
// dims, attach the pipe (bridge.Subscribe), send a full snapshot, then relay
// deltas. Re-subscribing the same ref is idempotent: the previous subscription
// is torn down and a fresh snapshot is replayed (requirement 004 reconnect
// replay). A failure to subscribe is an error frame.
func (c *wsConn) handleSubscribe(s protocol.Subscribe) {
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

	// Initial client dims reshape the pane so the CLI redraws for the phone
	// (requirement 005). A resize failure is not fatal: the mirror continues at
	// the pane's current size, and the real existence check happens below.
	if _, _, err := br.Resize(c.ctx, int(s.Cols), int(s.Rows)); err != nil {
		c.logErr("subscribe resize", err)
	}

	// Attach the pipe before the snapshot so no output between the two is lost
	// (term-bridge knowledge base: pipe first, then capture).
	ch, detach, err := br.Subscribe(c.ctx)
	if err != nil {
		c.sendError(protocol.ErrCodeInternal, "cannot attach mirror")
		return
	}

	snap, err := br.Snapshot(c.ctx)
	if err != nil {
		detach()
		c.sendError(protocol.ErrCodeSessionNotFound, "pane unavailable")
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
	c.subscribeAdd(sub)
	go c.relay(subCtx, sub, ch)
}

// handleUnsubscribe stops mirroring a session. Idempotent: unsubscribing a
// session that is not subscribed is not an error and produces no reply
// (docs/protocol.md §4.2).
func (c *wsConn) handleUnsubscribe(u protocol.Unsubscribe) {
	c.subscribeCancel(u.Ref)
}

// handleInput injects one whole text line and MUST answer with input_ack
// (requirement 003 send-must-arrive): ok:true once the bytes entered the pane,
// or a machine-readable failure reason. Every failure class in §7.3 is
// decidable and surfaced.
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
	if len(i.Text) > c.s.maxInput {
		ack(false, protocol.InputFailTooLarge)
		return
	}
	if err := br.Inject(c.ctx, i.Text); err != nil {
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

	// bridge.Scrollback addresses lines relative to the screen bottom
	// (-1 = bottom row), whereas the protocol addresses them relative to the
	// screen top. Translate: protocol row F maps to tmux row F - paneHeight.
	data, err := br.Scrollback(c.ctx, start-pane.Height, end-pane.Height)
	if err != nil {
		if errors.Is(err, bridge.ErrPaneNotFound) {
			c.sendError(protocol.ErrCodeSessionNotFound, "pane unavailable")
		} else {
			c.sendError(protocol.ErrCodeInternal, "scrollback failed")
		}
		return
	}

	frame, err := protocol.EncodeBinary(protocol.BinaryPayload{
		Kind:      protocol.KindScrollback,
		Ref:       sc.Ref,
		ReqID:     sc.ReqID,
		FromLine:  int32(start),
		LineCount: uint32(end - start + 1),
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
// known session is a no-op, and there is no resize ack frame.
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
	if _, _, err := br.Resize(c.ctx, int(r.Cols), int(r.Rows)); err != nil {
		if errors.Is(err, bridge.ErrPaneNotFound) {
			c.sendError(protocol.ErrCodeSessionNotFound, "pane unavailable")
		} else {
			c.sendError(protocol.ErrCodeInternal, "resize failed")
		}
	}
}

// scrollbackRange converges a scrollback request (protocol from_line/count,
// 0 = screen top, negative = history) to the pane's available range and
// returns the actual [start, end] in protocol coordinates. Convergence policy:
// clamp to the available range; a request entirely above the history (or
// entirely below the screen) is shifted to the nearest available edge so the
// client receives a useful page instead of a single degenerate line.
func (c *wsConn) scrollbackRange(ctx context.Context, br *bridge.Pane, pane discovery.Pane, fromLine, count int) (int, int, error) {
	// historySize = how many lines of history tmux retains above the screen.
	// It is measured by capturing from the oldest possible line to the screen
	// bottom and subtracting the screen height. (tmux's history-limit is
	// bounded and small; a dedicated bridge primitive could avoid the full
	// capture, but this consumes only the public bridge API.)
	oldestToBottom, err := br.Scrollback(ctx, math.MinInt32, -1)
	if err != nil {
		return 0, 0, err
	}
	historySize := countLines(oldestToBottom) - pane.Height
	if historySize < 0 {
		historySize = 0
	}

	// Available range in protocol coordinates.
	oldest := -historySize
	bottom := pane.Height - 1

	requestEnd := fromLine + count - 1
	switch {
	case requestEnd < oldest:
		// Entirely above the history: shift so the page starts at the oldest
		// available line, capped by what exists.
		start, end := oldest, oldest+count-1
		if end > bottom {
			end = bottom
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
