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
	"time"

	"github.com/agentmirror/agentmirror/internal/bridge"
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
	stateProvider  StateProvider
	discoverer     Discoverer

	listInterval time.Duration
	uploadDir    string
	maxUpload    int64
	maxInput     int

	// snapshot is the latest published two-level model (nil before the first
	// scan) and seq its monotonically increasing version, guarded by snapMu.
	// They are connection-independent: a reconnecting client lists and
	// continues from the same sequence the previous connection saw
	// (requirement 004 stateless replay).
	snapMu   sync.RWMutex
	snapshot *modelSnapshot
	seq      uint64

	catalog *sessionCatalog

	// trackers is the list_delta fan-out: every live client's send channel.
	trackersMu sync.Mutex
	trackers   map[*wsConn]struct{}

	// loopCtx/loopStop own the periodic scan goroutine (started by NewServer,
	// stopped by Close).
	loopCtx  context.Context
	loopStop context.CancelFunc
	loopOnce sync.Once
}

// NewServer constructs the API server from Options. Zero values use the
// documented defaults; the token validator and state provider fall back to
// their safe defaults when unset. The discovery loop starts immediately.
func NewServer(opts Options) *Server {
	log := opts.Log
	if log == nil {
		log = slog.New(slog.DiscardHandler)
	}

	s := &Server{
		log:            log,
		tokenValidator: opts.TokenValidator,
		stateProvider:  opts.StateProvider,
		discoverer:     opts.Discoverer,
		listInterval:   opts.ListInterval,
		uploadDir:      opts.UploadDir,
		maxUpload:      opts.MaxUploadBytes,
		maxInput:       opts.MaxInputBytes,
		catalog:        newSessionCatalog(),
		trackers:       make(map[*wsConn]struct{}),
	}
	if s.tokenValidator == nil {
		s.tokenValidator = staticToken{token: opts.Token}
	}
	if s.stateProvider == nil {
		s.stateProvider = unknownState{}
	}
	if s.discoverer == nil {
		s.discoverer = tmuxDiscoverer{logger: log}
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

	s.loopCtx, s.loopStop = context.WithCancel(context.Background())
	go s.listingLoop(s.loopCtx)
	return s
}

// Close stops the discovery loop. It does not close live connections; the
// daemon calls it on shutdown after its listeners stop accepting.
func (s *Server) Close() {
	s.loopStop()
}

// Handler returns the full HTTP handler: /ws (WebSocket) and /upload
// (multipart image upload) on the same port (docs/protocol.md §8).
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
	s.catalog.rebuild(model)
	snap := buildSnapshot(s.catalog, s.stateProvider, ctx)
	s.setSnapshot(snap)
	return nil
}

// listingLoop is the periodic scan heartbeat: every interval it scans tmux and
// pushes a list_delta to every live client. The first scan establishes the
// baseline (seq 1); later scans diff and emit only real changes. A discovery
// error is logged and skipped — the last good snapshot stays current and the
// loop keeps going (a dead tmux must never take the API down).
func (s *Server) listingLoop(ctx context.Context) {
	ticker := time.NewTicker(s.listInterval)
	defer ticker.Stop()
	s.log.Debug("listing loop started", "interval", s.listInterval)
	for {
		select {
		case <-ctx.Done():
			s.log.Debug("listing loop stopped")
			return
		case <-ticker.C:
			s.publishListing(ctx)
		}
	}
}

// publishListing performs one scan-and-diff cycle. The first scan (no previous
// model) just establishes the baseline at seq 1; each later scan with changes
// bumps the seq and fans out one list_delta.
func (s *Server) publishListing(ctx context.Context) {
	prev, _ := s.currentSnapshot()
	if err := s.rebuildCatalog(ctx); err != nil {
		s.log.Warn("listing: discovery failed", "err", err)
		return
	}
	cur, _ := s.currentSnapshot()

	if prev == nil {
		// First snapshot establishes the baseline; guarantee it carries a
		// sequence >= 1 (a client that lists before this tick reads seq 1).
		s.snapMu.Lock()
		if s.seq == 0 {
			s.seq = 1
		}
		s.snapMu.Unlock()
		s.log.Debug("listing: first snapshot", "seq", s.currentSeq())
		return
	}

	d := cur.diff(prev)
	if len(d.AddedSessions)+len(d.RemovedRefs)+len(d.ChangedSessions)+len(d.ChangedWorkspaces) == 0 {
		s.log.Debug("listing: no changes")
		return
	}
	d.Seq = s.nextSeq()
	s.fanout(d)
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
	conn, err := websocket.Accept(w, r, nil)
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
