package api

// listing.go builds the two-level workspace model (docs/protocol.md §5) from
// a discovery snapshot, and diffs two consecutive snapshots into a list_delta.
//
// 060 uproot (2026-08-15): the agent-state pipeline was removed wholesale
// (requirement 060: 二级菜单改为实时流并取代状态判定). The 012 aggregation
// rules and the state fields on Session/Workspace are gone with it. What
// remains is the pure two-level model + the four-set delta mechanism, which
// continues to serve the level-1 menu and the (future) level-2 live stream.

import (
	"sort"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

// toSession converts one catalog entry into the protocol Session the client
// renders. The display name is the tmux window name when one is known (task
// fix-session-alias: window names carry the meaningful per-window labels the
// fleet is organized by), falling back to the session name when the scan
// produced none. Dims come from the pane as discovered.
func toSession(e *sessionEntry) protocol.Session {
	name := e.pane.WindowName
	if name == "" {
		name = e.pane.Session
	}
	return protocol.Session{
		Ref:  e.ref,
		Name: name,
		Cwd:  e.pane.CWD,
		Rows: uint16(e.pane.Height),
		Cols: uint16(e.pane.Width),
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
// computing workspace session counts and ordering deterministically.
func buildSnapshot(c *sessionCatalog) *modelSnapshot {
	entries := c.list()
	sort.Slice(entries, func(i, j int) bool { return entries[i].ref < entries[j].ref })

	byRef := make(map[string]protocol.Session, len(entries))
	byCWD := make(map[string]protocol.Workspace)
	for _, e := range entries {
		s := toSession(e)
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
		// Compute the authoritative session count for the listing. This is the
		// single place the count is derived per workspace; the changed
		// snapshots in diff use the same source.
		ws.SessionCount = len(ws.Sessions)
		byCWD[cwd] = ws // write back so diff reads counted values
		ordered = append(ordered, ws)
	}
	sort.Slice(ordered, func(i, j int) bool { return ordered[i].Cwd < ordered[j].Cwd })

	return &modelSnapshot{byRef: byRef, byCWD: byCWD, ordered: ordered}
}

// listing returns the protocol.Listing payload for a request, in sorted
// workspace order.
func (m *modelSnapshot) listing() []protocol.Workspace {
	return m.ordered
}

// diff computes the list_delta transition from prev to this snapshot. The
// four sets are pairwise disjoint by construction (a ref is added, removed,
// or changed — never more than one). ChangedWorkspaces is re-derived for
// every cwd touched by an add/remove/change and emitted only when the session
// count actually changed (the aggregate-state semantics were removed with the
// agent-state pipeline, 060 uproot).
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
	// Changed: present in both but with a different value (dims, cwd, or name).
	// Sent as a replace (full current value).
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
	// changed session needs its session count re-derived.
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
			// The cwd vanished entirely; count 0 is not emitted as a
			// changed_workspace (removed sessions already told the client).
			// Continue.
			continue
		}
		prevWs, had := prev.byCWD[cwd]
		// Emit only when the session count changed from what the client
		// already knows, so a delta carries no noise.
		if !had || prevWs.SessionCount != cur.SessionCount {
			d.ChangedWorkspaces = append(d.ChangedWorkspaces, protocol.Workspace{
				Cwd:          cur.Cwd,
				SessionCount: cur.SessionCount,
				// Sessions omitted: a changed_workspaces entry carries only
				// the count semantics (docs/protocol.md §5.3).
			})
		}
	}
	sort.Slice(d.AddedSessions, func(i, j int) bool { return d.AddedSessions[i].Ref < d.AddedSessions[j].Ref })
	sort.Strings(d.RemovedRefs)
	sort.Slice(d.ChangedSessions, func(i, j int) bool { return d.ChangedSessions[i].Ref < d.ChangedSessions[j].Ref })
	sort.Slice(d.ChangedWorkspaces, func(i, j int) bool { return d.ChangedWorkspaces[i].Cwd < d.ChangedWorkspaces[j].Cwd })
	return d
}
