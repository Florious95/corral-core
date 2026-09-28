package api

// server.go wires the WebSocket API server together: the HTTP handler set
// (WS at /ws, image upload at /upload), the shared session catalog, the
// periodic discovery loop that pushes listing/list_delta, and the per-connection
// frame router. The wire contract is docs/protocol.md v1; the machine-verifiable
// codec is internal/protocol.

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"sync"
	"sync/atomic"
	"time"

	"github.com/agentmirror/agentmirror/internal/bridge"
	"github.com/agentmirror/agentmirror/internal/overlay"
	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

// Server is the WS API service. It holds the shared session catalog and the
// listing sequence; each authenticated connection gets its own subscription
// table (never shared, so one client cannot see another's), and the discovery
// loop is a single heartbeat that fans list_delta out to every live client.
type Server struct {
	log *slog.Logger

	tokenValidator TokenValidator
	discoverer     Discoverer

	listInterval time.Duration
	uploadDir    string
	maxUpload    int64
	maxUploadDir int64
	maxInput     int
	uploadMu     sync.Mutex

	// sendQueue 发送队列健康指标（D-36 失败态观测 + 常驻健康指标，见 sendq_metrics.go）。
	sendQueue SendQueueMetrics

	// snapshot is the latest published two-level model (nil before the first
	// scan) and seq its monotonically increasing version, guarded by snapMu.
	// They are connection-independent: a reconnecting client lists and
	// continues from the same sequence the previous connection saw
	// (requirement 004 stateless replay).
	snapMu   sync.RWMutex
	snapshot *modelSnapshot
	seq      uint64

	catalog *sessionCatalog

	// paneGeoms holds the pane-level original-geometry singleton per ref
	// (fix-host-pane-geometry-accounting). The original geometry is recorded by
	// the first subscriber of a pane and restored when the last subscriber
	// leaves — never rebased by an intermediate subscriber, so a pane that
	// shrank/grew for the phone always returns to its pre-phone geometry
	// regardless of who subscribed in between. This is the shared, connection-
	// independent counterpart to the per-connection subscription table.
	paneGeomsMu sync.Mutex
	paneGeoms   map[string]*paneGeometry

	// trackers is the list_delta fan-out: every live client's send channel.
	trackersMu sync.Mutex
	trackers   map[*wsConn]struct{}

	// attachPreviews records the most recent AttachPreview (requirement 057)
	// per ref, keyed by ref, so a later Input.AttachmentPath can look up how
	// long ago its image was pasted and skip re-pasting + skip (most of) the
	// settle wait. See attach_preview.go for the record/consume methods.
	attachPreviewsMu sync.Mutex
	attachPreviews   map[string]attachPreviewEntry

	// loopCtx/loopStop own the periodic scan goroutine (started by NewServer,
	// stopped by Close). The goroutine is started exactly once by NewServer, so
	// no sync.Once guard is needed here (a previously present loopOnce field was
	// dead and has been removed).
	loopCtx  context.Context
	loopStop context.CancelFunc

	// authed counts live, authenticated connections (set by handleAuth, cleared
	// by teardown). The listing loop polls only while authed > 0; with zero
	// clients it parks and spawns no scan subprocesses (taskbook
	// #fix-daemon-idle-cpu: unconditional ticks burned 17.5% CPU per orphan).
	// wakeCh is a capacity-1 signal that the count just went 0→1, so the loop
	// runs a fresh full scan immediately instead of waiting for the next tick.
	authed atomic.Int64
	wakeCh chan struct{}

	// level2Subscribers counts connections currently viewing the second-level
	// menu (requirement 061). The level2Loop polls only while this is > 0; at
	// zero it parks and spawns no scan subprocesses (idle CPU ≈ 0). level2WakeCh
	// is a capacity-1 signal that the count just went 0→1, so the first
	// subscriber's stream is fresh immediately.
	level2Subscribers atomic.Int64
	level2WakeCh      chan struct{}

	// level2Interval is how often the level2 loop scans while subscribers exist.
	// level2Heartbeat is how long an unchanged snapshot may sit before a
	// keep-alive is pushed (requirement 061: change-only push + low-frequency
	// heartbeat).
	level2Interval  time.Duration
	level2Heartbeat time.Duration
	// level2Seq is the L2 stream's own monotonic counter. It must not share
	// listing seq: publishLevel2 used to call nextSeq(), which punched holes
	// in list_delta continuity and made the App re-list (074 / A-rf-noloop).
	level2Seq atomic.Uint64

	// overlaySubscribers / overlayWakeCh / overlayInterval gate the 064
	// capture stream: only while ≥1 overlay subscriber exists may we start a
	// scratch tmux client. overlayLastHash skips unchanged PTY dumps.
	overlaySubscribers atomic.Int64
	overlayWakeCh      chan struct{}
	overlayInterval    time.Duration
	overlay            overlay.Capturer
	overlayLastHash    map[string]string

	providerFinder ProviderFinder
}

