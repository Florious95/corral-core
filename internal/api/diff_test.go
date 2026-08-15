package api

// diff_test.go pins modelSnapshot.diff() — the list_delta generator
// (listing.go, protocol.md §5.3) — for the transitions the scenario audit
// flagged as zero-coverage (docs/scenario-coverage.md D-6): added/removed/
// changed sessions, cross-cwd migration, and noise suppression (no change → no
// frame; a count-stable change → no workspace frame). Each test builds two
// modelSnapshots through the production path (sessionCatalog.rebuild +
// buildSnapshot), so diff reads real counts rather than hand-assembled maps.
// Pure logic tests: no tmux, no WebSocket.
//
// 060 uproot (2026-08-15): the agent-state pipeline was removed; the state /
// aggregate assertions are gone with it. What remains is the pure two-level
// model + the four-set delta mechanism.

import (
	"testing"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

// pane is a discovery.Pane for a diff test: one stable socket, unique pane id,
// and a cwd.
func pane(id, cwd, name string) discovery.Pane {
	return discovery.Pane{
		Socket: "/sock", Session: name, PaneID: id, CWD: cwd,
		Command: "claude", Width: 80, Height: 24,
	}
}

// snapshotFromPanes builds a modelSnapshot exactly as the server does — one
// session per pane — from a flat pane list grouped by cwd.
func snapshotFromPanes(panes []discovery.Pane) *modelSnapshot {
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
	return buildSnapshot(c)
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
		pane("%0", "/a", "s1"),
		pane("%1", "/a", "s2"),
	}
	prev := snapshotFromPanes(panes)
	cur := snapshotFromPanes(panes)
	assertEmptyDelta(t, cur.diff(prev))
}

// TestDiffAddedSessionNewWorkspace pins the add path for a brand-new cwd: the
// added session is announced, and the new workspace is announced too.
func TestDiffAddedSessionNewWorkspace(t *testing.T) {
	prev := snapshotFromPanes([]discovery.Pane{
		pane("%0", "/a", "s1"),
	})
	cur := snapshotFromPanes([]discovery.Pane{
		pane("%0", "/a", "s1"),
		pane("%1", "/b", "s2"),
	})

	d := cur.diff(prev)
	if len(d.AddedSessions) != 1 {
		t.Fatalf("added_sessions = %d, want 1", len(d.AddedSessions))
	}
	added := d.AddedSessions[0]
	if added.Ref != "/sock\x1f%1" || added.Cwd != "/b" {
		t.Fatalf("added session = %+v, want /b s2", added)
	}
	if len(d.RemovedRefs) != 0 || len(d.ChangedSessions) != 0 {
		t.Fatalf("unexpected removed/changed: removed=%d changed=%d", len(d.RemovedRefs), len(d.ChangedSessions))
	}
	if len(d.ChangedWorkspaces) != 1 {
		t.Fatalf("changed_workspaces = %d, want 1 for the new workspace", len(d.ChangedWorkspaces))
	}
	ws := d.ChangedWorkspaces[0]
	if ws.Cwd != "/b" || ws.SessionCount != 1 {
		t.Fatalf("changed workspace = %+v, want /b count 1", ws)
	}
}

// TestDiffAddedSessionExistingWorkspace pins the add path for an existing cwd:
// the pre-existing session is not re-announced (only the delta member is), and
// changed_workspaces carries the workspace's new count.
func TestDiffAddedSessionExistingWorkspace(t *testing.T) {
	prev := snapshotFromPanes([]discovery.Pane{
		pane("%0", "/a", "s1"),
	})
	cur := snapshotFromPanes([]discovery.Pane{
		pane("%0", "/a", "s1"),
		pane("%1", "/a", "s2"),
	})

	d := cur.diff(prev)
	if len(d.AddedSessions) != 1 || d.AddedSessions[0].Ref != "/sock\x1f%1" {
		t.Fatalf("added = %+v, want only s2", d.AddedSessions)
	}
	if len(d.ChangedWorkspaces) != 1 {
		t.Fatalf("changed_workspaces = %d, want 1", len(d.ChangedWorkspaces))
	}
	ws := d.ChangedWorkspaces[0]
	if ws.Cwd != "/a" || ws.SessionCount != 2 {
		t.Fatalf("changed workspace = %+v, want /a count 2", ws)
	}
}

// TestDiffRemovedSessionWorkspaceStillAlive pins the remove path when the
// workspace keeps other members: removed_refs carries the ref, and
// changed_workspaces re-announces the shrunk count.
func TestDiffRemovedSessionWorkspaceStillAlive(t *testing.T) {
	prev := snapshotFromPanes([]discovery.Pane{
		pane("%0", "/a", "s1"),
		pane("%1", "/a", "s2"),
	})
	cur := snapshotFromPanes([]discovery.Pane{
		pane("%0", "/a", "s1"),
	})

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
	if ws.Cwd != "/a" || ws.SessionCount != 1 {
		t.Fatalf("changed workspace = %+v, want /a count 1", ws)
	}
}

// TestDiffRemovedLastSessionWorkspaceVanishes pins the vanish path: removing a
// cwd's last member announces the removal but NOT a changed_workspace for the
// vanished cwd — emitting count 0 would be noise (listing.go's vanished-cwd
// branch).
func TestDiffRemovedLastSessionWorkspaceVanishes(t *testing.T) {
	prev := snapshotFromPanes([]discovery.Pane{
		pane("%0", "/a", "s1"),
	})
	cur := snapshotFromPanes(nil)

	d := cur.diff(prev)
	if len(d.RemovedRefs) != 1 || d.RemovedRefs[0] != "/sock\x1f%0" {
		t.Fatalf("removed_refs = %v, want [s1 ref]", d.RemovedRefs)
	}
	if len(d.ChangedWorkspaces) != 0 {
		t.Fatalf("vanished workspace must not emit changed_workspaces, got %+v", d.ChangedWorkspaces)
	}
}

// TestDiffDimsChangeSuppressesWorkspaceFrame pins a noise-suppression edge: a
// resize-only change (same ref, cwd) re-announces the changed session but not
// the workspace — count is unaffected, so a terminal resize must not churn the
// workspace header.
func TestDiffDimsChangeSuppressesWorkspaceFrame(t *testing.T) {
	prev := snapshotFromPanes([]discovery.Pane{
		{Socket: "/sock", Session: "s1", PaneID: "%0", CWD: "/a", Command: "claude", Width: 80, Height: 24},
	})
	cur := snapshotFromPanes([]discovery.Pane{
		{Socket: "/sock", Session: "s1", PaneID: "%0", CWD: "/a", Command: "claude", Width: 120, Height: 30},
	})

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
// cwd, and BOTH the old and the new workspace re-announce their counts (the old
// one shrinks, the new one grows).
func TestDiffCrossCwdMigration(t *testing.T) {
	prev := snapshotFromPanes([]discovery.Pane{
		pane("%0", "/a", "s1"),
		pane("%1", "/a", "s2"),
	})
	cur := snapshotFromPanes([]discovery.Pane{
		pane("%1", "/a", "s2"),
		pane("%0", "/b", "s1"),
	})

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
	if ws := byCwd["/a"]; ws.SessionCount != 1 {
		t.Fatalf("/a = %+v, want count 1 (s2 remains)", ws)
	}
	if ws := byCwd["/b"]; ws.SessionCount != 1 {
		t.Fatalf("/b = %+v, want count 1 (s1 arrived)", ws)
	}
}
