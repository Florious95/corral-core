package api

// diff_test.go pins modelSnapshot.diff() — the list_delta generator
// (listing.go, protocol.md §5.3) — for the transitions the scenario audit
// flagged as zero-coverage (docs/scenario-coverage.md D-6): added/removed/
// changed sessions, cross-cwd migration, and noise suppression (no change → no
// frame; an aggregate-stable change → no workspace frame). Each test builds two
// modelSnapshots through the production path (sessionCatalog.rebuild +
// buildSnapshot), so diff reads real aggregates rather than hand-assembled
// maps. Pure logic tests: no tmux, no WebSocket.

import (
	"context"
	"testing"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

// paneWithState is a discovery.Pane for a diff test: one stable socket, unique
// pane id, and a cwd. State is supplied separately through scriptedStates.
func paneWithState(id, cwd, name string, st protocol.AgentState) discovery.Pane {
	return discovery.Pane{
		Socket: "/sock", Session: name, PaneID: id, CWD: cwd,
		Command: "claude", Width: 80, Height: 24,
	}
}

// snapshotFromPanes builds a modelSnapshot exactly as the server does — one
// session per pane, states resolved through scriptedStates — from a flat pane
// list grouped by cwd.
func snapshotFromPanes(panes []discovery.Pane, states map[string]protocol.AgentState) *modelSnapshot {
	model := &discovery.Model{}
	byCWD := map[string][]discovery.Pane{}
	for _, p := range panes {
		byCWD[p.CWD] = append(byCWD[p.CWD], p)
	}
	for cwd, ps := range byCWD {
		model.Workspaces = append(model.Workspaces, discovery.Workspace{CWD: cwd, Panes: ps})
	}
	c := newSessionCatalog()
	c.rebuild(model)
	return buildSnapshot(c, scriptedStates(states), context.Background())
}

// assertEmptyDelta fails unless every one of the delta's four sets is empty.
func assertEmptyDelta(t *testing.T, d *protocol.ListDelta) {
	t.Helper()
	if len(d.AddedSessions) != 0 || len(d.RemovedRefs) != 0 || len(d.ChangedSessions) != 0 || len(d.ChangedWorkspaces) != 0 {
		t.Fatalf("expected an empty delta, got added=%d removed=%d changed=%d workspaces=%d",
			len(d.AddedSessions), len(d.RemovedRefs), len(d.ChangedSessions), len(d.ChangedWorkspaces))
	}
}

// TestDiffNoChangeYieldsEmptyDelta pins the core noise suppression: two
// identical snapshots produce an empty delta — no added/removed/changed
// session, no changed workspace. The listing loop relies on this to stay
// silent while the fleet is stable.
func TestDiffNoChangeYieldsEmptyDelta(t *testing.T) {
	panes := []discovery.Pane{
		paneWithState("%0", "/a", "s1", protocol.StateWorking),
		paneWithState("%1", "/a", "s2", protocol.StateBlocked),
	}
	states := map[string]protocol.AgentState{
		"%0": protocol.StateWorking,
		"%1": protocol.StateBlocked,
	}
	prev := snapshotFromPanes(panes, states)
	cur := snapshotFromPanes(panes, states)
	assertEmptyDelta(t, cur.diff(prev))
}

// TestDiffAddedSessionNewWorkspace pins the add path for a brand-new cwd: the
// added session is announced, and the new workspace is announced too (the
// client cannot derive aggregates, so the server must tell it the aggregate,
// protocol.md §5.3).
func TestDiffAddedSessionNewWorkspace(t *testing.T) {
	prev := snapshotFromPanes([]discovery.Pane{
		paneWithState("%0", "/a", "s1", protocol.StateWorking),
	}, map[string]protocol.AgentState{"%0": protocol.StateWorking})
	cur := snapshotFromPanes([]discovery.Pane{
		paneWithState("%0", "/a", "s1", protocol.StateWorking),
		paneWithState("%1", "/b", "s2", protocol.StateBlocked),
	}, map[string]protocol.AgentState{"%0": protocol.StateWorking, "%1": protocol.StateBlocked})

	d := cur.diff(prev)
	if len(d.AddedSessions) != 1 {
		t.Fatalf("added_sessions = %d, want 1", len(d.AddedSessions))
	}
	added := d.AddedSessions[0]
	if added.Ref != "/sock\x1f%1" || added.Cwd != "/b" || added.State != protocol.StateBlocked {
		t.Fatalf("added session = %+v, want /b blocked s2", added)
	}
	if len(d.RemovedRefs) != 0 || len(d.ChangedSessions) != 0 {
		t.Fatalf("unexpected removed/changed: removed=%d changed=%d", len(d.RemovedRefs), len(d.ChangedSessions))
	}
	if len(d.ChangedWorkspaces) != 1 {
		t.Fatalf("changed_workspaces = %d, want 1 for the new workspace", len(d.ChangedWorkspaces))
	}
	ws := d.ChangedWorkspaces[0]
	if ws.Cwd != "/b" || ws.SessionCount != 1 || ws.AggregateState != protocol.StateBlocked {
		t.Fatalf("changed workspace = %+v, want /b count 1 blocked", ws)
	}
}

// TestDiffAddedSessionExistingWorkspace pins the add path for an existing cwd:
// the pre-existing session is not re-announced (only the delta member is), and
// changed_workspaces carries the workspace's new count and aggregate.
func TestDiffAddedSessionExistingWorkspace(t *testing.T) {
	prev := snapshotFromPanes([]discovery.Pane{
		paneWithState("%0", "/a", "s1", protocol.StateWorking),
	}, map[string]protocol.AgentState{"%0": protocol.StateWorking})
	cur := snapshotFromPanes([]discovery.Pane{
		paneWithState("%0", "/a", "s1", protocol.StateWorking),
		paneWithState("%1", "/a", "s2", protocol.StateBlocked),
	}, map[string]protocol.AgentState{"%0": protocol.StateWorking, "%1": protocol.StateBlocked})

	d := cur.diff(prev)
	if len(d.AddedSessions) != 1 || d.AddedSessions[0].Ref != "/sock\x1f%1" {
		t.Fatalf("added = %+v, want only s2", d.AddedSessions)
	}
	if len(d.ChangedWorkspaces) != 1 {
		t.Fatalf("changed_workspaces = %d, want 1", len(d.ChangedWorkspaces))
	}
	ws := d.ChangedWorkspaces[0]
	if ws.Cwd != "/a" || ws.SessionCount != 2 || ws.AggregateState != protocol.StateBlocked {
		t.Fatalf("changed workspace = %+v, want /a count 2 blocked", ws)
	}
}

// TestDiffRemovedSessionWorkspaceStillAlive pins the remove path when the
// workspace keeps other members: removed_refs carries the ref, and
// changed_workspaces re-announces the shrunk aggregate/count.
func TestDiffRemovedSessionWorkspaceStillAlive(t *testing.T) {
	prev := snapshotFromPanes([]discovery.Pane{
		paneWithState("%0", "/a", "s1", protocol.StateWorking),
		paneWithState("%1", "/a", "s2", protocol.StateBlocked),
	}, map[string]protocol.AgentState{"%0": protocol.StateWorking, "%1": protocol.StateBlocked})
	cur := snapshotFromPanes([]discovery.Pane{
		paneWithState("%0", "/a", "s1", protocol.StateWorking),
	}, map[string]protocol.AgentState{"%0": protocol.StateWorking})

	d := cur.diff(prev)
	if len(d.RemovedRefs) != 1 || d.RemovedRefs[0] != "/sock\x1f%1" {
		t.Fatalf("removed_refs = %v, want [s2 ref]", d.RemovedRefs)
	}
	if len(d.AddedSessions) != 0 || len(d.ChangedSessions) != 0 {
		t.Fatalf("unexpected added/changed: added=%d changed=%d", len(d.AddedSessions), len(d.ChangedSessions))
	}
	if len(d.ChangedWorkspaces) != 1 {
		t.Fatalf("changed_workspaces = %d, want 1", len(d.ChangedWorkspaces))
	}
	ws := d.ChangedWorkspaces[0]
	if ws.Cwd != "/a" || ws.SessionCount != 1 || ws.AggregateState != protocol.StateWorking {
		t.Fatalf("changed workspace = %+v, want /a count 1 working", ws)
	}
}

// TestDiffRemovedLastSessionWorkspaceVanishes pins the vanish path: removing a
// cwd's last member announces the removal but NOT a changed_workspace for the
// vanished cwd — emitting count 0/unknown would be noise (the client already
// dropped the workspace from the removed member; listing.go's vanished-cwd
// branch).
func TestDiffRemovedLastSessionWorkspaceVanishes(t *testing.T) {
	prev := snapshotFromPanes([]discovery.Pane{
		paneWithState("%0", "/a", "s1", protocol.StateWorking),
	}, map[string]protocol.AgentState{"%0": protocol.StateWorking})
	cur := snapshotFromPanes(nil, nil)

	d := cur.diff(prev)
	if len(d.RemovedRefs) != 1 || d.RemovedRefs[0] != "/sock\x1f%0" {
		t.Fatalf("removed_refs = %v, want [s1 ref]", d.RemovedRefs)
	}
	if len(d.ChangedWorkspaces) != 0 {
		t.Fatalf("vanished workspace must not emit changed_workspaces, got %+v", d.ChangedWorkspaces)
	}
}

// TestDiffStateChangeUpdatesAggregate pins the change path: a session whose
// state moves must be re-announced as a changed session, and when the workspace
// aggregate follows it, changed_workspaces carries the new aggregate.
func TestDiffStateChangeUpdatesAggregate(t *testing.T) {
	prev := snapshotFromPanes([]discovery.Pane{
		paneWithState("%0", "/a", "s1", protocol.StateWorking),
	}, map[string]protocol.AgentState{"%0": protocol.StateWorking})
	cur := snapshotFromPanes([]discovery.Pane{
		paneWithState("%0", "/a", "s1", protocol.StateBlocked),
	}, map[string]protocol.AgentState{"%0": protocol.StateBlocked})

	d := cur.diff(prev)
	if len(d.ChangedSessions) != 1 {
		t.Fatalf("changed_sessions = %d, want 1", len(d.ChangedSessions))
	}
	cs := d.ChangedSessions[0]
	if cs.Ref != "/sock\x1f%0" || cs.State != protocol.StateBlocked {
		t.Fatalf("changed session = %+v, want blocked s1", cs)
	}
	if len(d.ChangedWorkspaces) != 1 {
		t.Fatalf("changed_workspaces = %d, want 1", len(d.ChangedWorkspaces))
	}
	if ws := d.ChangedWorkspaces[0]; ws.AggregateState != protocol.StateBlocked {
		t.Fatalf("aggregate = %q, want blocked", ws.AggregateState)
	}
}

// TestDiffStateChangeStableAggregateSuppressesWorkspaceFrame pins a noise-
// suppression edge: a session state change that does not move the workspace
// aggregate (blocked stays blocked) re-announces the session but emits NO
// changed_workspaces — the client's workspace header is still correct.
func TestDiffStateChangeStableAggregateSuppressesWorkspaceFrame(t *testing.T) {
	prev := snapshotFromPanes([]discovery.Pane{
		paneWithState("%0", "/a", "s1", protocol.StateWorking),
		paneWithState("%1", "/a", "s2", protocol.StateBlocked),
	}, map[string]protocol.AgentState{"%0": protocol.StateWorking, "%1": protocol.StateBlocked})
	cur := snapshotFromPanes([]discovery.Pane{
		paneWithState("%0", "/a", "s1", protocol.StateIdle),
		paneWithState("%1", "/a", "s2", protocol.StateBlocked),
	}, map[string]protocol.AgentState{"%0": protocol.StateIdle, "%1": protocol.StateBlocked})

	d := cur.diff(prev)
	if len(d.ChangedSessions) != 1 || d.ChangedSessions[0].State != protocol.StateIdle {
		t.Fatalf("changed_sessions = %+v, want idle s1", d.ChangedSessions)
	}
	if len(d.ChangedWorkspaces) != 0 {
		t.Fatalf("aggregate-stable change must not emit changed_workspaces, got %+v", d.ChangedWorkspaces)
	}
}

// TestDiffDimsChangeSuppressesWorkspaceFrame pins another noise-suppression
// edge: a resize-only change (same ref, state, cwd) re-announces the changed
// session but not the workspace — count and aggregate are unaffected, so a
// terminal resize must not churn the workspace header.
func TestDiffDimsChangeSuppressesWorkspaceFrame(t *testing.T) {
	prev := snapshotFromPanes([]discovery.Pane{
		{Socket: "/sock", Session: "s1", PaneID: "%0", CWD: "/a", Command: "claude", Width: 80, Height: 24},
	}, map[string]protocol.AgentState{"%0": protocol.StateWorking})
	cur := snapshotFromPanes([]discovery.Pane{
		{Socket: "/sock", Session: "s1", PaneID: "%0", CWD: "/a", Command: "claude", Width: 120, Height: 30},
	}, map[string]protocol.AgentState{"%0": protocol.StateWorking})

	d := cur.diff(prev)
	if len(d.ChangedSessions) != 1 {
		t.Fatalf("changed_sessions = %d, want 1", len(d.ChangedSessions))
	}
	cs := d.ChangedSessions[0]
	if cs.Rows != 30 || cs.Cols != 120 {
		t.Fatalf("changed session dims = %dx%d, want 120x30", cs.Cols, cs.Rows)
	}
	if len(d.ChangedWorkspaces) != 0 {
		t.Fatalf("dims-only change must not emit changed_workspaces, got %+v", d.ChangedWorkspaces)
	}
}

// TestDiffCrossCwdMigration pins the migration path: a session whose cwd moves
// between snapshots (e.g. the agent cd'd) is a changed session carrying the new
// cwd, and BOTH the old and the new workspace re-announce their aggregate/count
// (the old one shrinks, the new one grows).
func TestDiffCrossCwdMigration(t *testing.T) {
	prev := snapshotFromPanes([]discovery.Pane{
		paneWithState("%0", "/a", "s1", protocol.StateWorking),
		paneWithState("%1", "/a", "s2", protocol.StateBlocked),
	}, map[string]protocol.AgentState{"%0": protocol.StateWorking, "%1": protocol.StateBlocked})
	cur := snapshotFromPanes([]discovery.Pane{
		paneWithState("%1", "/a", "s2", protocol.StateBlocked),
		paneWithState("%0", "/b", "s1", protocol.StateWorking),
	}, map[string]protocol.AgentState{"%0": protocol.StateWorking, "%1": protocol.StateBlocked})

	d := cur.diff(prev)
	if len(d.ChangedSessions) != 1 {
		t.Fatalf("changed_sessions = %d, want 1 (the migrated session)", len(d.ChangedSessions))
	}
	cs := d.ChangedSessions[0]
	if cs.Ref != "/sock\x1f%0" || cs.Cwd != "/b" {
		t.Fatalf("changed session = %+v, want s1 at /b", cs)
	}
	if len(d.AddedSessions) != 0 || len(d.RemovedRefs) != 0 {
		t.Fatalf("migration must not add/remove the ref: added=%d removed=%d", len(d.AddedSessions), len(d.RemovedRefs))
	}
	// Both the old and the new workspace are re-announced.
	if len(d.ChangedWorkspaces) != 2 {
		t.Fatalf("changed_workspaces = %d, want 2 (old + new), got %+v", len(d.ChangedWorkspaces), d.ChangedWorkspaces)
	}
	byCwd := map[string]protocol.Workspace{}
	for _, ws := range d.ChangedWorkspaces {
		byCwd[ws.Cwd] = ws
	}
	if ws := byCwd["/a"]; ws.SessionCount != 1 || ws.AggregateState != protocol.StateBlocked {
		t.Fatalf("/a = %+v, want count 1 blocked (s2 remains)", ws)
	}
	if ws := byCwd["/b"]; ws.SessionCount != 1 || ws.AggregateState != protocol.StateWorking {
		t.Fatalf("/b = %+v, want count 1 working (s1 arrived)", ws)
	}
}
