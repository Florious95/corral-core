package api

// state_wiring.go assembles the agent-state pipeline behind the StateProvider
// seam (task fix-state-wiring, defect D-1). It is the production wiring that
// was missing: cmd/agentmirrord now hands the api server a provider that
// resolves each pane's agent state from the agentstate package, so listing
// carries a real state (not the always-unknown default) and the 012 aggregate
// (listing.go) has ranked inputs — the data source for blocked/done
// notifications (requirement 003 standard four).
//
// ## Pipeline per pane
//
//  1. Identify — agentstate.Identify: direct-command fast path, else a bounded
//     process-tree descent from #{pane_pid} (≤500ms, single ps). Wrapper panes
//     (pane_current_command=bash) resolve to claude/codex this way.
//  2. Sample — a bounded `tmux -S <socket> capture-pane -p -t <id> -e` tail for
//     RecentOutput (the rule tables match on-screen markers, state-parser §5).
//  3. Track — agentstate.Track(prev, sample): the done ≈ working→idle edge,
//     folded against the last published state. Per-ref prev memory is held
//     here, keyed by the same stable session ref the catalog uses.
//
// ## Hot-path isolation (requirement 008) — sampling/cache strategy
//
// `State()` is a pure cache read: it never runs Identify or capture-pane
// synchronously, so the listing loop (buildSnapshot → toSession → State) never
// blocks on the ≤500ms process-tree IO or the tmux capture. Sampling happens
// in a background goroutine per pane, gated by a TTL and a concurrency cap:
//
//   - A pane is re-sampled at most once per ttl (default 1s). Between
//     refreshes State() returns the last-known value — a live pane never
//     flashes unknown; a brand-new pane reports unknown on its first listing,
//     then the sampled state once the refresh lands.
//   - At most maxConcurrent refreshes run at once, so a large fleet never fans
//     out one ps + one tmux capture per pane simultaneously (resource-bounded,
//     engineering red line 3).
//   - Every failure — sample error, cancelled/budget-exceeded Identify —
//     degrades to StateUnknown and is stored as such. It is never an error and
//     never touches the mirror/input paths (which do not consult StateProvider
//     at all).
//   - When the listing loop parks (zero clients, idle-gate in server.go), no
//     State() calls arrive, so no sampling goroutines spawn and the daemon
//     runs no state subprocesses while idle (silent-economy red line 1).

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"os/exec"
	"strings"
	"sync"
	"time"

	"github.com/agentmirror/agentmirror/internal/agentstate"
	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

const (
	// defaultStateRefreshTTL bounds how often one pane is re-sampled. It is ≤
	// the default listing interval (2s), so each scan refreshes a pane at most
	// once; between refreshes State() serves the last-known value. Tests
	// shrink it to drive refresh quickly.
	defaultStateRefreshTTL = time.Second

	// defaultStateMaxConcurrent caps simultaneous background samplers so a
	// hundreds-of-panes fleet never spawns one ps + one tmux capture per pane
	// at once (resource-bounded, engineering red line 3).
	defaultStateMaxConcurrent = 4

	// defaultStatePruneAge drops a pane's cache entry once no listing has
	// requested it for this long, reclaiming vanished panes so the cache stays
	// proportional to the live fleet (resource-bounded).
	defaultStatePruneAge = 60 * time.Second

	// defaultStateSamplingBudget bounds one background refresh (Identify's own
	// internal 500ms ps budget plus the capture-pane round-trip). Exceeding it
	// degrades the pane to unknown (requirement 008).
	defaultStateSamplingBudget = 3 * time.Second
)

