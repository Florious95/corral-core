package api

// state_wiring_test.go — the StateProvider seam's isolation-law pins that
// SURVIVE the 058 archive (task fix-state-wiring, defect D-1). The 058 archive
// removed the decision layer; the tests that asserted it (wrapper tree →
// blocked, static title glyph → working/idle) are archived to
// docs/archive/agentstate-round4/api-state-wiring-fossil-tests.go. What stays
// here pins behavior that must hold while the state pipeline reports unknown:
//
//   - the pre-fix default (no provider) must still render all-unknown, so the
//     unknown fallback is never vacuous;
//   - the provider's cache must serve State() without blocking on IO and
//     degrade to unknown on sample failure (requirement 008 isolation law).
//   - PaneTitle reaching the decision function is NOT asserted here: the
//     archived title-glyph test was the fossil; the wiring re-verification
//     belongs to t.oracle's probe.

import (
	"context"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/protocol"
)


// TestStateWiringDefaultProviderStaysUnknown is the pre-fix control: without
// wiring a StateProvider, the API default must keep rendering all-unknown.
// This proves the wiring test above is not vacuously green — the unknown
// default is the D-1 defect this task removes for production wiring, and it
// must remain the safe fallback when no provider is configured.
func TestStateWiringDefaultProviderStaysUnknown(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	// The default provider (unknownState) is what startWS wires when Options
	// carries none.

	// List and assert the pane is unknown (and only unknown) regardless of
	// what the pane actually shows.
	te.wsEnv.sendFrame(&protocol.List{ReqID: 9})
	got := te.wsEnv.readControl()
	l, ok := got.(protocol.Listing)
	if !ok {
		t.Fatalf("expected listing, got %v", got.FrameType())
	}
	if len(l.Workspaces) != 1 || len(l.Workspaces[0].Sessions) != 1 {
		t.Fatalf("unexpected listing shape: %+v", l.Workspaces)
	}
	if st := l.Workspaces[0].Sessions[0].State; st != protocol.StateUnknown {
		t.Fatalf("default provider state = %q, want unknown (the pre-fix fallback)", st)
	}
}

// TestStateProviderSampleFailureDegradesUnknown pins the isolation law: a pane
// whose capture fails (here: a socket that does not exist) must degrade to
// StateUnknown, never block State(), and never affect the mirror path. The
// cached entry is served synchronously; the failed refresh stores unknown.
func TestStateProviderSampleFailureDegradesUnknown(t *testing.T) {
	p := NewStateProvider(discardLogger())
	defer p.Close()
	p.ttl = time.Millisecond // refresh eagerly

	// A pane on a socket that cannot be captured (no tmux server there).
	pn := discovery.Pane{Socket: "/nonexistent/sock", PaneID: "%0", Command: "claude", CWD: "/ws/x", Width: 80, Height: 24}
	st := p.State(context.Background(), pn)
	if st != protocol.StateUnknown {
		t.Fatalf("first sighting state = %q, want unknown (cache seed)", st)
	}
	// Give the failed refresh time to run; the next call must still be unknown.
	time.Sleep(200 * time.Millisecond)
	st = p.State(context.Background(), pn)
	if st != protocol.StateUnknown {
		t.Fatalf("post-failure state = %q, want unknown (isolation law)", st)
	}
}

// TestStateProviderCacheDoesNotBlockHotPath pins the hot-path isolation:
// State() is a synchronous cache read. A provider whose sample seam hangs must
// still return a state immediately (it returns the cached value and refreshes
// in the background), so the listing loop can never be stalled by state IO
// (requirement 008, knowledge base §0.4 D-1 red line).

func TestStateProviderCacheDoesNotBlockHotPath(t *testing.T) {
	p := NewStateProvider(discardLogger())
	defer p.Close()
	p.ttl = time.Hour // never refresh for real in this test

	// Swap in a sample seam that would block forever if called synchronously.
	p.sample = func(ctx context.Context, pn discovery.Pane) ([]byte, time.Duration, error) {
		<-ctx.Done()
		return nil, 0, ctx.Err()
	}

	pn := discovery.Pane{Socket: "/s", PaneID: "%0", Command: "claude", CWD: "/ws/x", Width: 80, Height: 24}
	// Seed the cache first so State() has something to serve.
	_ = p.State(context.Background(), pn)
	// A second call within the TTL must return from cache — no refresh, no IO.
	start := time.Now()
	st := p.State(context.Background(), pn)
	if elapsed := time.Since(start); elapsed > 50*time.Millisecond {
		t.Fatalf("State() took %v; hot-path cache read must not block on state IO", elapsed)
	}
	if st != protocol.StateUnknown {
		t.Fatalf("cache-seeded state = %q, want unknown", st)
	}
}
