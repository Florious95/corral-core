package api

// ws_conn.go implements one authenticated WebSocket connection: a reader
// goroutine that parses control frames and routes them, a writer goroutine
// that drains the send queue (control frames and binary mirror frames in
// order), and a per-connection subscription table. Closing the connection
// implicitly unsubscribes every session it held (docs/protocol.md §3).

import (
	"context"
	"errors"
	"log/slog"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/agentmirror/agentmirror/internal/bridge"
	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

// perfOrigin is the process-local monotonic origin for subscribe-frame
// timestamps. Values are elapsed milliseconds (time.Since), not wall clock
// (UnixMilli can jump; the app-side analog is elapsedRealtime).
var perfOrigin = time.Now()

func perfNowMS() int64 {
	return time.Since(perfOrigin).Milliseconds()
}

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

	// writeCtx is the writer's own context, cancelled by teardown only after
	// the connection context. The writer must be able to flush frames that were
	// queued before the connection cancelled (e.g. auth rejection's auth_ack +
	// close): if teardown's cancel killed the in-flight write too, the client
	// would block forever waiting for the ack it will never get.
	writeCtx  context.Context
	writeStop context.CancelFunc

	// authed is set once the auth frame validates. Until then every frame
	// except auth is refused with error: unauthorized.
	authed atomic.Bool

	subsMu sync.Mutex
	subs   map[string]*subscription

	// level2Mu guards the second-level live-stream subscription state (061).
	// level2On records whether this connection is viewing the level-2 menu;
	// level2WS is the workspace cwd; level2Snap/level2PushedAt track the last
	// pushed snapshot so we send frames only on change and heartbeats when
	// the snapshot is still the same.
	level2Mu       sync.Mutex
	level2Epoch    uint64
	level2On       bool
	level2WS       string
	level2Snap     string
	level2PushedAt time.Time

	overlayMu   sync.Mutex
	overlayOn   bool
	overlaySock string
	overlayCols uint16
	overlayRows uint16

	// send is the writer queue. Control frames use a blocking send (a reply
	// must never be dropped); mirror deltas use a non-blocking send. Overflow
	// aborts this connection so reconnect can restore a complete snapshot.
	sendCh chan wsMsg

	// closeReason 连接关闭原因（可观测健康记录，leader msg_1f0c3455fac0）：
	// readLoop 读错误 / 客户端主动关 / 写超时 / 正常 EOF。teardown 日志带出，
	// 用于「连接为什么断」溯源（重连假说 / 慢链路健康）。
	closeReason   string
	closeReasonMu sync.Mutex
	sendMu        sync.RWMutex
	// Mirror loss reuses the catalog transport barrier, including writer checks.
	catalogAbortOnce   sync.Once
	catalogAborted     atomic.Bool
	catalogCloseReason atomic.Value
	// Test-only relay/writer boundaries; nil in production, set before startup.
	beforeRelay       func(*subscription)
	snapshotFn        func(context.Context, *bridge.Pane) ([]byte, error)
	sendBinaryFn      func([]byte)
	controlEnqueue    func()
	beforeWriterFrame func(wsMsg)
	writeAttempt      func(wsMsg)
	afterWriter       func()

	// connMetrics 这条连接自己的计数（P0 修复：teardown 行必须报本连接的数，
	// 不是进程累计——此前进程级累计被打在 per-conn 行上误导数轮）。
	// 进程级累计在 Server.sendQueue（字段前缀 total.*），两者分开、字段名可一眼分辨。
	connMetrics ConnMetrics
}