// NewServer constructs the API server from Options. Zero values use the
// documented defaults; the token validator and state provider fall back to
// their safe defaults when unset. The discovery loop starts immediately.
// @contract
// @pre Options 任何字段可零值；全部按 Options 上文档的默认回退
// @post 返回已装配的 Server；discovery loop 已以 goroutine 启动；TokenValidator/Discoverer 缺省时装入安全默认
// @err none — 构造不返回 error；无效配置在运行时暴露
// @inv 生命周期由 Close 终结；loop 在零连接时挂起（idle-gate）
func NewServer(opts Options) *Server {
	log := opts.Log
	if log == nil {
		log = slog.New(slog.DiscardHandler)
	}

	s := &Server{
		log:            log,
		tokenValidator: opts.TokenValidator,
		discoverer:     opts.Discoverer,
		listInterval:   opts.ListInterval,
		uploadDir:      opts.UploadDir,
		maxUpload:      opts.MaxUploadBytes,
		maxUploadDir:   defaultMaxUploadDirBytes,
		maxInput:       opts.MaxInputBytes,
		catalog:        newSessionCatalog(),
		paneGeoms:      make(map[string]*paneGeometry),
		trackers:       make(map[*wsConn]struct{}),
		attachPreviews: make(map[string]attachPreviewEntry),
	}
	if s.tokenValidator == nil {
		s.tokenValidator = staticToken{token: opts.Token}
	}
	if s.discoverer == nil {
		// Copy the explicit scope (or the e2e-only env bridge) so a later
		// mutation cannot widen a running server's isolation boundary.
		socketDirs := resolvedDiscoverySocketDirs(opts.DiscoverySocketDirs)
		s.discoverer = tmuxDiscoverer{logger: log, socketDirs: socketDirs}
	}
	if s.listInterval <= 0 {
		s.listInterval = defaultListInterval
	}
	if s.maxUpload <= 0 {
		s.maxUpload = defaultMaxUploadBytes
	}
	if s.maxInput <= 0 {
		s.maxInput = defaultMaxInputBytes
	}

	// wakeCh is created before the loop so a 0→1 auth can never send on a nil
	// channel. Capacity 1: a wake that finds the slot occupied is dropped —
	// the loop is already about to run, so one scan covers both clients.
	s.wakeCh = make(chan struct{}, 1)
	// Same reasoning for the level2 live stream: the 0→1 subscriber wake must
	// never send on a nil channel, and a dropped wake is fine (a scan is coming).
	s.level2WakeCh = make(chan struct{}, 1)
	s.level2Interval = opts.Level2Interval
	if s.level2Interval <= 0 {
		s.level2Interval = defaultLevel2Interval
	}
	s.level2Heartbeat = opts.Level2Heartbeat
	if s.level2Heartbeat <= 0 {
		s.level2Heartbeat = defaultLevel2Heartbeat
	}
	s.overlayWakeCh = make(chan struct{}, 1)
	s.overlayLastHash = make(map[string]string)
	s.overlayInterval = opts.OverlayInterval
	if s.overlayInterval <= 0 {
		s.overlayInterval = defaultOverlayInterval
	}
	s.providerFinder = opts.ProviderFinder
	if s.providerFinder == nil {
		s.providerFinder = newProcFinder()
	}
	// 072 / 2026-08-19：抓屏 overlay 已归档，主流程不再构造 scratch 客户端、
	// 不再启动 overlayLoop。opts.OverlayCapturer 若注入也只保留字段，不被调用。
	s.overlay = opts.OverlayCapturer
	s.loopCtx, s.loopStop = context.WithCancel(context.Background())
	go s.listingLoop(s.loopCtx)
	go s.level2Loop(s.loopCtx)
	return s
}

