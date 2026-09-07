package api

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

// level2_test.go pins requirement 061: structural identity, unknown glyphs
// stay unknown (and log codepoint + full title), zero-subscriber idle gate,
// push-on-change, and low-frequency heartbeat.

func waitTyped(t *testing.T, e *wsEnv, deadline time.Time, want func(protocol.Typed) bool) protocol.Typed {
	t.Helper()
	for time.Now().Before(deadline) {
		remain := time.Until(deadline)
		if remain <= 0 {
			break
		}
		ctx, cancel := context.WithTimeout(context.Background(), remain)
		typ, data, err := e.conn.Read(ctx)
		cancel()
		if err != nil {
			continue
		}
		if typ != websocket.MessageText {
			continue
		}
		typed, err := protocol.UnmarshalFrame(data)
		if err != nil {
			t.Fatalf("decode frame %q: %v", data, err)
		}
		if want(typed) {
			return typed
		}
	}
	t.Fatal("timed out waiting for expected frame")
	return nil
}

func waitLevel2Frame(t *testing.T, e *wsEnv, d time.Duration) protocol.Level2Frame {
	t.Helper()
	got := waitTyped(t, e, time.Now().Add(d), func(typed protocol.Typed) bool {
		_, ok := typed.(protocol.Level2Frame)
		return ok
	})
	return got.(protocol.Level2Frame)
}

func l2Pane(title, session, window, cwd, sock, paneID string, w, h int) discovery.Pane {
	return discovery.Pane{
		Socket:     sock,
		Session:    session,
		PaneID:     paneID,
		CWD:        cwd,
		Command:    "claude",
		PaneTitle:  title,
		WindowName: window,
		Width:      w,
		Height:     h,
	}
}

func TestL2StructuralFields(t *testing.T) {
	title := "◐ w-librarian"
	md := &mutableDiscoverer{model: &discovery.Model{
		Workspaces: []discovery.Workspace{
			{CWD: "/ws/a", Panes: []discovery.Pane{
				l2Pane(title, "alpha", "claude", "/ws/a", "/tmp/sock1", "%0", 100, 40),
			}},
		},
	}}
	e := startWS(t, Options{
		Token:           "test-token",
		Discoverer:      md,
		Nodeprobe:       &testNodeprobe{provider: "claude", activity: "working", health: "normal"},
		ListInterval:    time.Hour,
		Level2Interval:  30 * time.Millisecond,
		Level2Heartbeat: time.Hour,
	})
	e.auth()
	e.sendFrame(&protocol.Level2Subscribe{Workspace: "/ws/a"})
	got := waitLevel2Frame(t, e, 5*time.Second)
	if len(got.Sessions) != 1 {
		t.Fatalf("sessions = %d, want 1", len(got.Sessions))
	}
	s := got.Sessions[0]
	wantRef := "/tmp/sock1\x1f%0"
	if s.Ref != wantRef {
		t.Fatalf("ref = %q, want structural %q", s.Ref, wantRef)
	}
	if s.Name != "claude" {
		t.Fatalf("name = %q, want window_name %q", s.Name, "claude")
	}
	if s.Cwd != "/ws/a" {
		t.Fatalf("cwd = %q, want pane_current_path /ws/a", s.Cwd)
	}
	if s.WindowName != "claude" || s.WindowIndex != "0" {
		t.Fatalf("structural window fields = %q/%q, want claude/0", s.WindowName, s.WindowIndex)
	}
	if s.Title != title {
		t.Fatalf("title = %q, want verbatim %q", s.Title, title)
	}
	if strings.Contains(s.Ref, title) || strings.Contains(s.Ref, "w-librarian") {
		t.Fatalf("ref %q derived from title — identity must be structural", s.Ref)
	}
	if s.Status != protocol.SessionStatusWorking {
		t.Fatalf("status = %q, want working (◐)", s.Status)
	}
}

func TestL2UnknownGlyphStaysUnknown(t *testing.T) {
	title := "?foo"
	md := &mutableDiscoverer{model: &discovery.Model{
		Workspaces: []discovery.Workspace{
			{CWD: "/ws/a", Panes: []discovery.Pane{
				l2Pane(title, "alpha", "claude", "/ws/a", "/tmp/sock1", "%0", 80, 24),
			}},
		},
	}}
	e := startWS(t, Options{
		Token:           "test-token",
		Discoverer:      md,
		ListInterval:    time.Hour,
		Level2Interval:  30 * time.Millisecond,
		Level2Heartbeat: time.Hour,
	})
	e.auth()
	e.sendFrame(&protocol.Level2Subscribe{Workspace: "/ws/a"})
	got := waitLevel2Frame(t, e, 5*time.Second)
	if len(got.Sessions) != 1 {
		t.Fatalf("sessions = %d, want 1", len(got.Sessions))
	}
	st := got.Sessions[0].Status
	if st != protocol.SessionStatusUnknown {
		t.Fatalf("status = %q, want unknown (must not fall back to idle)", st)
	}
	if st == protocol.SessionStatusIdle {
		t.Fatal("unknown glyph fell back to idle")
	}
}