// subscription is one live mirror on this connection: the relay goroutine's
// cancel func, pipe detach func, and pane-size restore func, torn down together.
type subscription struct {
	ref    string
	cancel context.CancelFunc
	detach func()
	// loss is independent from raw bytes so overflow remains observable even
	// when the data channel already contains stale chunks or has closed.
	loss <-chan error
	// ready gates forwarding until the initial snapshot has been queued. The
	// relay nevertheless starts immediately, so it owns loss cancellation while
	// capture/encoding/queueing are still in flight.
	ready     chan struct{}
	readyOnce sync.Once
	// Only the relay consumes loss. Initial capture/encode failures hand
	// termination to it and wait for resource release before sending Error.
	initialFailed chan struct{}
	relayDone     chan struct{}
	// restoreSize returns the pane to the geometry captured before this
	// subscription reshaped it. Nil when the original geometry could not be read.
	// restoreOnce guards it against double teardown (explicit unsubscribe racing
	// a connection close): the pane-level release is idempotent anyway, but the
	// Once keeps the invariant "at most one release per subscription" explicit.
	// @contract
	// @pre non-nil closure captures the pane-level geometry tracker
	// @post invoked at most once; releases one subscription on the tracker
	// @inv the pane-level geometry release runs at most once per subscription lifetime
	// @err Resize failures are logged and not returned to the already-unsubscribing client
	restoreOnce sync.Once
	restoreSize func()
}

// serveConn owns the connection from accept to close.
func (s *Server) serveConn(conn *websocket.Conn) {
	ctx, cancel := context.WithCancel(context.Background())
	writeCtx, writeStop := context.WithCancel(context.Background())
	// WS 连接计数（重连线索：慢网下连接数暴增 = 超时断开→重连）。
	s.sendQueue.recordConnection()
	c := &wsConn{
		s:         s,
		id:        connSeq.Add(1),
		conn:      conn,
		ctx:       ctx,
		cancel:    cancel,
		writeCtx:  writeCtx,
		writeStop: writeStop,
		subs:      make(map[string]*subscription),
		sendCh:    make(chan wsMsg, 256),
	}
	s.trackersMu.Lock()
	init := s.connInit
	s.trackersMu.Unlock()
	if init != nil {
		init(c)
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
			// 记录读侧关闭原因（客户端关 / 网络错误 / 上下文取消），teardown 日志带出。
			c.setCloseReason("read_error: " + err.Error())
			return
		}
		// Stamp recv before parse/dispatch. Integer only — no string format
		// on this hot path. Logging happens solely on subscribe (below).
		recvMS := perfNowMS()
		if typ == wsBinary {
			c.sendError(protocol.ErrCodeBadFrame, "binary frames are server-to-client only")
			continue
		}
		if !c.handleFrame(data, recvMS) {
			return
		}
	}
}

// writeTimeout bounds one frame write. It exists because writes now run on
// writeCtx, which (unlike the old c.ctx) is not cancelled by teardown — so a
// peer that genuinely stops reading must not wedge the writer forever.
const writeTimeout = 30 * time.Second

// writeFrame writes one message, using writeCtx so teardown's cancel of c.ctx
// cannot abort a reply that was already queued (auth rejection's auth_ack+close
// is the canonical case), and a bounded timeout so a dead peer cannot wedge the
// writer. It returns the underlying write error, if any.
func (c *wsConn) writeFrame(m wsMsg) error {
	// A write already begun cannot be withdrawn; queued old epochs can.
	if m.level2Epoch != 0 && m.level2Epoch != c.currentLevel2Epoch() {
		return nil
	}
	deadline := time.Now().Add(writeTimeout)
	if m.catalog != nil {
		admissionDeadline, live := c.s.scans.writeDeadline(m.catalog)
		if !live {
			return context.Canceled
		}
		if !time.Now().Before(admissionDeadline) {
			c.abortCatalog("catalog_timeout")
			return context.DeadlineExceeded
		}
		if admissionDeadline.Before(deadline) {
			deadline = admissionDeadline
		}
	}
	if c.writeAttempt != nil {
		c.writeAttempt(m)
	}
	wctx, cancel := context.WithDeadline(c.writeCtx, deadline)
	defer cancel()
	err := c.conn.Write(wctx, m.typ, m.data)
	if m.catalog != nil {
		if err == nil {
			c.s.scans.written(m.catalog, time.Now())
		} else {
			admissionDeadline, live := c.s.scans.writeDeadline(m.catalog)
			if live && !time.Now().Before(admissionDeadline) {
				c.abortCatalog("catalog_timeout")
			}
		}
	}
	return err
}

