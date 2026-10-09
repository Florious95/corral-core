package api

// These four reader-boundary cases intentionally compile on unmodified 1fa3.
// They require a real isolated tmux pane and a real authenticated WebSocket.
// A scan gate is never released until the known-ref full SNAPSHOT is judged.
import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/nodeprobe"
	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

type catalogGate struct {
	mu      sync.Mutex
	stage   string
	release chan struct{}
	entered chan string
	calls   map[string]int
	model   *discovery.Model
}

func newCatalogGate(model *discovery.Model) *catalogGate {
	return &catalogGate{model: model, calls: make(map[string]int), entered: make(chan string, 64)}
}
func (g *catalogGate) arm(stage string) {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.stage = stage
	g.release = make(chan struct{})
}
func (g *catalogGate) open() {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.release != nil {
		close(g.release)
		g.release = nil
		g.stage = ""
	}
}
func (g *catalogGate) hit(ctx context.Context, stage string) error {
	g.mu.Lock()
	g.calls[stage]++
	release := g.release
	armed := g.stage == stage
	g.mu.Unlock()
	if !armed {
		return nil
	}
	select {
	case g.entered <- stage:
	case <-ctx.Done():
		return ctx.Err()
	}
	select {
	case <-release:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}
func (g *catalogGate) Discover(ctx context.Context) (*discovery.Model, error) {
	if err := g.hit(ctx, "Discover"); err != nil {
		return nil, err
	}
	g.mu.Lock()
	model := g.model
	g.mu.Unlock()
	return model, nil
}

type catalogGateSampler struct{ g *catalogGate }

func (s catalogGateSampler) Sample(ctx context.Context, socket string) (nodeprobe.Report, error) {
	if err := s.g.hit(ctx, "Sample"); err != nil {
		return nodeprobe.Report{}, err
	}
	report := nodeprobe.Report{SchemaVersion: 1, Socket: socket}
	s.g.mu.Lock()
	model := s.g.model
	s.g.mu.Unlock()
	for _, w := range model.Workspaces {
		for _, p := range w.Panes {
			if p.Socket == socket {
				report.Nodes = append(report.Nodes, nodeprobe.Node{Session: p.Session, WindowIndex: p.WindowIndex, PaneID: p.PaneID, Provider: "codex", Activity: "idle", Health: "unknown"})
			}
		}
	}
	return report, nil
}
func catalogGateEntered(t *testing.T, g *catalogGate, stage string) {
	t.Helper()
	select {
	case got := <-g.entered:
		if got != stage {
			t.Fatalf("gate=%s want=%s", got, stage)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("scan did not enter named gate")
	}
}

func catalogReadSnapshot(t *testing.T, e *wsEnv, ref string, blocked bool) []byte {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	for {
		typ, data, err := e.conn.Read(ctx)
		if err != nil {
			if blocked {
				t.Fatalf("catalog gate blocked known-ref SNAPSHOT: %v", err)
			}
			t.Fatalf("initial fixture SNAPSHOT unavailable: %v", err)
		}
		if typ == websocket.MessageText {
			f, err := protocol.UnmarshalFrame(data)
			if err != nil {
				t.Fatal(err)
			}
			if f.FrameType() == protocol.TypeError {
				t.Fatalf("error instead of SNAPSHOT: %+v", f)
			}
			continue
		}
		frame, err := protocol.DecodeBinary(data)
		if err != nil {
			t.Fatal(err)
		}
		if frame.Kind == protocol.KindSnapshot && frame.Ref == ref {
			return frame.Data
		}
	}
}

func catalogReaderBoundary(t *testing.T, level2 bool, stage string, coldSubscribe bool) {
	// Hosted only execution: TMPDIR is isolated by the workflow. Byte length,
	// precreation and socket_path self-proof precede any pane operation.
	dir, err := os.MkdirTemp("", "p15-")
	if err != nil {
		t.Fatal(err)
	}
	sock := filepath.Join(dir, "s")
	if len([]byte(sock)) >= 104 {
		_ = os.Remove(dir)
		t.Fatal("private socket path exceeds bound")
	}
	env := scrubbedEnv()
	out, err := runTmuxCmd(env, sock, "-f", "/dev/null", "new-session", "-d", "-x", "80", "-y", "24", "-s", "p15", "printf '\033[2J\033[H\033[1;34mPERF15_MARK\033[0m\033[3;5HSTATIC\033[7;10H'; exec sleep 300")
	if err != nil {
		t.Fatalf("private fixture creation: %v %s", err, out)
	}
	actual, err := runTmuxCmd(env, sock, "list-sessions", "-F", "#{socket_path}")
	if err != nil || strings.TrimSpace(actual) != sock {
		t.Fatal("private fixture socket self-proof failed; no fallback permitted")
	}
	// Faults occur after real socket proof and before Go cleanup registration.
	switch os.Getenv("PERF15_FIXTURE_FAULT") {
	case "go-timeout", "outer-timeout":
		t.Log("PERF15_OWNED_FIXTURE_READY")
		select {}
	case "fixture-start":
		t.Fatal("intentional fixture-start failure")
	}
	t.Cleanup(func() {
		actual, err := runTmuxCmd(env, sock, "list-sessions", "-F", "#{socket_path}")
		if err != nil || strings.TrimSpace(actual) != sock {
			t.Error("cleanup socket proof failed")
			return
		}
		if out, err := runTmuxCmd(env, sock, "kill-server"); err != nil {
			t.Errorf("fixture cleanup: %v %s", err, out)
		}
		// Remove only this fixture's entries, never a shared socket root.
		_ = os.Remove(sock)
		_ = os.Remove(dir)
	})
	pane, err := runTmuxCmd(env, sock, "list-panes", "-t", "p15", "-F", "#{pane_id}")
	if err != nil {
		t.Fatal(err)
	}
	ref := sock + "\x1f" + strings.TrimSpace(pane)
	m := &discovery.Model{Workspaces: []discovery.Workspace{{CWD: "/p15", Panes: []discovery.Pane{{Socket: sock, Session: "p15", PaneID: strings.TrimSpace(pane), CWD: "/p15", PaneTitle: "p15", Width: 80, Height: 24}}}}}
	g := newCatalogGate(m)
	e := startWS(t, Options{Token: "test-token", Discoverer: g, Nodeprobe: catalogGateSampler{g}, ListInterval: time.Hour, Level2Interval: time.Hour})
	// Consume auth's initial scan before admitting the test List; neither auth
	// initialization nor cold Subscribe is the gate under test.
	g.arm("Discover")
	e.auth()
	catalogGateEntered(t, g, "Discover")
	if coldSubscribe {
		// The ref is valid because it names the owned tmux socket and pane, but
		// the first host catalog is deliberately still blocked. Subscribe must
		// route directly to that pane instead of waiting for unrelated scans.
		e.sendFrame(&protocol.Subscribe{Ref: ref, Cols: 80, Rows: 24})
		got := catalogReadSnapshot(t, e, ref, true)
		if len(got) == 0 {
			t.Fatal("cold direct subscribe returned an empty snapshot")
		}
		g.mu.Lock()
		held := g.release != nil
		g.mu.Unlock()
		if !held {
			t.Fatal("cold subscribe unexpectedly released the catalog gate")
		}
		g.open()
		return
	}
	g.open()
	e.sendFrame(&protocol.List{ReqID: 1})
	mustListing(t, e, 1)
	// The source is static. Read actual pane metadata/body only in this owned
	// fixture, until its complete marker reaches tmux; this is not product input.
	deadline := time.Now().Add(3 * time.Second)
	for {
		text, err := runTmuxCmd(env, sock, "capture-pane", "-p", "-e", "-t", strings.TrimSpace(pane))
		if err != nil {
			t.Fatal(err)
		}
		if strings.Contains(text, "PERF15_MARK") && strings.Contains(text, "STATIC") {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("static fixture source not ready")
		}
		time.Sleep(10 * time.Millisecond)
	}
	e.sendFrame(&protocol.Subscribe{Ref: ref, Cols: 80, Rows: 24})
	baseline := catalogReadSnapshot(t, e, ref, false)
	if !bytes.Contains(baseline, []byte("PERF15_MARK")) || !regexp.MustCompile(`\x1b\[[0-9;]*34[0-9;]*m`).Match(baseline) || !bytes.HasSuffix(baseline, []byte("\x1b[7;10H")) {
		t.Fatalf("static full marker/style/cursor oracle absent: %q", baseline)
	}
	g.arm(stage)
	defer g.open()
	if level2 {
		e.sendFrame(&protocol.Level2Subscribe{Workspace: "/p15"})
	} else {
		e.sendFrame(&protocol.List{ReqID: 2})
	}
	catalogGateEntered(t, g, stage)
	e.sendFrame(&protocol.Subscribe{Ref: ref, Cols: 80, Rows: 24})
	got := catalogReadSnapshot(t, e, ref, true)
	if !bytes.Equal(got, baseline) {
		t.Fatalf("full snapshot/style/cursor changed under %s gate: got=%q want=%q", stage, got, baseline)
	}
	g.mu.Lock()
	held := g.release != nil
	g.mu.Unlock()
	if !held {
		t.Fatal("scan released before full SNAPSHOT")
	}
	t.Logf("same-connection ref=%q stage=%s full_snapshot_bytes=%d gate_still_held=true", ref, stage, len(got))
}
func TestListScanBlockedKnownSubscribeGetsSnapshot(t *testing.T) {
	for _, stage := range []string{"Discover", "Sample"} {
		t.Run(stage, func(t *testing.T) { catalogReaderBoundary(t, false, stage, false) })
	}
}
func TestLevel2ScanBlockedKnownSubscribeGetsSnapshot(t *testing.T) {
	for _, stage := range []string{"Discover", "Sample"} {
		t.Run(stage, func(t *testing.T) { catalogReaderBoundary(t, true, stage, false) })
	}
}

func TestColdSubscribeBypassesBlockedInitialCatalog(t *testing.T) {
	catalogReaderBoundary(t, false, "Discover", true)
}
