package api

// discovery_failure_test.go activates the scriptedDiscoverer.err seam the
// scenario audit flagged as a dead wire (docs/scenario-coverage.md G-6 — the
// field was built but never assigned) and pins the API's degradation contract
// when a tmux scan fails: the last good snapshot stays current, a never-scanned
// server answers a well-formed empty listing (never an error frame), failures
// push no bogus deltas, and a recovery diff resumes normal delta emission.

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

// flipDiscoverer is a scriptedDiscoverer whose error can be switched at
// runtime, so one test can walk a server through healthy → failing → healthy
// scans (the fixed scriptedDiscoverer cannot express that sequencing).
type flipDiscoverer struct {
	mu    sync.Mutex
	model *discovery.Model
	err   error
}

func (d *flipDiscoverer) Discover(context.Context) (*discovery.Model, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.model, d.err
}

// setErr flips the discoverer into (or out of) the failure state.
func (d *flipDiscoverer) setErr(err error) {
	d.mu.Lock()
	d.err = err
	d.mu.Unlock()
}

// TestDiscoveryFailureRetainsLastGoodSnapshot pins the degradation contract: a
// failed scan is logged and skipped, the last good snapshot stays current, and
// a List still answers with it — the dead tmux must never take the listing down
// (server.go publishListing's error path).
func TestDiscoveryFailureRetainsLastGoodSnapshot(t *testing.T) {
	fd := &flipDiscoverer{model: testModel()}
	e := startWS(t, Options{Token: "test-token", Discoverer: fd, ProviderFinder: staticProvider("claude_code")})
	e.auth()

	// Let the baseline scan land (the 50ms test loop), then fail every scan.
	time.Sleep(150 * time.Millisecond)
	fd.setErr(errors.New("scan boom"))

	// A List after failures must return the last good model — not an error
	// frame, not an empty listing.
	e.sendFrame(&protocol.List{ReqID: 3})
	got := e.readControl()
	l, ok := got.(protocol.Listing)
	if !ok {
		t.Fatalf("expected listing after discovery failure, got %v", got.FrameType())
	}
	if len(l.Workspaces) != 1 || l.Workspaces[0].Cwd != "/ws/a" {
		t.Fatalf("listing after failure = %+v, want last good workspace /ws/a", l.Workspaces)
	}
	if len(l.Workspaces[0].Sessions) != 2 {
		t.Fatalf("last good snapshot must keep its sessions, got %d", len(l.Workspaces[0].Sessions))
	}
	if l.Seq < 1 {
		t.Errorf("seq = %d, want >= 1", l.Seq)
	}
}

// TestDiscoveryFailureFromStartReturnsEmptyListing activates the dead seam
// directly: scriptedDiscoverer.err was never assigned before this task. A server
// whose very first scans fail must answer a List with a well-formed empty
// listing (seq >= 1, no workspaces) — never an error frame, never a hang. A
// model is set alongside the err to prove the error dominates Discover's return
// (rebuildCatalog checks err first).
func TestDiscoveryFailureFromStartReturnsEmptyListing(t *testing.T) {
	e := startWS(t, Options{
		Token:      "test-token",
		Discoverer: scriptedDiscoverer{model: testModel(), err: errors.New("tmux down")},
	})
	e.auth()

	e.sendFrame(&protocol.List{ReqID: 4})
	got := e.readControl()
	l, ok := got.(protocol.Listing)
	if !ok {
		t.Fatalf("discovery failure must degrade to a listing, got %v", got.FrameType())
	}
	if len(l.Workspaces) != 0 {
		t.Fatalf("never-scanned server must list empty, got %d workspaces", len(l.Workspaces))
	}
	if l.Seq < 1 {
		t.Errorf("empty listing seq = %d, want >= 1", l.Seq)
	}
}

// TestDiscoveryFailureDoesNotPushBogusDelta pins the "no fabricated delta"
// contract: while scans keep failing, the loop must stay silent — a list_delta
// is never synthesized from a failed scan (the diff would be garbage). The read
// timing out (no frame) is the positive control.
func TestDiscoveryFailureDoesNotPushBogusDelta(t *testing.T) {
	fd := &flipDiscoverer{model: testModel()}
	e := startWS(t, Options{Token: "test-token", Discoverer: fd, ProviderFinder: staticProvider("claude_code")})
	e.auth()

	// Baseline scan lands (the first snapshot is a baseline, so no delta is
	// pushed). Now fail every scan and wait well past several ticks — the read
	// must time out, proving the loop stays silent.
	time.Sleep(150 * time.Millisecond)
	fd.setErr(errors.New("scan down"))
	time.Sleep(150 * time.Millisecond)

	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	typ, _, err := e.conn.Read(ctx)
	cancel()
	if err == nil {
		t.Fatalf("failed scans pushed a frame (type %v); must stay silent", typ)
	}
}