// writeLoop drains the send queue and writes each message. On a close message
// it writes the close frame after any queued message and exits; on connection
// ctx cancellation it flushes the frames already queued (non-blocking) then
// closes abruptly, so a peer waiting on a reply — e.g. the auth_ack of a
// rejection — always gets that reply or a close, never a hang. Writing errors
// also close the underlying connection: without that the peer's Read would
// block forever on a dead writer (the read side alone cannot detect it).
func (c *wsConn) writeLoop() {
	defer func() {
		if c.afterWriter != nil {
			c.afterWriter()
		}
	}()
	defer c.writeStop()
	for {
		select {
		case m := <-c.sendCh:
			if c.beforeWriterFrame != nil {
				c.beforeWriterFrame(m)
			}
			if c.catalogAborted.Load() {
				_ = c.conn.CloseNow()
				return
			}
			if m.close {
				c.setCloseReason("client_close: " + m.reason)
				_ = c.conn.Close(m.code, m.reason)
				return
			}
			if err := c.writeFrame(m); err != nil {
				// 写超时/写错误 → 强制关闭：记录原因（重连假说：慢链路 30s 写超时是候选）。
				c.setCloseReason("write_error: " + err.Error())
				_ = c.conn.CloseNow()
				return
			}
		case <-c.ctx.Done():
			c.flushQueued()
			return
		}
	}
}

// flushQueued drains the send channel without blocking after the connection
// has been cancelled, delivering any reply that was queued before the cancel
// (auth rejection's auth_ack+close is the canonical case) and then closing the
// connection so the peer's Read returns instead of hanging.
func (c *wsConn) flushQueued() {
	for {
		select {
		case m := <-c.sendCh:
			if c.beforeWriterFrame != nil {
				c.beforeWriterFrame(m)
			}
			if c.catalogAborted.Load() {
				_ = c.conn.CloseNow()
				return
			}
			if m.close {
				_ = c.conn.Close(m.code, m.reason)
				return
			}
			if err := c.writeFrame(m); err != nil {
				_ = c.conn.CloseNow()
				return
			}
		default:
			_ = c.conn.CloseNow()
			return
		}
	}
}

