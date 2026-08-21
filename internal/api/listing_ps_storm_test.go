package api

import (
	"context"
	"fmt"
	"os"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

// TestListingTickFullTablePsForkAtMostOnce is the 095 prior-red probe:
// one listing tick over N sessions must fork full-table ps at most once.
// Unfixed main calls IdentifySet once then Identify per session → N+1.
func TestListingTickFullTablePsForkAtMostOnce(t *testing.T) {
	const n = 10
	panes := make([]discovery.Pane, n)
	for i := 0; i < n; i++ {
		panes[i] = discovery.Pane{
			Socket:     "/tmp/lsfix-sock",
			Session:    fmt.Sprintf("s%d", i),
			PaneID:     fmt.Sprintf("%%%d", i),
			CWD:        "/ws/lsfix",
			Command:    "node",
			WindowName: fmt.Sprintf("w%d", i),
			PanePID:    80_000_000 + i,
			Width:      80,
			Height:     24,
		}
	}
	md := &mutableDiscoverer{model: &discovery.Model{
		Workspaces: []discovery.Workspace{{CWD: "/ws/lsfix", Panes: panes}},
	}}
	s := NewServer(Options{
		Token:        "test-token",
		Discoverer:   md,
		ListInterval: time.Hour,
		// Claim every pane so filterModel keeps all N; Identify still
		// goes through procFinder (the production buildSnapshot shape).
		ProviderFinder: claimingProcFinder{inner: newProcFinder()},
	})
	t.Cleanup(func() { s.Close() })

	procTableReads.Store(0)
	if err := s.rebuildCatalog(context.Background()); err != nil {
		t.Fatalf("rebuildCatalog: %v", err)
	}
	got := procTableReads.Load()
	if got > 1 {
		t.Fatalf("listing tick full-table ps forks=%d want<=1 (unfixed shape is N+1=%d)", got, n+1)
	}
}

// TestHandleListDoesNotBlockReadLoop is the 095 handleList prior-red:
// a List refresh that blocks in Discover must not stall later frames on
// the same connection's readLoop (subscribe / input sit unread).
func TestHandleListDoesNotBlockReadLoop(t *testing.T) {
	gd := &gatedDiscoverer{
		model:   testModel(),
		entered: make(chan struct{}, 1),
		release: make(chan struct{}),
	}
	e := startWS(t, Options{
		Token:          "test-token",
		Discoverer:     gd,
		ListInterval:   time.Hour,
		ProviderFinder: staticProvider("claude_code"),
	})
	e.auth()
	e.sendFrame(&protocol.List{ReqID: 1})
	_ = mustListing(t, e, 1)

	gd.block.Store(true)
	e.sendFrame(&protocol.List{ReqID: 2})
	select {
	case <-gd.entered:
	case <-time.After(2 * time.Second):
		t.Fatal("list refresh never entered Discover")
	}

	e.sendFrame(&protocol.Input{ReqID: 99, Ref: "no-such-ref", Text: "x"})
	ack := waitTyped(t, e, time.Now().Add(400*time.Millisecond), func(typed protocol.Typed) bool {
		_, ok := typed.(protocol.InputAck)
		return ok
	})
	ia := ack.(protocol.InputAck)
	if ia.ReqID != 99 {
		t.Fatalf("input_ack req_id=%d want 99 (readLoop stalled behind list?)", ia.ReqID)
	}

	close(gd.release)
	_ = mustListing(t, e, 2)
}

func TestProcFinderIdentifyDoesNotInvalidateSetCache(t *testing.T) {
	f := newProcFinder()
	pid := os.Getpid()
	ppid := os.Getppid()
	procTableReads.Store(0)
	_ = f.IdentifySet([]int{pid, ppid})
	if got := procTableReads.Load(); got != 1 {
		t.Fatalf("IdentifySet forks=%d want 1", got)
	}
	_ = f.Identify(pid)
	_ = f.Identify(ppid)
	if got := procTableReads.Load(); got != 1 {
		t.Fatalf("Identify after IdentifySet forks=%d want 1 (single-pid key overwrote the table)", got)
	}
}

func TestProcFinderIdentityCacheKeyedByPidStarttime(t *testing.T) {
	f := newProcFinder()
	pid := os.Getpid()
	_ = f.Identify(pid)
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.snap == nil || f.snap.start[pid] == "" {
		t.Fatalf("ps snapshot missing starttime for pid %d", pid)
	}
	e, ok := f.ident[pid]
	if !ok || e.start == "" || e.start != f.snap.start[pid] {
		t.Fatalf("ident cache not keyed by (pid,starttime): ok=%v entry=%+v snap.start=%q", ok, e, f.snap.start[pid])
	}
}

// claimingProcFinder is the production call shape with a guaranteed hit:
// IdentifySet + Identify both reach procFinder (full-table ps), but every
// pid is claimed so filterModel cannot drop the N sessions before tagging.
type claimingProcFinder struct{ inner *procFinder }

func (c claimingProcFinder) Identify(pid int) string {
	_ = c.inner.Identify(pid)
	return "claude_code"
}

func (c claimingProcFinder) IdentifySet(pids []int) map[int]string {
	_ = c.inner.IdentifySet(pids)
	out := make(map[int]string, len(pids))
	for _, pid := range pids {
		if pid > 0 {
			out[pid] = "claude_code"
		}
	}
	return out
}

type gatedDiscoverer struct {
	mu      sync.Mutex
	model   *discovery.Model
	block   atomic.Bool
	entered chan struct{}
	release chan struct{}
}

func (d *gatedDiscoverer) Discover(ctx context.Context) (*discovery.Model, error) {
	if d.block.Load() {
		select {
		case d.entered <- struct{}{}:
		default:
		}
		select {
		case <-d.release:
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.model, nil
}