// wiredStateProvider is the production StateProvider. It is a cache fronting
// the agentstate pipeline; see the file header for the isolation rationale.
type wiredStateProvider struct {
	log *slog.Logger

	// reg is the per-agent rule registry (claude/codex adapters).
	reg agentstate.Registry
	// identify resolves a pane to its agent kind (bounded IO inside).
	identify func(ctx context.Context, in agentstate.IdentifyInput) agentstate.AgentKind
	// sample captures a pane's recent output (bounded IO inside). It returns
	// RecentOutput, LastOutputAge, error. Both seams default to the production
	// implementations and are injectable for tests.
	sample func(ctx context.Context, p discovery.Pane) ([]byte, time.Duration, error)

	ttl      time.Duration
	maxConc  chan struct{}
	pruneAge time.Duration

	// ctx/cancel own the background refresh goroutines; Close cancels them.
	ctx    context.Context
	cancel context.CancelFunc

	mu    sync.Mutex
	cache map[string]*stateCacheEntry
}

// stateCacheEntry is one pane's cached state plus the memory the pipeline
// needs across refreshes (the last pane identity for sampling and the last
// published state for Track's done-edge).
type stateCacheEntry struct {
	// pane is the freshest discovered Pane for this ref, re-sampled with.
	pane discovery.Pane
	// state is the last published state served by State().
	state protocol.AgentState
	// prev is the last state Track folded against (working→idle ⇒ done).
	prev protocol.AgentState
	// lastRequested updates on every State() call; the prune pass uses it to
	// reclaim panes that stopped being listed.
	lastRequested time.Time
	// lastRefresh is when the last background sample completed; State() skips
	// a refresh until ttl has elapsed.
	lastRefresh time.Time
	// inFlight prevents stacking refreshes for the same pane.
	inFlight bool
}

// NewStateProvider returns the production StateProvider wiring agentstate into
// the API: process-tree identify + pane-output sampling + prev-tracked state,
// served from a TTL cache so the listing hot path never blocks on state IO
// (requirement 008). log may be nil (discarded).
func NewStateProvider(log *slog.Logger) *wiredStateProvider {
	if log == nil {
		log = slog.New(slog.DiscardHandler)
	}
	p := &wiredStateProvider{
		log:      log,
		reg:      agentstate.DefaultRegistry(),
		identify: agentstate.Identify,
		sample:   capturePaneOutput,
		ttl:      defaultStateRefreshTTL,
		maxConc:  make(chan struct{}, defaultStateMaxConcurrent),
		pruneAge: defaultStatePruneAge,
		cache:    make(map[string]*stateCacheEntry),
	}
	p.ctx, p.cancel = context.WithCancel(context.Background())
	return p
}

// Close cancels the background refresh goroutines. The daemon calls it during
// shutdown after the api server closes.
func (p *wiredStateProvider) Close() { p.cancel() }

// State implements StateProvider. It is a pure cache read plus a refresh
// schedule: never performs I/O synchronously (requirement 008), so the listing
// loop cannot be stalled by the state pipeline. Unknown is returned for a pane
// never sampled yet, and for a pane whose last refresh failed.
func (p *wiredStateProvider) State(ctx context.Context, pn discovery.Pane) protocol.AgentState {
	ref := sessionRef(pn)
	now := time.Now()

	p.mu.Lock()
	e := p.cache[ref]
	if e == nil {
		// First sighting: seed an unknown entry and schedule the first sample.
		// The pane reports unknown on this listing and the real state once the
		// background refresh lands (next scan at the latest).
		e = &stateCacheEntry{pane: pn, state: protocol.StateUnknown, lastRequested: now}
		p.cache[ref] = e
	}
	e.pane = pn // re-sample with the freshest identity
	e.lastRequested = now
	if !e.inFlight && now.Sub(e.lastRefresh) >= p.ttl {
		e.inFlight = true
		prev := e.prev
		go p.refresh(ref, pn, prev)
	}
	// Read the cached state under the lock so a concurrent refresh write can
	// never race this read (the value is a protocol string, but the read must
	// be synchronized with the write in refresh).
	st := e.state
	p.mu.Unlock()
	return st
}

