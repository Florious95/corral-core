package api

// listing.go builds the two-level workspace model (docs/protocol.md §5) from
// a discovery snapshot, computes the authoritative workspace aggregates
// (requirement 012), and diffs two consecutive snapshots into a list_delta.
// The aggregation rule lives here and nowhere else on the server, and it is
// the single source of truth for what the client renders (012).

import (
	"context"
	"sort"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

// statePriority ranks agent states by attention demand (requirement 012):
// blocked > working > idle. unknown ranks lowest and is never selected (it is
// skipped before any comparison). The done slot was removed (user ruling
// 2026-08-13: the server has no done; see the 058 archive).
func statePriority(s protocol.AgentState) int {
	switch s {
	case protocol.StateBlocked:
		return 4
	case protocol.StateWorking:
		return 2
	case protocol.StateIdle:
		return 1
	default: // StateUnknown and any future value
		return 0
	}
}

// aggregateState applies the 012 aggregation rule to a workspace's member
// states: the member with the highest attention priority wins; unknown
// members are excluded; if every member is unknown the aggregate is unknown.
func aggregateState(states []protocol.AgentState) protocol.AgentState {
	best := protocol.StateUnknown
	bestP := 0
	for _, s := range states {
		if p := statePriority(s); p > bestP {
			best = s
			bestP = p
		}
	}
	return best
}

// toSession converts one catalog entry into the protocol Session the client
// renders. State comes from the provider (which may be the always-unknown
// default); dims come from the pane as discovered. The display name is the
// tmux window name when one is known (task fix-session-alias: window names
// carry the meaningful per-window labels the fleet is organized by), falling
// back to the session name when the scan produced none.
func toSession(e *sessionEntry, provider StateProvider, ctx context.Context) protocol.Session {
	name := e.pane.WindowName
	if name == "" {
		name = e.pane.Session
	}
	return protocol.Session{
		Ref:   e.ref,
		Name:  name,
		Cwd:   e.pane.CWD,
		State: provider.State(ctx, e.pane),
		Rows:  uint16(e.pane.Height),
		Cols:  uint16(e.pane.Width),
	}
}

// modelSnapshot is one version of the two-level model the server has
// published (or is about to publish). It is the diff input for list_delta
// generation and the payload source for a full listing.
type modelSnapshot struct {
	// byRef indexes every session by its stable ref.
	byRef map[string]protocol.Session
	// byCWD indexes every workspace by its grouping key.
	byCWD map[string]protocol.Workspace
	// ordered lists the workspaces sorted by CWD for deterministic output.
	ordered []protocol.Workspace
}

// buildSnapshot converts the catalog's current entries into a modelSnapshot,
// computing aggregates and ordering deterministically.
func buildSnapshot(c *sessionCatalog, provider StateProvider, ctx context.Context) *modelSnapshot {
	entries := c.list()
	sort.Slice(entries, func(i, j int) bool { return entries[i].ref < entries[j].ref })

	byRef := make(map[string]protocol.Session, len(entries))
	byCWD := make(map[string]protocol.Workspace)
	for _, e := range entries {
		s := toSession(e, provider, ctx)
		byRef[s.Ref] = s
		ws, ok := byCWD[s.Cwd]
		if !ok {
			ws = protocol.Workspace{Cwd: s.Cwd}
		}
		ws.Sessions = append(ws.Sessions, s)
		byCWD[s.Cwd] = ws
	}

	ordered := make([]protocol.Workspace, 0, len(byCWD))
	for cwd, ws := range byCWD {
		// Compute the authoritative aggregate and count for the listing. This
		// is the single place the 012 rule runs per workspace; the changed
		// snapshots in diff use the same source.
		ws.SessionCount, ws.AggregateState = wsAggregate(ws)
		byCWD[cwd] = ws // write back so diff reads aggregated values
		ordered = append(ordered, ws)
	}
	sort.Slice(ordered, func(i, j int) bool { return ordered[i].Cwd < ordered[j].Cwd })

	return &modelSnapshot{byRef: byRef, byCWD: byCWD, ordered: ordered}
}

// wsAggregate computes a workspace's session count and aggregate state from
// its current member sessions (requirement 012). It is the single source of
// the aggregation rule for both the full listing and delta emission.
func wsAggregate(ws protocol.Workspace) (count int, agg protocol.AgentState) {
	states := make([]protocol.AgentState, 0, len(ws.Sessions))
	for _, s := range ws.Sessions {
		states = append(states, s.State)
	}
	return len(ws.Sessions), aggregateState(states)
}

// listing returns the protocol.Listing payload for a request, in sorted
// workspace order.
func (m *modelSnapshot) listing() []protocol.Workspace {
	return m.ordered
}

// diff computes the list_delta transition from prev to this snapshot. The
// four sets are pairwise disjoint by construction (a ref is added, removed,
// or changed — never more than one). Workspace aggregates are recomputed for
// every cwd touched by an add/remove/change and emitted only when the
// aggregate or count actually changed.
func (m *modelSnapshot) diff(prev *modelSnapshot) *protocol.ListDelta {
	d := &protocol.ListDelta{}

	// Added: in this snapshot but absent from the previous one.
	for ref, s := range m.byRef {
		if _, ok := prev.byRef[ref]; !ok {
			d.AddedSessions = append(d.AddedSessions, s)
		}
	}
	// Removed: in the previous snapshot but gone now.
	for ref := range prev.byRef {
		if _, ok := m.byRef[ref]; !ok {
			d.RemovedRefs = append(d.RemovedRefs, ref)
		}
	}
	// Changed: present in both but with a different value (state, dims, cwd,
	// or name). Sent as a replace (full current value).
	for ref, cur := range m.byRef {
		old, ok := prev.byRef[ref]
		if !ok {
			continue
		}
		if old != cur {
			d.ChangedSessions = append(d.ChangedSessions, cur)
		}
	}

	// Affected cwds: any workspace that contains an added, removed, or
	// changed session needs its aggregate/count re-derived.
	affected := make(map[string]bool)
	for _, s := range d.AddedSessions {
		affected[s.Cwd] = true
	}
	for _, ref := range d.RemovedRefs {
		if p, ok := prev.byRef[ref]; ok {
			affected[p.Cwd] = true
		}
	}
	for _, s := range d.ChangedSessions {
		affected[s.Cwd] = true
	}
	// A changed session that moved cwd affects both its old and new workspace.
	for ref, cur := range m.byRef {
		old, ok := prev.byRef[ref]
		if !ok {
			continue
		}
		if old != cur && old.Cwd != cur.Cwd {
			affected[old.Cwd] = true
		}
	}

	for cwd := range affected {
		cur, ok := m.byCWD[cwd]
		if !ok {
			// The cwd vanished entirely; count 0/aggregate unknown is not
			// emitted as a changed_workspace (removed sessions already told
			// the client). Continue.
			continue
		}
		prevWs, had := prev.byCWD[cwd]
		// Emit only when the aggregate/count changed from what the client
		// already knows, so a delta carries no noise.
		if !had || prevWs.SessionCount != cur.SessionCount || prevWs.AggregateState != cur.AggregateState {
			d.ChangedWorkspaces = append(d.ChangedWorkspaces, protocol.Workspace{
				Cwd:            cur.Cwd,
				SessionCount:   cur.SessionCount,
				AggregateState: cur.AggregateState,
				// Sessions omitted: a changed_workspaces entry carries only
				// the aggregate/count semantics (docs/protocol.md §5.3).
			})
		}
	}
	sort.Slice(d.AddedSessions, func(i, j int) bool { return d.AddedSessions[i].Ref < d.AddedSessions[j].Ref })
	sort.Strings(d.RemovedRefs)
	sort.Slice(d.ChangedSessions, func(i, j int) bool { return d.ChangedSessions[i].Ref < d.ChangedSessions[j].Ref })
	sort.Slice(d.ChangedWorkspaces, func(i, j int) bool { return d.ChangedWorkspaces[i].Cwd < d.ChangedWorkspaces[j].Cwd })
	return d
}
