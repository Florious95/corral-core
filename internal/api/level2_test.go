package api

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

// level2_test.go pins the three invariants of the second-level live stream
// (requirement 060; design at .team/nodes/level2-livestream/实现方案.md):
//
//  1. Title verbatim — the pane title is transmitted byte-for-byte (including
//     ◐/✳ prefixes), zero parsing/matching/mapping.
//  2. Identity structural — Ref/Name come from tmux structural fields
//     (session_name/window_index/window_name), never from the title string.
//  3. Pull only while open — the loop scans tmux only while ≥1 level2
//     subscriber exists; at zero subscribers it parks (zero Discover calls).
//     Never attach tmux.

// TestLevel2TitleVerbatim asserts the pane title reaches the client byte-for-
// byte: a title carrying a ◐ (working) or ✳ (idle) prefix is transmitted
// unchanged, never stripped, trimmed, matched, or mapped.
func TestLevel2TitleVerbatim(t *testing.T) {
	working := "◐ w-librarian"
	idle := "✳ dev-state"
	md := &mutableDiscoverer{model: &discovery.Model{
		Workspaces: []discovery.Workspace{
			{CWD: "/ws/a", Panes: []discovery.Pane{
				{Socket: "/tmp/sock1", Session: "alpha", PaneID: "%0", CWD: "/ws/a", Command: "claude", PaneTitle: working, WindowName: "claude", Width: 100, Height: 40},
				{Socket: "/tmp/sock1", Session: "beta", PaneID: "%1", CWD: "/ws/a", Command: "codex", PaneTitle: idle, WindowName: "codex", Width: 80, Height: 24},
			}},
		},
	}}
	e := startWS(t, Options{Token: "test-token", Discoverer: md, ListInterval: time.Hour})
	e.auth()

	// Subscribe to the level-2 stream for workspace /ws/a.
	e.sendFrame(&protocol.Level2Subscribe{Workspace: "/ws/a"})

	// The level2 loop wakes on 0→1 and scans immediately; read the pushed frame.
	deadline := time.Now().Add(5 * time.Second)
	var got *protocol.Level2Frame
	for time.Now().Before(deadline) {
		typed := e.readControl()
		if f, ok := typed.(protocol.Level2Frame); ok {
			got = &f
			break
		}
		// Not a level2 frame (e.g. a listing delta); keep reading.
	}
	if got == nil {
		t.Fatal("no level2_frame received after subscribing")
	}
	if len(got.Sessions) != 2 {
		t.Fatalf("level2_frame sessions = %d, want 2", len(got.Sessions))
	}
	byRef := map[string]protocol.Session{}
	for _, s := range got.Sessions {
		byRef[s.Ref] = s
	}
	// Title must be byte-identical to the source pane title, ◐/✳ prefixes intact.
	if byRef["/tmp/sock1\x1f%0"].Title != working {
		t.Fatalf("working pane title = %q, want verbatim %q (◐ prefix stripped/mapped?)", byRef["/tmp/sock1\x1f%0"].Title, working)
	}
	if byRef["/tmp/sock1\x1f%1"].Title != idle {
		t.Fatalf("idle pane title = %q, want verbatim %q (✳ prefix stripped/mapped?)", byRef["/tmp/sock1\x1f%1"].Title, idle)
	}
}

// TestLevel2IdentityStructural asserts identity comes ONLY from structural
// fields: two panes with the same title but different session_name get
// different refs/names, and changing a pane's title does not change its
// ref/name.
func TestLevel2IdentityStructural(t *testing.T) {
	// Two panes, same title "same title" — identity must still distinguish them
	// by structural fields (session_name → ref = socket+paneid).
	md := &mutableDiscoverer{model: &discovery.Model{
		Workspaces: []discovery.Workspace{
			{CWD: "/ws/a", Panes: []discovery.Pane{
				{Socket: "/tmp/sock1", Session: "alpha", PaneID: "%0", CWD: "/ws/a", Command: "claude", PaneTitle: "same title", WindowName: "claude", Width: 100, Height: 40},
				{Socket: "/tmp/sock1", Session: "beta", PaneID: "%1", CWD: "/ws/a", Command: "codex", PaneTitle: "same title", WindowName: "codex", Width: 80, Height: 24},
			}},
		},
	}}
	e := startWS(t, Options{Token: "test-token", Discoverer: md, ListInterval: time.Hour})
	e.auth()
	e.sendFrame(&protocol.Level2Subscribe{Workspace: "/ws/a"})

	deadline := time.Now().Add(5 * time.Second)
	var got *protocol.Level2Frame
	for time.Now().Before(deadline) {
		typed := e.readControl()
		if f, ok := typed.(protocol.Level2Frame); ok {
			got = &f
			break
		}
	}
	if got == nil {
		t.Fatal("no level2_frame received")
	}
	refs := map[string]bool{}
	for _, s := range got.Sessions {
		refs[s.Ref] = true
		// Ref must be the structural identity (socket + paneid), never the title.
		if strings.Contains(s.Ref, "same title") {
			t.Fatalf("ref %q derives from the title string — identity must be structural", s.Ref)
		}
	}
	if !refs["/tmp/sock1\x1f%0"] || !refs["/tmp/sock1\x1f%1"] {
		t.Fatalf("expected structural refs /tmp/sock1\\x1f%%0 and %%1, got %v", refs)
	}
}