// Close stops the discovery loop and drains every live subscription on every
// tracked connection so no pipe-pane cat is left attached to a pane when the
// daemon exits (the graceful-shutdown half of the crash-residue fix, root-cause
// chain step 2; a SIGKILL path still relies on bridge subscribe's detach-first
// self-healing — graceful close is never the only line of defense). It does not
// close live connections; the daemon calls it on shutdown after its listeners
// stop accepting.
// @contract
// @pre Server 由 NewServer 构造
// @post discovery loop 已停止；每个 tracked 连接的订阅被排空（relay 已取消、pipe 已 detach）；连接本身保持开放
// @err none
// @inv 幂等：重复调用安全；不 close 任何 WebSocket 连接
func (s *Server) Close() {
	s.loopStop()
	if s.overlay != nil {
		s.overlay.Stop()
	}
	// Snapshot the tracked connections under the lock, then drain each outside
	// it: closeSubscriptions takes a per-connection subsMu, so taking
	// trackersMu here too would invert lock order with registerTracker.
	s.trackersMu.Lock()
	conns := make([]*wsConn, 0, len(s.trackers))
	for c := range s.trackers {
		conns = append(conns, c)
	}
	s.trackersMu.Unlock()
	for _, c := range conns {
		c.closeSubscriptions()
	}
}

// Handler returns the full HTTP handler: /ws (WebSocket) and /upload
// (multipart image upload) on the same port (docs/protocol.md §8).
// @contract
// @pre Server 由 NewServer 构造
// @post 返回一个 http.Handler：/ws 升级为 WebSocket，/upload 接受 POST 图片上传；两路径共用同一端口
// @err none
// @inv 返回的 handler 持有 Server 引用；Server.Close 后不再接受新连接
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/ws", s.handleWS)
	mux.HandleFunc("/upload", s.handleUpload)
	return mux
}

// --- listing sequence & snapshot ------------------------------------------

// nextSeq advances the shared listing sequence (seq >= 1). It is called only
// when a new model version is published (first scan or a real change).
func (s *Server) nextSeq() uint64 {
	s.snapMu.Lock()
	defer s.snapMu.Unlock()
	s.seq++
	return s.seq
}

// nextLevel2Seq advances the level-2 stream sequence only. Listing / list_delta
// continuity is unaffected.
func (s *Server) nextLevel2Seq() uint64 {
	return s.level2Seq.Add(1)
}

// currentSeq returns the last published sequence, 0 before any scan.
func (s *Server) currentSeq() uint64 {
	s.snapMu.RLock()
	defer s.snapMu.RUnlock()
	return s.seq
}

// currentSnapshot returns the latest published model and its sequence. A nil
// snapshot with seq 0 means nothing has been scanned yet.
func (s *Server) currentSnapshot() (*modelSnapshot, uint64) {
	s.snapMu.RLock()
	defer s.snapMu.RUnlock()
	return s.snapshot, s.seq
}