// teardown cancels the connection context and detaches every subscription's
// pipe. It runs exactly once, from serveConn after the read loop exits. The
// writer wakes on c.ctx.Done(), flushes frames queued before cancellation
// (auth_ack + close on rejection) using its own live writeCtx, then cleans up
// writeCtx itself; teardown must NOT cancel writeCtx here or the flush would
// fail before the reply reached the wire.
func (c *wsConn) teardown() {
	c.cancel()
	c.s.scans.remove(c)
	closeReason := c.getCloseReason()
	if reason := c.catalogCloseReason.Load(); reason != nil {
		closeReason = reason.(string)
	}
	// 发送队列健康记录（常驻产品指标，非取证临时物）：会话结束时打一行，空闲零开销。
	// 内容只含计数，绝无 token/凭据（daemon 日志有明文 token 历史问题，纪律）。
	// 慢链路丢 delta → 客户端不一致 → 补发快照 → 整屏重建（D-36「发消息整屏刷」假说第 12 条）。
	// per-conn 与 process-level 分开报，字段前缀一眼可辨（P0：此前进程累计被打在 per-conn 行）。
	cm := c.connMetrics.snapshot()
	if m := c.s.sendQueue.Snapshot(); m.FramesSent > 0 || m.DeltasDropped > 0 || m.SnapshotsPushed > 0 || m.ConnectionsTotal > 0 || cm.FramesSent > 0 {
		c.s.log.Info("ws: sendq health",
			"conn", c.id,
			// 这条连接自己的数（per-connection，本行真正该报的东西）。
			"conn.deltas_dropped", cm.DeltasDropped,
			"conn.snapshots_pushed", cm.SnapshotsPushed,
			"conn.snapshots_from_resize", cm.SnapshotsFromResize,
			"conn.snapshots_from_subscribe", cm.SnapshotsFromSubscribe,
			"conn.frames_sent", cm.FramesSent,
			// 进程从启动到现在的累计（整体健康，非本连接）。
			"total.deltas_dropped", m.DeltasDropped,
			"total.snapshots_pushed", m.SnapshotsPushed,
			"total.snapshots_from_resize", m.SnapshotsFromResize,
			"total.subscribes", m.SubscribesTotal,
			"total.snapshots_from_subscribe", m.SnapshotsFromSubscribe,
			"total.connections", m.ConnectionsTotal,
			"total.queue_peak", m.QueuePeak,
			"total.frames_sent", m.FramesSent,
			"close_reason", closeReason,
		)
	}
	// The connection is no longer a live client: un-count it so the listing
	// loop parks once zero clients remain (idle-gate, taskbook
	// #fix-daemon-idle-cpu). Only an authenticated connection was counted.
	if c.authed.Load() {
		c.s.unmarkAuthed()
	}
	// If this connection was viewing the level-2 menu, un-count it so the
	// level2 loop parks once zero subscribers remain (061 idle gate).
	c.handleLevel2Unsubscribe(protocol.Level2Unsubscribe{})
	if c.overlayActive() {
		c.setOverlay(false, "", 0, 0)
		c.s.unmarkOverlay()
	}
	c.subsMu.Lock()
	subs := c.subs
	c.subs = make(map[string]*subscription)
	c.subsMu.Unlock()
	for _, sub := range subs {
		teardownSubscription(sub)
	}
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
// 快照帧计入 c.s.sendQueue（D-36 失败态观测：补发快照次数 = 整屏重建次数）。
func (c *wsConn) sendBinary(data []byte) {
	// 帧 kind 是 payload[3]（magic 2 + version 1 + kind 1）；kind=1 为 KindSnapshot。
	if len(data) > 3 && data[3] == byte(protocol.KindSnapshot) {
		c.s.sendQueue.recordSnapshot()
		c.connMetrics.recordSnapshot()
	}
	c.sendMsg(wsMsg{typ: wsBinary, data: data})
}

// sendMirror enqueues a binary mirror frame without blocking: a slow client
// whose queue is full must reconnect and replay a snapshot. Raw ANSI bytes
// lost here cannot be reconstructed by later deltas.
// 丢弃次数与队列峰值计入 c.s.sendQueue（常驻健康指标，「丢了多少数据」本就是健康度量）。
func (c *wsConn) sendMirror(data []byte) {
	c.sendMu.RLock()
	if c.catalogAborted.Load() || c.ctx.Err() != nil {
		c.sendMu.RUnlock()
		return
	}
	select {
	case c.sendCh <- wsMsg{typ: wsBinary, data: data}:
		c.s.sendQueue.recordQueued(len(c.sendCh))
		c.connMetrics.recordFramesSent()
		c.sendMu.RUnlock()
	default:
		c.sendMu.RUnlock()
		c.abortConnection("mirror_loss: ws_send_queue_overflow")
	}
}

// sendMsg enqueues one message, unblocking early when the connection closes.
func (c *wsConn) sendMsg(m wsMsg) {
	c.sendMu.RLock()
	defer c.sendMu.RUnlock()
	if c.catalogAborted.Load() || c.ctx.Err() != nil {
		return
	}
	if c.controlEnqueue != nil {
		c.controlEnqueue()
	}
	select {
	case c.sendCh <- m:
	case <-c.ctx.Done():
	}
}

// sendClose enqueues a close marker: the writer sends any queued message, then
// a WebSocket close frame and exits.
func (c *wsConn) sendClose(code websocket.StatusCode, reason string) {
	c.sendMsg(wsMsg{close: true, code: code, reason: reason})
}

// --- frame routing ----------------------------------------------------------

// handleFrame parses one control frame and routes it. It returns false when
// the connection should stop reading (auth rejection). Unknown frame types and
// unparsable frames get a protocol error; frames the server never receives
// from a client (listing, input_ack, …) are refused as unsupported_type.
func (c *wsConn) handleFrame(data []byte, recvMS int64) bool {
	typed, err := protocol.UnmarshalFrame(data)
	if err != nil {
		c.classifyCodecError(err)
		return true
	}
	// The auth frame is the one frame allowed before authentication. Every
	// other frame requires a validated auth first (docs/protocol.md §3, §9);
	// routing auth through the gate would reject the very frame that opens
	// the connection.
	if a, ok := typed.(protocol.Auth); ok {
		return c.handleAuth(a)
	}
	if !c.authed.Load() {
		c.sendError(protocol.ErrCodeUnauthorized, "not authenticated")
		return true
	}

	switch t := typed.(type) {
	case protocol.List:
		c.handleList(t)
	case protocol.Subscribe:
		startMS := perfNowMS()
		c.handleSubscribe(t)
		c.logPerfSubscribe(t.Ref, recvMS, startMS, perfNowMS())
	case protocol.Unsubscribe:
		c.handleUnsubscribe(t)
	case protocol.Input:
		c.handleInput(t)
	case protocol.Scrollback:
		c.handleScrollback(t)
	case protocol.Resize:
		c.handleResize(t)
	case protocol.ScrollWheel:
		c.handleScrollWheel(t)
	case protocol.AttachPreview:
		c.handleAttachPreview(t)
	case protocol.Level2Subscribe:
		c.handleLevel2Subscribe(t)
	case protocol.Level2Unsubscribe:
		c.handleLevel2Unsubscribe(t)
	case protocol.OverlaySubscribe:
		c.handleOverlaySubscribe(t)
	case protocol.OverlayUnsubscribe:
		c.handleOverlayUnsubscribe(t)
	default:
		// auth_ack, listing, list_delta, input_ack, error, pane_mode_changed,
		// level2_frame, level2_heartbeat, overlay_frame are server-to-client only.
		c.sendError(protocol.ErrCodeUnsupportedType, "frame type is not client-to-server")
	}
	return true
}

// logPerfSubscribe emits one structured line for a subscribe frame.
// Keys are the t.srv contract (verbatim): msg=perf_subscribe plus
// recv_ms / start_ms / done_ms / queue_ms, with queue_ms = start-recv.
// @contract
// @pre recvMS/startMS/doneMS are perfNowMS() samples taken at Read / dispatch / return
// @post one Info line when the logger accepts Info; logger-off path does not format a string
// @inv does not log pane contents, tokens, or socket paths
func (c *wsConn) logPerfSubscribe(ref string, recvMS, startMS, doneMS int64) {
	log := c.s.log
	if log == nil || !log.Enabled(c.ctx, slog.LevelInfo) {
		return
	}
	log.Info("perf_subscribe",
		"recv_ms", recvMS,
		"start_ms", startMS,
		"done_ms", doneMS,
		"queue_ms", startMS-recvMS,
		"ref", ref,
	)
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
	case errors.Is(err, protocol.ErrInvalidField):
		c.sendError(protocol.ErrCodeInvalidField, invalidFieldReason(err))
	case errors.Is(err, protocol.ErrBadPayload),
		errors.Is(err, protocol.ErrMissingVersion),
		errors.Is(err, protocol.ErrInvalidRef),
		errors.Is(err, protocol.ErrInvalidGeometry),
		errors.Is(err, protocol.ErrInvalidCount):
		c.sendError(protocol.ErrCodeBadFrame, "malformed frame")
	default:
		c.sendError(protocol.ErrCodeBadFrame, "malformed frame")
	}
}