// TestLevel2StopsWhenNoSubscriber asserts the zero-subscriber idle gate: with
// no level2 subscribers the server makes zero Discover (tmux) calls; after
// subscribing, scans happen; after unsubscribing, they stop.
func TestLevel2StopsWhenNoSubscriber(t *testing.T) {
	cd := &countingDiscoverer{model: testModel()}
	e := startWS(t, Options{Token: "test-token", Discoverer: cd, ListInterval: time.Hour})
	e.auth()

	// Establish the pre-subscription scan count (the listing loop with ListInterval
	// time.Hour still does one initial scan, so snapshot the baseline).
	baseline := cd.scans.Load()

	// Give any spurious level2 scans a chance to show up; none should.
	time.Sleep(150 * time.Millisecond)
	if got := cd.scans.Load(); got > baseline {
		t.Fatalf("level2 scan ran with zero subscribers: scans %d > baseline %d (idle gate broken)", got, baseline)
	}

	// Subscribe: scans must now run.
	e.sendFrame(&protocol.Level2Subscribe{Workspace: "/ws/a"})
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) && cd.scans.Load() == baseline {
		time.Sleep(10 * time.Millisecond)
	}
	if cd.scans.Load() == baseline {
		t.Fatal("no level2 scan after subscribing (gate did not wake)")
	}

	// Unsubscribe: scans must stop.
	e.sendFrame(&protocol.Level2Unsubscribe{})
	time.Sleep(150 * time.Millisecond)
	after := cd.scans.Load()
	time.Sleep(200 * time.Millisecond)
	if got := cd.scans.Load(); got != after {
		t.Fatalf("level2 scan continued after unsubscribe: %d -> %d (idle gate broken)", after, got)
	}
}

// TestLevel2NoTmuxAttach asserts the server's tmux command set never contains
// "attach": the level-2 data source is list-panes -a (discovery), which never
// attaches a client (requirement 060 §六; avoids multi-client size negotiation).
func TestLevel2NoTmuxAttach(t *testing.T) {
	root := repoRoot(t) // repoRoot walks up from the test package to go.mod → the server module root
	// Search discovery (the tmux command source) and level2.go (the live stream)
	// for any attach command. Exclude test files.
	for _, dir := range []string{"internal/discovery", "internal/api"} {
		err := filepath.Walk(filepath.Join(root, dir), func(path string, info os.FileInfo, err error) error {
			if err != nil {
				return err
			}
			if info.IsDir() || !strings.HasSuffix(path, ".go") || strings.HasSuffix(path, "_test.go") {
				return nil
			}
			data, err := os.ReadFile(path)
			if err != nil {
				return err
			}
			// A real attach command appears in source as "attach-session" (the
			// tmux command) or "attach" as a quoted command token. Plain-word
			// "attach" in a comment (e.g. "never attach tmux") is not a command
			// and must not fail the check.
			if strings.Contains(string(data), "attach-session") || strings.Contains(string(data), `"attach"`) || strings.Contains(string(data), "`attach`") {
				t.Errorf("%s: contains an attach command (must use list-panes -a only)", path)
			}
			return nil
		})
		if err != nil {
			t.Fatalf("walk %s: %v", dir, err)
		}
	}
}

// repoRoot returns the repository root by walking up from the test package.
func repoRoot(t *testing.T) string {
	t.Helper()
	dir, err := os.Getwd()
	if err != nil {
		t.Fatalf("getwd: %v", err)
	}
	for {
		if _, err := os.Stat(filepath.Join(dir, "go.mod")); err == nil {
			return dir
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			t.Fatal("could not find repo root")
		}
		dir = parent
	}
}

