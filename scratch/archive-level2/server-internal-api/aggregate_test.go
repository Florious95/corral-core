package api

// aggregate_test.go pins requirement 012's workspace aggregate rules — the
// ranking order blocked > working > idle, unknown exclusion, all-unknown
// aggregation, and the empty workspace — directly on statePriority,
// aggregateState, and wsAggregate (listing.go). These are the server's single
// source of the aggregation rule (protocol.md §5.2), which the scenario audit
// flagged as zero direct coverage (docs/scenario-coverage.md D-5). Pure logic
// tests: no tmux, no WebSocket.
//
// The done slot was removed (user ruling 2026-08-13; see
// docs/archive/agentstate-round4/).

import (
	"context"
	"testing"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

// scriptedStates is a StateProvider that returns the state listed by pane id,
// defaulting to unknown. It lets aggregation/diff tests drive the StateProvider
// seam with ranked inputs without any tmux — the API asks the provider, never
// samples itself (008 isolation law).
type scriptedStates map[string]protocol.AgentState

func (m scriptedStates) State(_ context.Context, p discovery.Pane) protocol.AgentState {
	if s, ok := m[p.PaneID]; ok {
		return s
	}
	return protocol.StateUnknown
}

// TestStatePriorityFullOrdering pins the 012 priority table directly: blocked
// ranks above working above idle, and unknown (plus any unlisted future value)
// ranks lowest at 0.
func TestStatePriorityFullOrdering(t *testing.T) {
	cases := []struct {
		name  string
		state protocol.AgentState
		want  int
	}{
		{name: "blocked", state: protocol.StateBlocked, want: 4},
		{name: "working", state: protocol.StateWorking, want: 2},
		{name: "idle", state: protocol.StateIdle, want: 1},
		{name: "unknown", state: protocol.StateUnknown, want: 0},
		// A value not in the closed set (a future state before a protocol bump)
		// must never out-rank a known state: it degrades to the lowest slot.
		{name: "future", state: protocol.AgentState("flying"), want: 0},
	}
	for _, c := range cases {
		if got := statePriority(c.state); got != c.want {
			t.Errorf("statePriority(%q) = %d, want %d", c.state, got, c.want)
		}
	}
}

// TestAggregateBlockedWinsOverAllKnown pins rule 1: the member with the highest
// attention priority wins. blocked is the top of the table (012,
// protocol.md §5.2).
func TestAggregateBlockedWinsOverAllKnown(t *testing.T) {
	got := aggregateState([]protocol.AgentState{
		protocol.StateIdle, protocol.StateWorking, protocol.StateBlocked,
	})
	if got != protocol.StateBlocked {
		t.Fatalf("aggregate = %q, want blocked", got)
	}
}

// TestAggregateWorkingBeatsIdle pins rule 1's remaining ordering: working
// ranks above idle (the done slot that out-ranked working was removed; see
// the 058 archive).
func TestAggregateWorkingBeatsIdle(t *testing.T) {
	got := aggregateState([]protocol.AgentState{protocol.StateIdle, protocol.StateWorking})
	if got != protocol.StateWorking {
		t.Fatalf("aggregate = %q, want working", got)
	}
}

// TestAggregateUnknownExcludedNeverWins pins rule 2: unknown never participates
// in the highest-priority pick. A workspace mixing a known member with unknown
// members must aggregate to the known member, whatever the known one is.
func TestAggregateUnknownExcludedNeverWins(t *testing.T) {
	if got := aggregateState([]protocol.AgentState{protocol.StateUnknown, protocol.StateWorking}); got != protocol.StateWorking {
		t.Fatalf("unknown+working aggregate = %q, want working (unknown excluded)", got)
	}
	if got := aggregateState([]protocol.AgentState{protocol.StateBlocked, protocol.StateUnknown, protocol.StateUnknown}); got != protocol.StateBlocked {
		t.Fatalf("blocked+unknown aggregate = %q, want blocked", got)
	}
}

// TestAggregateAllUnknownYieldsUnknown pins rule 3: only when every member is
// unknown does the workspace aggregate to unknown.
func TestAggregateAllUnknownYieldsUnknown(t *testing.T) {
	if got := aggregateState([]protocol.AgentState{protocol.StateUnknown, protocol.StateUnknown}); got != protocol.StateUnknown {
		t.Fatalf("all-unknown aggregate = %q, want unknown", got)
	}
}

// TestAggregateEmptyYieldsUnknown pins rule 4 for an empty member list: no
// ranked member exists, so the aggregate degrades to unknown (the vacuous
// all-unknown case the diff path can hit while a cwd is emptying).
func TestAggregateEmptyYieldsUnknown(t *testing.T) {
	if got := aggregateState(nil); got != protocol.StateUnknown {
		t.Fatalf("empty aggregate = %q, want unknown", got)
	}
}

// TestWsAggregateCountAndRankedState pins wsAggregate's two outputs together:
// the workspace's session count and the 012 aggregate derived from its members
// — the value that lands in both listing and list_delta.
func TestWsAggregateCountAndRankedState(t *testing.T) {
	ws := protocol.Workspace{
		Cwd: "/proj/a",
		Sessions: []protocol.Session{
			{Ref: "s1", Name: "c", Cwd: "/proj/a", State: protocol.StateWorking, Rows: 24, Cols: 80},
			{Ref: "s2", Name: "c", Cwd: "/proj/a", State: protocol.StateBlocked, Rows: 24, Cols: 80},
			{Ref: "s3", Name: "c", Cwd: "/proj/a", State: protocol.StateUnknown, Rows: 24, Cols: 80},
		},
	}
	count, agg := wsAggregate(ws)
	if count != 3 {
		t.Errorf("count = %d, want 3", count)
	}
	if agg != protocol.StateBlocked {
		t.Errorf("aggregate = %q, want blocked (highest known member; unknown excluded)", agg)
	}
}

// TestWsAggregateEmptyWorkspace pins rule 4 at the workspace level: a workspace
// with no members has count 0 and aggregates to unknown.
func TestWsAggregateEmptyWorkspace(t *testing.T) {
	ws := protocol.Workspace{Cwd: "/proj/empty"}
	count, agg := wsAggregate(ws)
	if count != 0 {
		t.Errorf("empty workspace count = %d, want 0", count)
	}
	if agg != protocol.StateUnknown {
		t.Errorf("empty workspace aggregate = %q, want unknown", agg)
	}
}