// invalidFieldReason keeps the field name from Validate (070: 不能只说 malformed frame)。
func invalidFieldReason(err error) string {
	msg := err.Error()
	const prefix = "protocol: invalid or missing required field: "
	if strings.HasPrefix(msg, prefix) {
		return strings.TrimPrefix(msg, prefix)
	}
	return msg
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

// closeSubscriptions drains every live subscription on this connection —
// cancelling its relay and detaching its pipe — without closing the connection
// itself. It is the graceful-shutdown drain: Server.Close calls it on every
// tracked connection so no pipe-pane cat outlives the daemon (the graceful half
// of the crash-residue fix, root-cause chain step 2; SIGKILL relies on bridge
// subscribe's detach-first self-healing instead). Safe to run concurrently with
// relay/teardown: each sub's cancelOnce serializes detach.
func (c *wsConn) closeSubscriptions() {
	c.subsMu.Lock()
	subs := c.subs
	c.subs = make(map[string]*subscription)
	c.subsMu.Unlock()
	for _, sub := range subs {
		teardownSubscription(sub)
	}
}

// releaseRelayGate allows delta forwarding after the initial snapshot.
func (sub *subscription) releaseRelayGate() {
	if sub.ready != nil {
		sub.readyOnce.Do(func() { close(sub.ready) })
	}
}

// teardownSubscription releases one subscription's resources in a fixed order:
// cancel the relay context, detach the pipe, then release the pane geometry.
// It is the single teardown path shared by every exit route — explicit
// unsubscribe, connection close (teardown), graceful server close
// (closeSubscriptions), and relay stream end — so the pane restore runs on all
// of them alike (fix-host-pane-geometry-accounting 契约 2). restoreOnce makes
// it idempotent if two routes race on the same subscription.
func teardownSubscription(sub *subscription) {
	if sub.cancel != nil {
		sub.cancel()
	}
	// Unblock a relay that is waiting for the initial snapshot; its context
	// is already canceled so it cannot forward queued bytes on this path.
	sub.releaseRelayGate()
	if sub.detach != nil {
		sub.detach()
	}
	sub.restoreOnce.Do(func() {
		if sub.restoreSize != nil {
			sub.restoreSize()
		}
	})
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
		teardownSubscription(sub)
	}
	return sub != nil
}

