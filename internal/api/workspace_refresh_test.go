package api

// These are concurrency/protocol tests with scripted discovery and sampling.
// They are NOT real-tmux benchmarks, Android tests, or performance evidence.

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/nodeprobe"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

type workspaceTestSource struct {
	mu           sync.Mutex
	host         *discovery.Model
	models       map[string]*discovery.Model
	hostGate     <-chan struct{}
	gates        map[string]<-chan struct{}
	ignoreCancel map[string]bool
	entered      chan string
	hostCalls    int
}

func newWorkspaceTestSource() *workspaceTestSource {
	return &workspaceTestSource{host: &discovery.Model{}, models: make(map[string]*discovery.Model),
		gates: make(map[string]<-chan struct{}), ignoreCancel: make(map[string]bool), entered: make(chan string, 256)}
}

func (d *workspaceTestSource) Discover(ctx context.Context) (*discovery.Model, error) {
	d.mu.Lock()
	model, gate := d.host, d.hostGate
	d.hostCalls++
	d.mu.Unlock()
	d.entered <- "host"
	if gate != nil {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-gate:
		}
	}
	return model, nil
}

func (d *workspaceTestSource) DiscoverWorkspace(ctx context.Context, cwd string) (*discovery.Model, error) {
	d.mu.Lock()
	model, gate, ignore := d.models[cwd], d.gates[cwd], d.ignoreCancel[cwd]
	d.mu.Unlock()
	d.entered <- cwd
	if gate != nil {
		if ignore {
			<-gate // intentionally bad dependency: exercise late-result rejection
		} else {
			select {
			case <-ctx.Done():
				return nil, ctx.Err()
			case <-gate:
			}
		}
	}
	if model == nil {
		model = &discovery.Model{}
	}
	return model, nil
}

func workspaceFixtureModel(cwd, socket string, panes ...string) *discovery.Model {
	ws := discovery.Workspace{CWD: cwd}
	for _, id := range panes {
		ws.Panes = append(ws.Panes, discovery.Pane{Socket: socket, Session: "agent", PaneID: id,
			CWD: cwd, WindowName: "fixture", Width: 80, Height: 24})
	}
	return &discovery.Model{Workspaces: []discovery.Workspace{ws}}
}

type workspaceTestSampler struct {
	mu    sync.Mutex
	calls []string
}

func (p *workspaceTestSampler) Sample(_ context.Context, socket string) (nodeprobe.Report, error) {
	p.mu.Lock()
	p.calls = append(p.calls, socket)
	p.mu.Unlock()
	r := nodeprobe.Report{SchemaVersion: 1, Socket: socket}
	for i := 0; i < 4; i++ {
		r.Nodes = append(r.Nodes, nodeprobe.Node{Socket: socket, Session: "agent", PaneID: fmt.Sprintf("%%%d", i),
			Provider: "claude", State: "idle", Activity: "idle", Health: "normal"})
	}
	return r, nil
}

func awaitWorkspaceCall(t *testing.T, d *workspaceTestSource, name string) {
	t.Helper()
	deadline := time.NewTimer(3 * time.Second)
	defer deadline.Stop()
	for {
		select {
		case got := <-d.entered:
			if got == name {
				return
			}
		case <-deadline.C:
			t.Fatalf("did not reach %s dependency boundary", name)
		}
	}
}

func readWorkspaceFrame(t *testing.T, e *wsEnv, cwd string) protocol.Level2Frame {
	t.Helper()
	for i := 0; i < 16; i++ {
		f := e.readControl()
		if frame, ok := f.(protocol.Level2Frame); ok {
			if frame.Workspace != cwd {
				t.Fatalf("late workspace frame %q overwrote %q", frame.Workspace, cwd)
			}
			return frame
		}
		if f.FrameType() == protocol.TypeError {
			t.Fatalf("unexpected workspace error: %+v", f)
		}
	}
	t.Fatal("workspace frame not received")
	return protocol.Level2Frame{}
}

