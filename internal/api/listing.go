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
	"strconv"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/nodeprobe"
	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/agentmirror/agentmirror/internal/sessionname"
)

// displayName is the single Provider-agnostic path from discovered tmux
// fields to protocol Session.name. It does not read Provider, status, or
// native session_name; listing, list_delta, and Level2 all go through here.
func displayName(p discovery.Pane) string {
	return sessionname.Resolve(p.WindowName, p.PaneTitle, p.CWD, p.Command).Value
}

// sessionFromPane is the single discovery-to-protocol projection shared by
// listing and Level2. Display-only Name never replaces structural WindowName
// or WindowIndex, and Ref remains the socket/pane identity.
func sessionFromPane(p discovery.Pane, observation nodeprobe.Observation) protocol.Session {
	return protocol.Session{
		Ref:         sessionRef(p),
		Name:        displayName(p),
		WindowName:  p.WindowName,
		WindowIndex: strconv.Itoa(p.WindowIndex),
		Cwd:         p.CWD,
		Title:       p.PaneTitle,
		Provider:    observation.Provider,
		Activity:    observation.Activity,
		SessionName: observation.SessionName,
		Health:      observation.Health,
		Status:      observation.Activity,
		Rows:        uint16(p.Height),
		Cols:        uint16(p.Width),
	}
}

// toSession converts one catalog entry into the protocol Session the client
// renders using the same projection as Level2. Dims and structural fields
// come from the pane as discovered.
func toSession(e *sessionEntry) protocol.Session {
	return sessionFromPane(e.pane, e.observation)
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
		if e.observation.Activity == protocol.SessionStatusWorking {
			ws.WorkingCount++
		}
		byCWD[s.Cwd] = ws
	}

	ordered := make([]protocol.Workspace, 0, len(byCWD))
	for cwd, ws := range byCWD {
		// Compute the authoritative session count for the listing. The
		// working count was accumulated from the same accepted nodeprobe
		// observations while grouping above; unknown and idle panes do not
		// contribute to it.
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

	// Workspace aggregates are compared directly, rather than only for
	// session refs touched above. A pane's activity may change while its ref,
	// cwd and dimensions stay the same; that transition must still update the
	// level-1 badge. Compare every current cwd and emit zeroes for workspaces
	// that disappeared so the client cannot retain a stale green count.
	for cwd, cur := range m.byCWD {
		prevWs, had := prev.byCWD[cwd]
		if had && prevWs.SessionCount == cur.SessionCount && prevWs.WorkingCount == cur.WorkingCount {
			continue
		}
		d.ChangedWorkspaces = append(d.ChangedWorkspaces, protocol.Workspace{
			Cwd:          cur.Cwd,
			SessionCount: cur.SessionCount,
			WorkingCount: cur.WorkingCount,
			// Sessions omitted: a changed_workspaces entry carries only
			// aggregate count semantics (docs/protocol.md §5.3).
		})
	}
	for cwd := range prev.byCWD {
		if _, ok := m.byCWD[cwd]; ok {
			continue
		}
		d.ChangedWorkspaces = append(d.ChangedWorkspaces, protocol.Workspace{
			Cwd:          cwd,
			SessionCount: 0,
			WorkingCount: 0,
			// The final pane disappeared; explicitly clear the client-side
			// aggregate even though the workspace row itself may be removed.
		})
	}
	sort.Slice(d.AddedSessions, func(i, j int) bool { return d.AddedSessions[i].Ref < d.AddedSessions[j].Ref })
	sort.Strings(d.RemovedRefs)
	sort.Slice(d.ChangedSessions, func(i, j int) bool { return d.ChangedSessions[i].Ref < d.ChangedSessions[j].Ref })
	sort.Slice(d.ChangedWorkspaces, func(i, j int) bool { return d.ChangedWorkspaces[i].Cwd < d.ChangedWorkspaces[j].Cwd })
	return d
}