// relay watches loss even while the initial snapshot is pending, then forwards
// deltas. Overflow aborts the connection without flushing old frames.
// On unexpected stream close (pane died or pipe displaced) it
// sendError so the client is not left frozen on the last frame, then tears
// down the subscription so a later input on the same ref gets not_subscribed
// instead of a silent no-op. Voluntary unsubscribe cancels ctx first and
// stays silent (docs/protocol.md §4.2). The context is the subscription's
// own; teardown cancels it when the connection closes.
func (c *wsConn) relay(ctx context.Context, sub *subscription, ch <-chan []byte) {
	loss := sub.loss
	ready := sub.ready
	defer func() {
		if sub.relayDone != nil {
			defer close(sub.relayDone)
		}
		// Single teardown path (fix-host-pane-geometry-accounting 契约 2): the
		// pane restore runs on relay stream end too, exactly like the explicit
		// unsubscribe / connection close / server close routes. restoreOnce keeps
		// this idempotent if subscribeCancel/teardown already released it.
		teardownSubscription(sub)
		// Remove only if this subscription is still the live one for the ref
		// (a re-subscribe may have replaced it concurrently).
		c.subsMu.Lock()
		if c.subs[sub.ref] == sub {
			delete(c.subs, sub.ref)
		}
		c.subsMu.Unlock()
	}()
	if c.beforeRelay != nil {
		c.beforeRelay(sub)
	}
	for {
		if ready != nil {
			select {
			case <-ready:
				if ctx.Err() != nil {
					return
				}
				ready = nil
			case cause, ok := <-loss:
				if ok && ctx.Err() == nil {
					c.abortConnection("mirror_loss: " + cause.Error())
					return
				}
				loss = nil
			case <-sub.initialFailed:
				// Detach synchronizes with fanout publication and prevents new
				// loss. Cancellation belongs to deferred teardown, after this
				// sole consumer has accounted for the committed cause.
				if sub.detach != nil {
					sub.detach()
				}
				select {
				case cause, ok := <-loss:
					if ok && ctx.Err() == nil {
						c.abortConnection("mirror_loss: " + cause.Error())
					}
				default:
				}
				return
			case <-ctx.Done():
				return
			}
			continue
		}
		// Prefer a loss that raced with a data notification. This check plus
		// the post-receive check below prevents already-buffered stale chunks
		// from being actively drained after the loss boundary.
		if loss != nil {
			select {
			case cause, ok := <-loss:
				if ok && ctx.Err() == nil {
					c.abortConnection("mirror_loss: " + cause.Error())
					return
				}
				loss = nil
			default:
			}
		}
		select {
		case cause, ok := <-loss:
			if ok && ctx.Err() == nil {
				c.abortConnection("mirror_loss: " + cause.Error())
				return
			}
			loss = nil
		case chunk, ok := <-ch:
			if !ok {
				// A loss closes the data channel too; consume its independent
				// signal before preserving the displaced-pipe ErrorFrame path.
				if loss != nil {
					select {
					case cause, lossOK := <-loss:
						if lossOK && ctx.Err() == nil {
							c.abortConnection("mirror_loss: " + cause.Error())
							return
						}
						loss = nil
					default:
					}
				}
				// Unexpected close (pane gone, or the pipe was stolen): the
				// client must see a control frame, not a frozen last frame.
				// Voluntary unsubscribe/teardown cancels ctx first, so that
				// path stays silent as protocol §4.2 requires.
				if ctx.Err() == nil && !c.catalogAborted.Load() {
					c.sendError(protocol.ErrCodeSessionNotFound, "mirror closed: pipe displaced or pane gone")
				}
				return
			}
			if loss != nil {
				select {
				case cause, lossOK := <-loss:
					if lossOK && ctx.Err() == nil {
						c.abortConnection("mirror_loss: " + cause.Error())
						return
					}
					loss = nil
				default:
				}
			}
			if c.catalogAborted.Load() {
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
// carries the geometry needed for scrollback convergence). Non-subscribe
// operations remain catalog-backed so stale or fabricated refs cannot perform
// input/scrollback/resize operations.
func (c *wsConn) resolvePane(ref string) (*bridge.Pane, discovery.Pane, bool) {
	e := c.s.catalogEntry(ref)
	if e == nil {
		return nil, discovery.Pane{}, false
	}
	return e.bridge, e.pane, true
}

// resolveSubscribePane keeps terminal entry independent from host discovery.
// A cold or in-flight catalog may not contain a valid ref yet, but the ref
// itself is enough to construct the read-only bridge used to establish the
// initial snapshot. Once that snapshot succeeds, normal subscription state
// gates all subsequent client operations.
func (c *wsConn) resolveSubscribePane(ref string) (*bridge.Pane, discovery.Pane, bool) {
	if e := c.s.catalogEntry(ref); e != nil {
		return e.bridge, e.pane, true
	}
	return directPaneFromRef(ref)
}

// log errors at debug level (a closing connection is normal, not an incident).
func (c *wsConn) logErr(verb string, err error) {
	if err == nil {
		return
	}
	c.s.log.Debug("ws: "+verb, "conn", c.id, "err", err)
}

func (c *wsConn) discardQueuedAndClose() {
	// A sender may already be blocked on a full queue. Cancellation above
	// makes it leave sendMsg; sendMu then closes the race where it could
	// otherwise enqueue after this drain.
	c.sendMu.Lock()
	defer c.sendMu.Unlock()
	for {
		select {
		case <-c.sendCh:
		default:
			_ = c.conn.CloseNow()
			return
		}
	}
}

func (c *wsConn) abortCatalog(reason string) {
	c.abortConnection(reason)
}

// Both catalog failure and mirror loss invalidate the same transport. Cancel
// blocked enqueuers before taking sendMu exclusively; ordinary close still flushes.
func (c *wsConn) abortConnection(reason string) {
	c.catalogAbortOnce.Do(func() {
		c.catalogCloseReason.Store(reason)
		c.catalogAborted.Store(true)
		if reason == "mirror_loss: ws_send_queue_overflow" {
			c.s.sendQueue.recordDrop()
			c.connMetrics.recordDrop()
		}
		c.cancel()
		c.writeStop()
		c.s.scans.remove(c)
		c.s.log.Warn("ws: terminating connection", "conn", c.id, "reason", reason)
		c.discardQueuedAndClose()
	})
}

func (c *wsConn) sendCatalog(frame protocol.Typed, epoch uint64, waiter *catalogWaiter) {
	body, err := protocol.MarshalFrame(frame)
	if err != nil {
		c.s.log.Error("catalog: marshal failed", "conn", c.id, "err", err)
		c.abortCatalog("catalog_backpressure")
		return
	}
	c.sendMu.RLock()
	if c.catalogAborted.Load() || c.ctx.Err() != nil {
		c.sendMu.RUnlock()
		return
	}
	select {
	case c.sendCh <- wsMsg{typ: wsText, data: body, level2Epoch: epoch, catalog: waiter}:
		c.sendMu.RUnlock()
	default:
		c.sendMu.RUnlock()
		c.abortCatalog("catalog_backpressure")
	}
}

// Reader and writer can finish concurrently; keep the health reason race-free.
func (c *wsConn) setCloseReason(reason string) {
	c.closeReasonMu.Lock()
	c.closeReason = reason
	c.closeReasonMu.Unlock()
}
func (c *wsConn) getCloseReason() string {
	c.closeReasonMu.Lock()
	defer c.closeReasonMu.Unlock()
	return c.closeReason
}
