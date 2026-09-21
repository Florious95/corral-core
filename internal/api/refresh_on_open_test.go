package api

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/nodeprobe"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

func TestRefreshOnOpenListRescans(t *testing.T) {
	md := &mutableDiscoverer{model: &discovery.Model{
		Workspaces: []discovery.Workspace{
			{CWD: "/ws/a", Panes: []discovery.Pane{
				{Socket: "/tmp/sock1", Session: "keep", PaneID: "%0", CWD: "/ws/a",
					Command: "node", WindowName: "rf-keep", Width: 80, Height: 24},
			}},
		},
	}}
	e := startWS(t, Options{
		Token:        "test-token",
		Discoverer:   md,
		ListInterval: time.Hour,
	})
	e.auth()
	e.sendFrame(&protocol.List{ReqID: 1})
	l1 := mustListing(t, e, 1)
	if listingNames(l1)["rf-keep"] == false {
		t.Fatalf("list1 missing rf-keep: %+v", l1.Workspaces)
	}

	md.set(&discovery.Model{
		Workspaces: []discovery.Workspace{
			{CWD: "/ws/a", Panes: []discovery.Pane{
				{Socket: "/tmp/sock1", Session: "keep", PaneID: "%0", CWD: "/ws/a",
					Command: "node", WindowName: "rf-keep", Width: 80, Height: 24},
				{Socket: "/tmp/sock1", Session: "add", PaneID: "%1", CWD: "/ws/a",
					Command: "node", WindowName: "rf-added", Width: 80, Height: 24},
			}},
		},
	})
	e.sendFrame(&protocol.List{ReqID: 2})
	l2 := mustListing(t, e, 2)
	names := listingNames(l2)
	if !names["rf-keep"] || !names["rf-added"] {
		t.Fatalf("list2 still old world names=%v (ensureInitialScan cache?)", names)
	}
}

func TestRunnerBackedListPublishesActivityBeforeInventoryCacheRefresh(t *testing.T) {
	idleMarker := filepath.Join(t.TempDir(), "idle")
	probe := filepath.Join(t.TempDir(), "nodeprobe.sh")
	script := "#!/bin/sh\nactivity=working\nif [ -f \"" + idleMarker + "\" ]; then activity=idle; fi\nprintf '{\\\"schema_version\\\":1,\\\"socket\\\":\\\"%s\\\",\\\"nodes\\\":[{\\\"session\\\":\\\"alpha\\\",\\\"window_index\\\":0,\\\"pane_id\\\":\\\"%%0\\\",\\\"provider\\\":\\\"pi\\\",\\\"state\\\":\\\"%s\\\",\\\"activity\\\":\\\"%s\\\",\\\"health\\\":\\\"normal\\\"}]}\\n' \"$2\" \"$activity\" \"$activity\"\n"
	if err := os.WriteFile(probe, []byte(script), 0o700); err != nil {
		t.Fatal(err)
	}
	md := &mutableDiscoverer{model: &discovery.Model{
		Workspaces: []discovery.Workspace{{CWD: "/ws/a", Panes: []discovery.Pane{
			{Socket: "/tmp/sock1", Session: "alpha", PaneID: "%0", CWD: "/ws/a", PanePID: 4242,
				Command: "node", WindowName: "status", Width: 80, Height: 24},
		}}},
	}}
	e := startWS(t, Options{
		Token:        "test-token",
		Discoverer:   md,
		Nodeprobe:    nodeprobe.NewRunner(nodeprobe.Capability{Binary: probe}),
		ListInterval: 20 * time.Millisecond,
	})
	e.auth()
	e.sendFrame(&protocol.List{ReqID: 1})
	first := mustListing(t, e, 1)
	if got := first.Workspaces[0].Sessions[0].Activity; got != "working" {
		t.Fatalf("initial activity=%q, want working", got)
	}
	if err := os.WriteFile(idleMarker, nil, 0o600); err != nil {
		t.Fatal(err)
	}

	// The old inventory sampler served this same structural signature from a
	// two-second cache. Dynamic activity must publish on the next scan instead.
	idle := waitTyped(t, e, time.Now().Add(1500*time.Millisecond), func(typed protocol.Typed) bool {
		d, ok := typed.(protocol.ListDelta)
		return ok && len(d.ChangedSessions) == 1 && d.ChangedSessions[0].Activity == "idle"
	})
	if got := idle.(protocol.ListDelta).ChangedSessions[0].Status; got != "idle" {
		t.Fatalf("idle delta status=%q, want idle", got)
	}
	if err := os.Remove(idleMarker); err != nil {
		t.Fatal(err)
	}
	working := waitTyped(t, e, time.Now().Add(1500*time.Millisecond), func(typed protocol.Typed) bool {
		d, ok := typed.(protocol.ListDelta)
		return ok && len(d.ChangedSessions) == 1 && d.ChangedSessions[0].Activity == "working"
	})
	if got := working.(protocol.ListDelta).ChangedSessions[0].Status; got != "working" {
		t.Fatalf("working delta status=%q, want working", got)
	}
}

