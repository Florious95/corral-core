package api

// ws_conn.go implements one authenticated WebSocket connection: a reader
// goroutine that parses control frames and routes them, a writer goroutine
// that drains the send queue (control frames and binary mirror frames in
// order), and a per-connection subscription table. Closing the connection
// implicitly unsubscribes every session it held (docs/protocol.md §3).

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"

	"github.com/coder/websocket"
	"github.com/remote-agent/agentmirror/internal/bridge"
	"github.com/remote-agent/agentmirror/internal/discovery"
	"github.com/remote-agent/agentmirror/internal/protocol"
)

// connSeq assigns each connection a monotonically increasing id for logging.
var connSeq atomic.Uint64

// wsConn is one live connection. It owns its subscription table (never shared
// between connections — a client can only address its own mirrors) and its
// send queue; the connection closes = all subscriptions are torn down
// (requirement 004 stateless replay, docs/protocol.md §3).
type wsConn struct {
	s    *Server
	id   uint64
	conn *websocket.Conn

	// ctx is the per-connection context. Cancelling it (on close) unblocks
	// the writer and every subscription's relay goroutine.
	ctx    context.Context
	cancel context.CancelFunc

	// authed is set once the auth frame validates. Until then every frame
	// except auth is refused with error: unauthorized.
	authed atomic.Bool

	subsMu sync.Mutex
	subs   map[string]*subscription

	// send is the writer queue. Control frames use a blocking send (a reply
	// must never be dropped); mirror deltas use a non-blocking send that drops
	// on overflow (the next snapshot reconciles, requirement 004).
	sendCh chan wsMsg
}

// subscription is one live mirror on this connection: the relay goroutine's
// cancel func and the pipe detach func, torn down together.
type subscription struct {
	ref    string
	cancel context.CancelFunc
	detach func()
}

// serveConn owns the connection from accept to close.
func (s *Server) serveConn(conn *websocket.Conn) {
	ctx, cancel := context.WithCancel(context.Background())
	c := &wsConn{
		s:      s,
		id:     connSeq.Add(1),
		conn:   conn,
		ctx:    ctx,
		cancel: cancel,
		subs:   make(map[string]*subscription),
		sendCh: make(chan wsMsg, 256),
	}
	s.registerTracker(c)
	go c.writeLoop()
	c.readLoop()
	c.teardown()
}

// readLoop reads control frames until the connection closes, routing each to
// the frame handler. A binary frame from the client is a protocol error (the
// binary channel is server-to-client only, docs/protocol.md §1).
func (c *wsConn) readLoop() {
	for {
		typ, data, err := c.conn.Read(c.ctx)
		if err != nil {
			return
		}
		if typ == wsBinary {
			c.sendError(protocol.ErrCodeBadFrame, "binary frames are server-to-client only")
			continue
		}
		if !c.handleFrame(data) {
			return
		}
	}
}

// writeLoop drains the send queue and writes each message. On a close message
// it writes the close frame after any queued message and exits; on ctx
// cancellation it closes abruptly. Writing errors end the loop (the read side
// will notice the close and tear down).
func (c *wsConn) writeLoop() {
	for {
		select {
		case m := <-c.sendCh:
			if m.close {
				_ = c.conn.Close(m.code, m.reason)
				return
			}
			if err := c.conn.Write(c.ctx, m.typ, m.data); err != nil {
				return
			}
		case <-c.ctx.Done():
			_ = c.conn.CloseNow()
			return
		}
	}
}

// teardown cancels the connection context and detaches every subscription's
// pipe. It runs exactly once, from serveConn after the read loop exits.
func (c *wsConn) teardown() {
	c.cancel()
	c.subsMu.Lock()
	for _, sub := range c.subs {
		sub.cancel()
		sub.detach()
	}
	c.subs = make(map[string]*subscription)
	c.subsMu.Unlock()
	c.s.unregisterTracker(c)
}

// --- send helpers ----------------------------------------------------------

// send enqueues one control frame. A reply must reach the client, so the send
// blocks until there is room (it only unblocks early when the connection is
// closed, via ctx cancellation).
func (c *wsConn) send(typed protocol.Typed) {
	body, err := protocol.MarshalFrame(typed)
	if err != nil {
		c.s.log.Error("ws: marshal frame", "conn", c.id, "err", err)
		return
	}
	c.sendMsg(wsMsg{typ: wsText, data: body})
}

// sendError enqueues an ErrorFrame (docs/protocol.md §7.1).
func (c *wsConn) sendError(code protocol.ErrorCode, reason string) {
	c.send(&protocol.ErrorFrame{Code: code, Reason: reason})
}

// sendBinary enqueues one binary stream frame (snapshot/delta/scrollback).
func (c *wsConn) sendBinary(data []byte) {
	c.sendMsg(wsMsg{typ: wsBinary, data: data})
}

// sendMirror enqueues a binary mirror frame without blocking: a slow client
// whose queue is full drops the delta, and the next snapshot reconciles
// (requirement 004 — the tmux pane is the source of truth, not this queue).
func (c *wsConn) sendMirror(data []byte) {
	select {
	case c.sendCh <- wsMsg{typ: wsBinary, data: data}:
	default:
		c.s.log.Debug("ws: dropping mirror delta for slow connection", "conn", c.id)
	}
}

// sendMsg enqueues one message, unblocking early when the connection closes.
func (c *wsConn) sendMsg(m wsMsg) {
	select {
	case c.sendCh <- m:
	case <-c.ctx.Done():
	}
}

// sendClose enqueues a close marker: the writer sends any queued message, then
// a WebSocket close frame and exits.
func (c *wsConn) sendClose(code websocket.StatusCode, reason string) {
	select {
	case c.sendCh <- wsMsg{close: true, code: code, reason: reason}:
	case <-c.ctx.Done():
	}
}

