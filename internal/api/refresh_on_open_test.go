package api

import (
	"errors"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
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
		Token:          "test-token",
		Discoverer:     md,
		ListInterval:   time.Hour,
		ProviderFinder: staticProvider("claude_code"),
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

func TestRefreshOnOpenListFailureKeepsCache(t *testing.T) {
	fd := &flipDiscoverer{model: testModel()}
	e := startWS(t, Options{
		Token:          "test-token",
		Discoverer:     fd,
		ListInterval:   time.Hour,
		ProviderFinder: staticProvider("claude_code"),
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
	e := startWS(t, Options{
		Token:           "test-token",
		Discoverer:      md,
		ListInterval:    time.Hour,
		Level2Interval:  10 * time.Second,
		Level2Heartbeat: time.Hour,
		ProviderFinder:  staticProvider("grok"),
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
		ProviderFinder:  staticProvider("claude_code"),
	})
	e.auth()
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