func TestRefreshOnOpenListFailureKeepsCache(t *testing.T) {
	fd := &flipDiscoverer{model: testModel()}
	e := startWS(t, Options{
		Token:        "test-token",
		Discoverer:   fd,
		ListInterval: time.Hour,
	})
	e.auth()
	e.sendFrame(&protocol.List{ReqID: 1})
	l1 := mustListing(t, e, 1)
	if len(l1.Workspaces) != 1 || len(l1.Workspaces[0].Sessions) != 2 {
		t.Fatalf("list1 = %+v, want 2 sessions", l1.Workspaces)
	}

	fd.setErr(errors.New("tmux down"))
	e.sendFrame(&protocol.List{ReqID: 2})
	l2 := mustListing(t, e, 2)
	if len(l2.Workspaces) == 0 {
		t.Fatal("refresh failure wiped the listing")
	}
	if len(l2.Workspaces[0].Sessions) != 2 {
		t.Fatalf("refresh failure must keep cache sessions, got %d", len(l2.Workspaces[0].Sessions))
	}
}

func TestRefreshOnOpenLevel2Resubscribe(t *testing.T) {
	md := &mutableDiscoverer{model: &discovery.Model{
		Workspaces: []discovery.Workspace{
			{CWD: "/ws/a", Panes: []discovery.Pane{
				l2Pane("idle keep - grok", "s", "rf-keep", "/ws/a", "/tmp/sock1", "%0", 80, 24),
			}},
		},
	}}
	np := &testNodeprobe{provider: "grok", activity: "idle", health: "normal", session: "s"}
	e := startWS(t, Options{
		Token:           "test-token",
		Discoverer:      md,
		Nodeprobe:       np,
		ListInterval:    time.Hour,
		Level2Interval:  10 * time.Second,
		Level2Heartbeat: time.Hour,
	})
	e.auth()
	e.sendFrame(&protocol.Level2Subscribe{Workspace: "/ws/a"})
	first := waitLevel2Frame(t, e, 2*time.Second)
	if len(first.Sessions) == 0 {
		t.Fatal("first L2 frame empty")
	}

	md.set(&discovery.Model{
		Workspaces: []discovery.Workspace{
			{CWD: "/ws/a", Panes: []discovery.Pane{
				l2Pane("⠋ now working - grok", "s", "rf-keep", "/ws/a", "/tmp/sock1", "%0", 80, 24),
			}},
		},
	})
	np.setActivity("working")
	t0 := time.Now()
	e.sendFrame(&protocol.Level2Subscribe{Workspace: "/ws/a"})
	second := waitLevel2Frame(t, e, 1500*time.Millisecond)
	dt := time.Since(t0)
	if len(second.Sessions) == 0 {
		t.Fatal("resubscribe L2 frame empty")
	}
	if second.Sessions[0].Status != protocol.SessionStatusWorking {
		t.Fatalf("resubscribe status=%q, want working (stale cache?)", second.Sessions[0].Status)
	}
	if dt >= 2*time.Second {
		t.Fatalf("resubscribe waited %s ≥ 2s cadence", dt)
	}
}

func TestRefreshOnOpenLevel2ZeroSubscribersNoPoll(t *testing.T) {
	cd := &countingDiscoverer{model: testModel()}
	e := startWS(t, Options{
		Token:           "test-token",
		Discoverer:      cd,
		ListInterval:    time.Hour,
		Level2Interval:  30 * time.Millisecond,
		Level2Heartbeat: time.Hour,
	})
	e.auth()
	// Auth ACK is not completion of the mandatory initial scan.
	e.sendFrame(&protocol.List{ReqID: 1})
	mustListing(t, e, 1)
	base := cd.scans.Load()
	time.Sleep(120 * time.Millisecond)
	if got := cd.scans.Load(); got > base {
		t.Fatalf("L2 scanned with zero subscribers: %d > %d", got, base)
	}
}

func mustListing(t *testing.T, e *wsEnv, reqID uint32) protocol.Listing {
	t.Helper()
	got := waitTyped(t, e, time.Now().Add(3*time.Second), func(typed protocol.Typed) bool {
		l, ok := typed.(protocol.Listing)
		return ok && l.ReqID == reqID
	})
	return got.(protocol.Listing)
}

func listingNames(l protocol.Listing) map[string]bool {
	out := map[string]bool{}
	for _, w := range l.Workspaces {
		for _, s := range w.Sessions {
			out[s.Name] = true
		}
	}
	return out
}