// setSnapshot publishes a new model version under lock.
func (s *Server) setSnapshot(snap *modelSnapshot) {
	s.snapMu.Lock()
	s.snapshot = snap
	s.snapMu.Unlock()
}

// rebuildCatalog scans tmux once, swaps the catalog to the fresh snapshot,
// and publishes the new model. On discovery failure the previous model is
// retained unchanged and an error is returned for the caller to log.
func (s *Server) rebuildCatalog(ctx context.Context) error {
	model, err := s.discoverer.Discover(ctx)
	if err != nil {
		return fmt.Errorf("api: discover: %w", err)
	}
	s.catalog.rebuild(filterModel(s, model))
	snap := buildSnapshot(s.catalog)
	s.setSnapshot(snap)
	return nil
}

// listingLoop is the idle-gated periodic scan heartbeat (taskbook
// #fix-daemon-idle-cpu). Every ListInterval while at least one connection is
// authenticated it scans tmux and pushes a list_delta to every live client;
// with zero authenticated connections it parks and spawns no scan subprocesses
// (the fix for the 17.5%-per-orphan idle burn). The 0→1 transition wakes the
// loop immediately so the first client's listing is fresh, not up to one
// interval old. The first scan establishes the baseline (seq 1); later scans
// diff and emit only real changes. A discovery error is logged and skipped —
// the last good snapshot stays current and the loop keeps going (a dead tmux
// must never take the API down).
func (s *Server) listingLoop(ctx context.Context) {
	s.log.Debug("listing loop started", "interval", s.listInterval)
	for {
		if s.countAuthed() == 0 {
			// Zero clients: park and spawn no scan subprocesses (the idle-burn
			// red line). A 0→1 wake breaks the park and scans immediately below.
			select {
			case <-ctx.Done():
				s.log.Debug("listing loop stopped")
				return
			case <-s.wakeCh:
			}
		} else {
			// Clients connected: scan on the regular cadence, or sooner when a
			// wake arrives (a re-auth while already active gets a fresh scan).
			select {
			case <-ctx.Done():
				s.log.Debug("listing loop stopped")
				return
			case <-s.wakeCh:
			case <-time.After(s.listInterval):
			}
		}
		s.publishListing(ctx)
	}
}

// --- idle-gate accounting (taskbook #fix-daemon-idle-cpu) -------------------

// markAuthed is called by handleAuth when a connection authenticates: it bumps
// the live-client count and wakes the loop (0→1) so the first client's listing
// is fresh immediately instead of after one interval.
func (s *Server) markAuthed() {
	if s.authed.Add(1) == 1 {
		select {
		case s.wakeCh <- struct{}{}:
		default:
		}
	}
}

// unmarkAuthed is called by teardown when a connection closes: it drops the
// live-client count. When it reaches zero the loop parks after the in-flight
// scan, so an idle daemon spawns no further scan subprocesses.
func (s *Server) unmarkAuthed() {
	if s.authed.Add(-1) <= 0 {
		s.authed.Store(0) // never negative: teardown is idempotent-guarded by wsConn
	}
}

// countAuthed returns the number of live, authenticated connections.
func (s *Server) countAuthed() int64 {
	return s.authed.Load()
}

// publishListing performs one scan-and-diff cycle. The first scan (no previous
// model or sequence) just establishes the baseline at seq 1; each later scan
// with changes bumps the seq and fans out one list_delta.
func (s *Server) publishListing(ctx context.Context) {
	_ = s.refreshListing(ctx)
}