// --- frame routing ----------------------------------------------------------

// handleFrame parses one control frame and routes it. It returns false when
// the connection should stop reading (auth rejection). Unknown frame types and
// unparsable frames get a protocol error; frames the server never receives
// from a client (listing, input_ack, …) are refused as unsupported_type.
func (c *wsConn) handleFrame(data []byte) bool {
	typed, err := protocol.UnmarshalFrame(data)
	if err != nil {
		c.classifyCodecError(err)
		return true
	}
	// Auth is the one frame allowed before authentication. Everything else
	// requires a validated auth first (docs/protocol.md §3, §9).
	if !c.authed.Load() {
		c.sendError(protocol.ErrCodeUnauthorized, "not authenticated")
		return true
	}

	switch t := typed.(type) {
	case protocol.Auth:
		return c.handleAuth(t)
	case protocol.List:
		c.handleList(t)
	case protocol.Subscribe:
		c.handleSubscribe(t)
	case protocol.Unsubscribe:
		c.handleUnsubscribe(t)
	case protocol.Input:
		c.handleInput(t)
	case protocol.Scrollback:
		c.handleScrollback(t)
	case protocol.Resize:
		c.handleResize(t)
	default:
		// auth_ack, listing, list_delta, input_ack, error are server-to-client.
		c.sendError(protocol.ErrCodeUnsupportedType, "frame type is not client-to-server")
	}
	return true
}

// classifyCodecError maps a codec error to the protocol error frame and, for
// version mismatch, closes the connection (docs/protocol.md §2).
func (c *wsConn) classifyCodecError(err error) {
	switch {
	case errors.Is(err, protocol.ErrUnknownType):
		c.sendError(protocol.ErrCodeUnsupportedType, "unknown frame type")
	case errors.Is(err, protocol.ErrUnsupportedVersion):
		c.sendError(protocol.ErrCodeUnsupportedVersion, "unsupported protocol version")
		c.sendClose(websocket.StatusProtocolError, "unsupported version")
	case errors.Is(err, protocol.ErrBadPayload),
		errors.Is(err, protocol.ErrInvalidField),
		errors.Is(err, protocol.ErrMissingVersion),
		errors.Is(err, protocol.ErrInvalidState),
		errors.Is(err, protocol.ErrInvalidRef),
		errors.Is(err, protocol.ErrInvalidGeometry),
		errors.Is(err, protocol.ErrInvalidCount):
		c.sendError(protocol.ErrCodeBadFrame, "malformed frame")
	default:
		c.sendError(protocol.ErrCodeBadFrame, "malformed frame")
	}
}

// --- subscription helpers ---------------------------------------------------

// subscribeAdd records a live subscription for ref.
func (c *wsConn) subscribeAdd(sub *subscription) {
	c.subsMu.Lock()
	c.subs[sub.ref] = sub
	c.subsMu.Unlock()
}

// subscribed reports whether ref has a live subscription on this connection.
func (c *wsConn) subscribed(ref string) bool {
	c.subsMu.Lock()
	defer c.subsMu.Unlock()
	return c.subs[ref] != nil
}

// subscribeCancel tears down the subscription for ref, if any. It is
// idempotent: cancelling a session that is not subscribed is not an error
// (docs/protocol.md §4.2). Returns true if a subscription existed.
func (c *wsConn) subscribeCancel(ref string) bool {
	c.subsMu.Lock()
	sub := c.subs[ref]
	if sub != nil {
		delete(c.subs, ref)
	}
	c.subsMu.Unlock()
	if sub != nil {
		sub.cancel()
		sub.detach()
	}
	return sub != nil
}

// relay drains a bridge delta stream and forwards each chunk as a binary
// delta frame. On stream close (pane died or pipe detached) it tears down the
// subscription so a later input on the same ref gets not_subscribed instead
// of a silent no-op. The context is the subscription's own; teardown cancels
// it when the connection closes.
func (c *wsConn) relay(ctx context.Context, sub *subscription, ch <-chan []byte) {
	defer func() {
		sub.detach()
		// Remove only if this subscription is still the live one for the ref
		// (a re-subscribe may have replaced it concurrently).
		c.subsMu.Lock()
		if c.subs[sub.ref] == sub {
			delete(c.subs, sub.ref)
		}
		c.subsMu.Unlock()
	}()
	for {
		select {
		case chunk, ok := <-ch:
			if !ok {
				return
			}
			frame, err := protocol.EncodeBinary(protocol.BinaryPayload{
				Kind: protocol.KindDelta,
				Ref:  sub.ref,
				Data: chunk,
			})
			if err != nil {
				c.s.log.Debug("ws: encode delta", "conn", c.id, "err", err)
				continue
			}
			c.sendMirror(frame)
		case <-ctx.Done():
			return
		}
	}
}

// resolveBridge looks up the bridge for a ref via the shared catalog.
func (c *wsConn) resolveBridge(ref string) (*bridge.Pane, bool) {
	return c.s.resolveBridge(ref)
}

// resolvePane resolves the bridge and the discovery pane for a ref (the pane
// carries the geometry needed for scrollback convergence).
func (c *wsConn) resolvePane(ref string) (*bridge.Pane, discovery.Pane, bool) {
	e := c.s.catalog.entry(ref)
	if e == nil {
		return nil, discovery.Pane{}, false
	}
	return e.bridge, e.pane, true
}

// log errors at debug level (a closing connection is normal, not an incident).
func (c *wsConn) logErr(verb string, err error) {
	if err == nil {
		return
	}
	c.s.log.Debug("ws: "+verb, "conn", c.id, "err", err)
}