// refresh runs one sampling pass for a ref and stores the resulting state. It
// is the only place the pipeline's IO (Identify + capture) runs; it is bounded
// by defaultStateSamplingBudget and capped by maxConc. Every failure path
// stores StateUnknown — never an error, never a block (requirement 008).
func (p *wiredStateProvider) refresh(ref string, pn discovery.Pane, prev protocol.AgentState) {
	p.maxConc <- struct{}{}
	defer func() { <-p.maxConc }()

	ctx, cancel := context.WithTimeout(p.ctx, defaultStateSamplingBudget)
	defer cancel()

	state := protocol.StateUnknown
	out, age, err := p.sample(ctx, pn)
	if err != nil {
		// Sample failure (stale socket, tmux gone): degrade to unknown, keep
		// the pane listed. A later refresh retries after ttl.
		p.log.Debug("state: sample failed", "ref", ref, "err", err)
	} else {
		kind := p.identify(ctx, agentstate.IdentifyInput{
			PanePID:     pn.PanePID,
			PaneTitle:   "", // title is a secondary heuristic; the tree is authoritative (state-ident-wrapper §5)
			PaneCommand: pn.Command,
		})
		// Normalize the rule-table dispatch key: for a wrapper pane the
		// identified kind decides which adapter runs (Track dispatches on
		// Sample.PaneCommand). A direct pane keeps its own command.
		cmd := pn.Command
		if c, ok := kind.Command(); ok {
			cmd = c
		}
		decided := agentstate.Track(prev, agentstate.Sample{
			PaneCommand:   cmd,
			RecentOutput:  out,
			LastOutputAge: age,
		})
		state = decided.State
	}

	p.mu.Lock()
	e := p.cache[ref]
	if e == nil {
		e = &stateCacheEntry{}
		p.cache[ref] = e
	}
	e.pane = pn
	e.state = state
	e.prev = state // Track already folded prev; this becomes the next prev
	e.lastRefresh = time.Now()
	e.inFlight = false
	p.mu.Unlock()

	p.prune()
}

// prune reclaims cache entries for panes no listing has requested for
// pruneAge, keeping the map proportional to the live fleet. It runs after each
// refresh, so a parked listing loop (no refreshes, no growth) never needs it.
func (p *wiredStateProvider) prune() {
	now := time.Now()
	p.mu.Lock()
	defer p.mu.Unlock()
	for ref, e := range p.cache {
		if !e.inFlight && now.Sub(e.lastRequested) > p.pruneAge {
			delete(p.cache, ref)
		}
	}
}

// capturePaneOutput is the production sampler: one bounded `tmux capture-pane`
// of the pane's current screen (with ANSI, which the adapters strip). The
// tmux -S socket addresses the pane's server directly (discovery already
// validated the socket), and TMUX is stripped so a nested tmux guard can never
// trip. LastOutputAge is left zero: no rule table consumes it today, and the
// screen itself is the sampled truth.
func capturePaneOutput(ctx context.Context, p discovery.Pane) ([]byte, time.Duration, error) {
	if p.Socket == "" || p.PaneID == "" {
		return nil, 0, fmt.Errorf("state: pane without socket/pane id cannot be sampled")
	}
	cmd := exec.CommandContext(ctx, "tmux", "-S", p.Socket, "capture-pane", "-p", "-t", p.PaneID, "-e")
	cmd.Env = envWithoutTMUX(os.Environ())
	out, err := cmd.CombinedOutput()
	if err != nil {
		return nil, 0, fmt.Errorf("capture pane %s on %s: %w", p.PaneID, p.Socket, err)
	}
	return out, 0, nil
}

// envWithoutTMUX returns the environment with TMUX removed, so a nested tmux
// command can never be refused by the session guard (same scrub the discovery
// scan applies).
func envWithoutTMUX(environ []string) []string {
	out := make([]string, 0, len(environ))
	for _, kv := range environ {
		if strings.HasPrefix(kv, "TMUX=") {
			continue
		}
		out = append(out, kv)
	}
	return out
}