// refreshListing runs one real discovery pass and updates the shared snapshot.
// On failure the previous snapshot is left untouched (069: never wipe the
// list because a refresh failed). Callers that must answer the client (list)
// send that retained snapshot.
func (s *Server) refreshListing(ctx context.Context) error {
	prev, prevSeq := s.currentSnapshot()
	if err := s.rebuildCatalog(ctx); err != nil {
		s.log.Warn("listing: discovery failed",
			"err", err,
			"had_cache", prev != nil,
			"prev_seq", prevSeq,
			"prev_sessions", snapshotSessionCount(prev),
		)
		return err
	}
	cur, _ := s.currentSnapshot()

	if prev == nil && prevSeq == 0 {
		s.snapMu.Lock()
		if s.seq == 0 {
			s.seq = 1
		}
		s.snapMu.Unlock()
		s.log.Debug("listing: first snapshot", "seq", s.currentSeq())
		return nil
	}
	if prev == nil {
		prev = &modelSnapshot{}
	}

	d := cur.diff(prev)
	if len(d.AddedSessions)+len(d.RemovedRefs)+len(d.ChangedSessions)+len(d.ChangedWorkspaces) == 0 {
		s.log.Debug("listing: no changes")
		return nil
	}
	d.Seq = s.nextSeq()
	s.fanout(d)
	return nil
}

// ensureInitialScan forces the first scan synchronously so a client that lists
// before the loop's first tick still gets a seq >= 1. It is a no-op once a
// snapshot exists.
func (s *Server) ensureInitialScan(ctx context.Context) {
	s.snapMu.RLock()
	done := s.snapshot != nil
	s.snapMu.RUnlock()
	if done {
		return
	}
	if err := s.rebuildCatalog(ctx); err != nil {
		s.log.Warn("listing: initial scan failed", "err", err)
	}
	s.snapMu.Lock()
	if s.seq == 0 {
		s.seq = 1
	}
	s.snapMu.Unlock()
}

// --- tracker fan-out --------------------------------------------------------

// registerTracker adds a live connection to the list_delta fan-out.
func (s *Server) registerTracker(c *wsConn) {
	s.trackersMu.Lock()
	s.trackers[c] = struct{}{}
	s.trackersMu.Unlock()
}

// unregisterTracker removes a live connection from the fan-out.
func (s *Server) unregisterTracker(c *wsConn) {
	s.trackersMu.Lock()
	delete(s.trackers, c)
	s.trackersMu.Unlock()
}

// fanout sends one list_delta to every live client. A slow client whose send
// channel is full drops the delta; the client re-lists on seq discontinuity
// (docs/protocol.md §4.2), so a slow client heals itself without stalling the
// heartbeat.
func (s *Server) fanout(d *protocol.ListDelta) {
	body, err := protocol.MarshalFrame(d)
	if err != nil {
		s.log.Error("listing: marshal delta", "err", err)
		return
	}
	s.trackersMu.Lock()
	defer s.trackersMu.Unlock()
	for c := range s.trackers {
		select {
		case c.sendCh <- wsMsg{typ: wsText, data: body}:
		default:
			s.log.Debug("listing: dropping delta for slow connection")
		}
	}
}

// --- server-level seams -----------------------------------------------------

// resolveBridge looks up the bridge bound to a client-facing ref. ok=false
// means the ref is unknown (the caller replies session_not_found).
func (s *Server) resolveBridge(ref string) (*bridge.Pane, bool) {
	e := s.catalog.entry(ref)
	if e == nil {
		return nil, false
	}
	return e.bridge, true
}

// handleWS upgrades an HTTP request to the WebSocket API and serves the
// connection until it closes.
func (s *Server) handleWS(w http.ResponseWriter, r *http.Request) {
	// InsecureSkipVerify: this is a LAN/tailnet-internal daemon, not exposed to
	// the public internet — skip the browser Origin check so web clients can
	// connect from any origin (see docs/protocol.md).
	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		InsecureSkipVerify: true,
	})
	if err != nil {
		s.log.Warn("ws: accept failed", "err", err)
		return
	}
	s.serveConn(conn)
}

// handleUpload serves POST /upload (docs/protocol.md §8). See upload.go.
func (s *Server) handleUpload(w http.ResponseWriter, r *http.Request) {
	s.serveUpload(w, r)
}