func TestWorkspaceRefreshIndependentOfBlockedHostScan(t *testing.T) {
	d := newWorkspaceTestSource()
	gate := make(chan struct{})
	defer close(gate)
	d.hostGate = gate
	d.models["/A"] = workspaceFixtureModel("/A", "/fixture/A", "%0")
	p := &workspaceTestSampler{}
	e := startWS(t, Options{Token: "test-token", Discoverer: d, Nodeprobe: p, ListInterval: time.Hour, Level2Interval: time.Hour})
	e.auth()
	awaitWorkspaceCall(t, d, "host")
	e.sendFrame(&protocol.Level2Subscribe{Workspace: "/A"})
	frame := readWorkspaceFrame(t, e, "/A")
	if len(frame.Sessions) != 1 {
		t.Fatalf("target rows = %d", len(frame.Sessions))
	}
	d.mu.Lock()
	calls := d.hostCalls
	d.mu.Unlock()
	if calls != 1 {
		t.Fatalf("L2 started extra global scans: %d", calls)
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if len(p.calls) != 1 || p.calls[0] != "/fixture/A" {
		t.Fatalf("sampling escaped target workspace: %v", p.calls)
	}
}

func TestWorkspaceRefreshOneBlockedWorkspaceDoesNotOccupyBothWorkers(t *testing.T) {
	d := newWorkspaceTestSource()
	gate := make(chan struct{})
	defer close(gate)
	d.gates["/B"] = gate
	d.models["/A"] = workspaceFixtureModel("/A", "/fixture/A", "%0")
	e := startWS(t, Options{Token: "test-token", Discoverer: d, ListInterval: time.Hour, Level2Interval: time.Hour})
	e.auth()
	other, _ := catalogPeer(t, e, nil)
	other.sendFrame(&protocol.Level2Subscribe{Workspace: "/B"})
	awaitWorkspaceCall(t, d, "/B")
	e.sendFrame(&protocol.Level2Subscribe{Workspace: "/A"})
	if len(readWorkspaceFrame(t, e, "/A").Sessions) != 1 {
		t.Fatal("healthy A was not delivered while B remained blocked")
	}
}

func TestWorkspaceRefreshPostCutoffBurstCoalescesAndDropsLateResult(t *testing.T) {
	d := newWorkspaceTestSource()
	gate := make(chan struct{})
	var release sync.Once
	defer release.Do(func() { close(gate) })
	d.gates["/A"], d.ignoreCancel["/A"] = gate, true
	d.models["/A"] = workspaceFixtureModel("/A", "/fixture/A", "%0")
	e := startWS(t, Options{Token: "test-token", Discoverer: d, ListInterval: time.Hour, Level2Interval: time.Hour})
	e.auth()
	e.srv.trackersMu.Lock()
	var c *wsConn
	for peer := range e.srv.trackers {
		c = peer
	}
	e.srv.trackersMu.Unlock()
	if c == nil {
		t.Fatal("connection missing")
	}
	c.handleLevel2Subscribe(protocol.Level2Subscribe{Workspace: "/A"})
	awaitWorkspaceCall(t, d, "/A")
	d.mu.Lock()
	d.models["/A"] = workspaceFixtureModel("/A", "/fixture/A", "%1")
	d.gates["/A"] = nil
	d.mu.Unlock()
	for i := 0; i < 100; i++ {
		c.handleLevel2Subscribe(protocol.Level2Subscribe{Workspace: "/A"})
	}
	q := e.srv.workspaceScans
	q.mu.Lock()
	if len(q.active) != 1 || len(q.pending) != 1 || len(q.pending["/A"].waiters) != 1 || len(q.order) > 2*workspacePendingLimit {
		q.mu.Unlock()
		t.Fatal("burst was not bounded/coalesced")
	}
	q.mu.Unlock()
	release.Do(func() { close(gate) })
	frame := readWorkspaceFrame(t, e, "/A")
	if len(frame.Sessions) != 1 || frame.Sessions[0].Ref != "/fixture/A\x1f%1" {
		t.Fatalf("pre-cutoff rows were replayed: %+v", frame.Sessions)
	}
}

func TestWorkspaceRefreshSwitchRejectsOldPageAndPreservesNullEmpty(t *testing.T) {
	d := newWorkspaceTestSource()
	gate := make(chan struct{})
	var release sync.Once
	defer release.Do(func() { close(gate) })
	d.gates["/A"], d.ignoreCancel["/A"] = gate, true
	d.models["/A"] = workspaceFixtureModel("/A", "/fixture/A", "%0")
	d.models["/B"] = workspaceFixtureModel("/B", "/fixture/B", "%0")
	e := startWS(t, Options{Token: "test-token", Discoverer: d, ListInterval: time.Hour, Level2Interval: time.Hour})
	e.auth()
	e.sendFrame(&protocol.Level2Subscribe{Workspace: "/A"})
	awaitWorkspaceCall(t, d, "/A")
	e.sendFrame(&protocol.Level2Subscribe{Workspace: "/B"})
	if len(readWorkspaceFrame(t, e, "/B").Sessions) != 1 {
		t.Fatal("B missing")
	}
	release.Do(func() { close(gate) })
	catalogEventually(t, "obsolete A worker did not retire", func() bool {
		q := e.srv.workspaceScans
		q.mu.Lock()
		defer q.mu.Unlock()
		return q.active["/A"] == nil
	})
	d.mu.Lock()
	d.models["/B"] = &discovery.Model{}
	d.mu.Unlock()
	e.sendFrame(&protocol.Level2Subscribe{Workspace: "/B"})
	frame := readWorkspaceFrame(t, e, "/B")
	if len(frame.Sessions) != 0 {
		t.Fatal("deleted last session was retained")
	}
	wire, err := json.Marshal(frame)
	if err != nil || !bytes.Contains(wire, []byte(`"sessions":null`)) {
		t.Fatalf("Go nil-slice compatibility changed: %s (%v)", wire, err)
	}
}

func TestWorkspaceRefreshCatalogProtectsNewRefsAndDeletionFromOlderGlobalScan(t *testing.T) {
	baseModel := workspaceFixtureModel("/A", "/fixture/A", "%0")
	base := newSessionCatalog()
	base.rebuild(baseModel, nil)
	s := &Server{catalog: base, snapshot: buildSnapshot(base)}
	t0 := time.Now().Add(-3 * time.Second)
	s.catalogStarted = t0
	newCatalog := newSessionCatalog()
	newCatalog.rebuild(workspaceFixtureModel("/A", "/fixture/A", "%1"), nil)
	s.publishWorkspaceCatalog("/A", t0.Add(time.Second), newCatalog)
	if s.catalogEntry("/fixture/A\x1f%1") == nil || s.catalogEntry("/fixture/A\x1f%0") != nil {
		t.Fatal("new target refs cannot route independently of host publication")
	}
	s.publishWorkspaceCatalog("/A", t0.Add(2*time.Second), newSessionCatalog())
	s.snapMu.Lock()
	s.pruneWorkspaceCatalogsLocked(t0)
	s.snapMu.Unlock()
	if s.catalogEntry("/fixture/A\x1f%0") != nil || s.catalogEntry("/fixture/A\x1f%1") != nil {
		t.Fatal("older global publication resurrected removed workspace refs")
	}
}

func TestWorkspaceRefreshProductionDefaultActuallyUsesScopedSource(t *testing.T) {
	s := NewServer(Options{DiscoverySocketDirs: []string{}, ListInterval: time.Hour})
	defer s.Close()
	if _, ok := s.discoverer.(*indexedDiscoverer); !ok || s.workspaceScans == nil {
		t.Fatal("production default did not activate the scoped path")
	}
	if err := s.rebuildCatalog(context.Background()); err != nil {
		t.Fatal(err)
	}
	model, err := s.discoverer.(WorkspaceDiscoverer).DiscoverWorkspace(context.Background(), "/unknown")
	if err != nil || len(model.Workspaces) != 0 {
		t.Fatalf("explicit empty isolation boundary widened: model=%v err=%v", model, err)
	}
}

func TestWorkspaceRefreshExpiredAdmissionIsErrorNotEmptySnapshot(t *testing.T) {
	d := newWorkspaceTestSource()
	d.models["/A"] = workspaceFixtureModel("/A", "/fixture/A", "%0")
	e := startWS(t, Options{Token: "test-token", Discoverer: d, ListInterval: time.Hour, Level2Interval: time.Hour})
	e.auth()
	e.sendFrame(&protocol.Level2Subscribe{Workspace: "/A"})
	readWorkspaceFrame(t, e, "/A")
	awaitWorkspaceCall(t, d, "/A")
	gate := make(chan struct{})
	defer close(gate)
	d.mu.Lock()
	d.gates["/A"] = gate
	d.mu.Unlock()
	e.sendFrame(&protocol.Level2Subscribe{Workspace: "/A"})
	awaitWorkspaceCall(t, d, "/A")
	q := e.srv.workspaceScans
	q.mu.Lock()
	job := q.active["/A"]
	if job == nil {
		q.mu.Unlock()
		t.Fatal("target job missing")
	}
	for c, waiter := range job.waiters {
		waiter.deadline = time.Now().Add(-time.Millisecond)
		job.waiters[c] = waiter
	}
	q.mu.Unlock()
	q.wake()
	if f := e.readControl(); f.FrameType() != protocol.TypeError {
		t.Fatalf("expired refresh must not publish an authoritative empty frame: %+v", f)
	}
}