// TestDiscoveryRecoveryResumesDelta is the positive control for the failure
// path: after a failure window, a successful scan with a real change diffs
// against the last good snapshot and pushes a list_delta — the fleet is never
// stuck stale.
func TestDiscoveryRecoveryResumesDelta(t *testing.T) {
	fd := &flipDiscoverer{model: testModel()}
	e := startWS(t, Options{Token: "test-token", Discoverer: fd, ProviderFinder: staticProvider("claude_code")})
	e.auth()

	// Baseline with the original model, then fail a couple of scans.
	time.Sleep(150 * time.Millisecond)
	fd.setErr(errors.New("scan down"))
	time.Sleep(150 * time.Millisecond)

	// Recover with a model that added a workspace: the next successful scan
	// must diff against the retained baseline and push a delta for the change.
	fd.mu.Lock()
	fd.err = nil
	fd.model = &discovery.Model{
		Workspaces: []discovery.Workspace{
			{CWD: "/ws/a", Panes: []discovery.Pane{
				{Socket: "/tmp/sock1", Session: "alpha", PaneID: "%0", CWD: "/ws/a", Command: "claude", Width: 100, Height: 40},
				{Socket: "/tmp/sock1", Session: "beta", PaneID: "%1", CWD: "/ws/a", Command: "codex", Width: 80, Height: 24},
			}},
			{CWD: "/ws/c", Panes: []discovery.Pane{
				{Socket: "/tmp/sock2", Session: "gamma", PaneID: "%2", CWD: "/ws/c", Command: "zsh", Width: 50, Height: 20},
			}},
		},
	}
	fd.mu.Unlock()

	// Read until a list_delta carrying the new /ws/c workspace arrives.
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		ctx, cancel := context.WithTimeout(context.Background(), 500*time.Millisecond)
		typ, data, err := e.conn.Read(ctx)
		cancel()
		if err != nil {
			continue
		}
		if typ != websocket.MessageText {
			continue
		}
		typed, err := protocol.UnmarshalFrame(data)
		if err != nil {
			continue
		}
		d, ok := typed.(protocol.ListDelta)
		if !ok {
			continue
		}
		if len(d.AddedSessions) != 1 || d.AddedSessions[0].Cwd != "/ws/c" {
			t.Fatalf("recovered delta added = %+v, want single /ws/c session", d.AddedSessions)
		}
		return
	}
	t.Fatal("recovery never pushed the expected list_delta")
}

// TestDiscoveryRecoveryReachesConnectedClientFromStartFailure probes the
// recovery edge with NO prior good snapshot: a client that listed during a
// total outage got an empty listing (seq 1); when the fleet reappears, the
// recovery scan must reach that connected client with a delta (requirement 003
// standard four — a session appearing is exactly when the user must be told),
// not silently leave it on the stale empty listing.
//
// The first successful post-outage scan must diff against the empty seq-1
// listing already visible to the client instead of silently replacing it as a
// baseline. The assertions below guard that recovery path.
func TestDiscoveryRecoveryReachesConnectedClientFromStartFailure(t *testing.T) {
	fd := &flipDiscoverer{model: nil, err: errors.New("tmux down")}
	e := startWS(t, Options{Token: "test-token", Discoverer: fd, ProviderFinder: staticProvider("claude_code")})
	e.auth()

	// During the outage the client lists and gets a well-formed empty listing.
	e.sendFrame(&protocol.List{ReqID: 7})
	got := e.readControl()
	if l, ok := got.(protocol.Listing); !ok || len(l.Workspaces) != 0 {
		t.Fatalf("pre-recovery listing = %+v, want empty", got)
	}

	// The fleet comes back: recovery must push a delta so the already-connected
	// client learns about the reappeared sessions.
	fd.mu.Lock()
	fd.err = nil
	fd.model = testModel()
	fd.mu.Unlock()

	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		ctx, cancel := context.WithTimeout(context.Background(), 500*time.Millisecond)
		typ, data, err := e.conn.Read(ctx)
		cancel()
		if err != nil {
			continue
		}
		if typ != websocket.MessageText {
			continue
		}
		typed, err := protocol.UnmarshalFrame(data)
		if err != nil {
			continue
		}
		d, ok := typed.(protocol.ListDelta)
		if !ok {
			continue
		}
		if len(d.AddedSessions) == 2 {
			return
		}
	}
	t.Fatal("recovery after total-outage listing never pushed a delta to the connected client")
}