func TestL2UnknownProviderIsRetainedWithoutTitleInference(t *testing.T) {
	title := "?l2-unknown-provider"
	md := &mutableDiscoverer{model: &discovery.Model{Workspaces: []discovery.Workspace{{CWD: "/ws/a", Panes: []discovery.Pane{
		l2Pane(title, "alpha", "claude", "/ws/a", "/tmp/sock1", "%0", 80, 24),
	}}}}}
	e := startWS(t, Options{Token: "test-token", Discoverer: md, ListInterval: time.Hour})
	e.auth()
	e.sendFrame(&protocol.Level2Subscribe{Workspace: "/ws/a"})
	got := waitLevel2Frame(t, e, 5*time.Second)
	if len(got.Sessions) != 1 || got.Sessions[0].Provider != "unknown" || got.Sessions[0].Activity != "unknown" {
		t.Fatalf("accepted unknown row was filtered or inferred: %+v", got.Sessions)
	}
	if got.Sessions[0].Title != title {
		t.Fatalf("title changed: %q", got.Sessions[0].Title)
	}
}

func TestL2NoPollWithoutSubscriber(t *testing.T) {
	cd := &countingDiscoverer{model: testModel()}
	np := &testNodeprobe{}
	e := startWS(t, Options{
		Token:           "test-token",
		Discoverer:      cd,
		Nodeprobe:       np,
		ListInterval:    time.Hour,
		Level2Interval:  30 * time.Millisecond,
		Level2Heartbeat: time.Hour,
	})
	e.auth()

	waitFor := func(what string, condition func() bool) {
		t.Helper()
		deadline := time.Now().Add(5 * time.Second)
		for !condition() && time.Now().Before(deadline) {
			time.Sleep(5 * time.Millisecond)
		}
		if !condition() {
			t.Fatalf("timed out waiting for %s: level2=%d scans=%d nodeprobe=%d", what, e.srv.countLevel2(), cd.scans.Load(), np.count())
		}
	}
	stableCounters := func() (int64, int) {
		t.Helper()
		deadline := time.Now().Add(5 * time.Second)
		lastScans, lastProbe := cd.scans.Load(), np.count()
		stable := 0
		for time.Now().Before(deadline) {
			time.Sleep(e.srv.level2Interval)
			scans, probes := cd.scans.Load(), np.count()
			if scans == lastScans && probes == lastProbe {
				stable++
				if stable == 2 {
					return scans, probes
				}
			} else {
				stable = 0
				lastScans, lastProbe = scans, probes
			}
		}
		t.Fatalf("counters did not settle after unsubscribe: scans=%d nodeprobe=%d", lastScans, lastProbe)
		return 0, 0
	}

	if got := e.srv.countLevel2(); got != 0 {
		t.Fatalf("level2 subscribers after auth = %d, want 0", got)
	}
	// Auth ack is not a listing completion barrier. Settle the mandatory
	// auth-triggered listing sample before attributing later work to level2.
	waitFor("auth listing sample", func() bool { return cd.scans.Load() >= 1 && np.count() >= 1 })
	baseline, baselineProbe := cd.scans.Load(), np.count()
	time.Sleep(5 * e.srv.level2Interval)
	if got := cd.scans.Load(); got != baseline {
		t.Fatalf("level2 scan ran with zero subscribers: scans %d != baseline %d (idle gate broken)", got, baseline)
	}
	if got := np.count(); got != baselineProbe {
		t.Fatalf("nodeprobe ran with zero level2 subscribers: %d -> %d", baselineProbe, got)
	}

	e.sendFrame(&protocol.Level2Subscribe{Workspace: "/ws/a"})
	waitFor("level2 subscribe state and positive-control sample", func() bool {
		return e.srv.countLevel2() == 1 && cd.scans.Load() > baseline && np.count() > baselineProbe
	})

	e.sendFrame(&protocol.Level2Unsubscribe{Workspace: "/ws/a"})
	waitFor("level2 unsubscribe state", func() bool { return e.srv.countLevel2() == 0 })
	after, afterProbe := stableCounters()
	// Frozen counterexample tooth: removing either zero-subscriber production
	// guard makes both counters grow in this post-barrier observation window.
	time.Sleep(5 * e.srv.level2Interval)
	if got := cd.scans.Load(); got != after {
		t.Fatalf("level2 scan continued after unsubscribe: %d -> %d (idle gate broken)", after, got)
	}
	if got := np.count(); got != afterProbe {
		t.Fatalf("nodeprobe continued after unsubscribe: %d -> %d", afterProbe, got)
	}
}

func TestL2PushOnChangeOnly(t *testing.T) {
	md := &mutableDiscoverer{model: &discovery.Model{
		Workspaces: []discovery.Workspace{
			{CWD: "/ws/a", Panes: []discovery.Pane{
				l2Pane("◐ before", "alpha", "claude", "/ws/a", "/tmp/sock1", "%0", 80, 24),
			}},
		},
	}}
	np := &testNodeprobe{provider: "pi", activity: "working", health: "normal"}
	e := startWS(t, Options{
		Token:           "test-token",
		Discoverer:      md,
		Nodeprobe:       np,
		ListInterval:    time.Hour,
		Level2Interval:  30 * time.Millisecond,
		Level2Heartbeat: time.Hour,
	})
	e.auth()
	e.sendFrame(&protocol.Level2Subscribe{Workspace: "/ws/a"})
	first := waitLevel2Frame(t, e, 5*time.Second)
	if first.Sessions[0].Title != "◐ before" || first.Sessions[0].Status != protocol.SessionStatusWorking {
		t.Fatalf("first frame = title %q status %q", first.Sessions[0].Title, first.Sessions[0].Status)
	}

	// Leave the socket unread while several identical scans run. If the
	// server re-pushed the same snapshot, those frames sit in the buffer
	// and the next read is still "◐ before" instead of the changed row.
	time.Sleep(150 * time.Millisecond)

	np.setActivity("idle")
	md.set(&discovery.Model{
		Workspaces: []discovery.Workspace{
			{CWD: "/ws/a", Panes: []discovery.Pane{
				l2Pane("✳ after", "alpha", "claude", "/ws/a", "/tmp/sock1", "%0", 80, 24),
			}},
		},
	})
	second := waitLevel2Frame(t, e, 5*time.Second)
	if second.Sessions[0].Title != "✳ after" {
		t.Fatalf("second title = %q, want ✳ after (unchanged snapshot was re-pushed?)", second.Sessions[0].Title)
	}
	if second.Sessions[0].Status != protocol.SessionStatusIdle {
		t.Fatalf("second status = %q, want idle", second.Sessions[0].Status)
	}
}

func TestL2Heartbeat(t *testing.T) {
	md := &mutableDiscoverer{model: &discovery.Model{
		Workspaces: []discovery.Workspace{
			{CWD: "/ws/a", Panes: []discovery.Pane{
				l2Pane("◐ hold", "alpha", "claude", "/ws/a", "/tmp/sock1", "%0", 80, 24),
			}},
		},
	}}
	e := startWS(t, Options{
		Token:           "test-token",
		Discoverer:      md,
		ListInterval:    time.Hour,
		Level2Interval:  20 * time.Millisecond,
		Level2Heartbeat: 80 * time.Millisecond,
	})
	e.auth()
	e.sendFrame(&protocol.Level2Subscribe{Workspace: "/ws/a"})
	_ = waitLevel2Frame(t, e, 5*time.Second)

	got := waitTyped(t, e, time.Now().Add(2*time.Second), func(typed protocol.Typed) bool {
		_, ok := typed.(protocol.Level2Heartbeat)
		return ok
	})
	hb := got.(protocol.Level2Heartbeat)
	if hb.Workspace != "/ws/a" {
		t.Fatalf("heartbeat workspace = %q, want /ws/a", hb.Workspace)
	}
	if hb.Seq < 1 {
		t.Fatalf("heartbeat seq = %d, want >= 1", hb.Seq)
	}
}

// TestLevel2TitleVerbatim keeps the 060 title-passthrough pin: prefixes stay
// byte-identical on the wire (061 still transmits title verbatim).
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
	e.sendFrame(&protocol.Level2Subscribe{Workspace: "/ws/a"})
	got := waitLevel2Frame(t, e, 5*time.Second)
	if len(got.Sessions) != 2 {
		t.Fatalf("level2_frame sessions = %d, want 2", len(got.Sessions))
	}
	byRef := map[string]protocol.Session{}
	for _, s := range got.Sessions {
		byRef[s.Ref] = s
	}
	if byRef["/tmp/sock1\x1f%0"].Title != working {
		t.Fatalf("working pane title = %q, want verbatim %q", byRef["/tmp/sock1\x1f%0"].Title, working)
	}
	if byRef["/tmp/sock1\x1f%1"].Title != idle {
		t.Fatalf("idle pane title = %q, want verbatim %q", byRef["/tmp/sock1\x1f%1"].Title, idle)
	}
}

func TestLevel2IdentityStructural(t *testing.T) {
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
	got := waitLevel2Frame(t, e, 5*time.Second)
	refs := map[string]bool{}
	for _, s := range got.Sessions {
		refs[s.Ref] = true
		if strings.Contains(s.Ref, "same title") {
			t.Fatalf("ref %q derives from the title string — identity must be structural", s.Ref)
		}
	}
	if !refs["/tmp/sock1\x1f%0"] || !refs["/tmp/sock1\x1f%1"] {
		t.Fatalf("expected structural refs /tmp/sock1\\x1f%%0 and %%1, got %v", refs)
	}
}

func TestLevel2StopsWhenNoSubscriber(t *testing.T) {
	TestL2NoPollWithoutSubscriber(t)
}

func TestLevel2NoTmuxAttach(t *testing.T) {
	root := repoRoot(t)
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
