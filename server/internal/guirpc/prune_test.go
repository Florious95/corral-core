package guirpc

import (
	"context"
	"fmt"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
)

func retainedTUI(m *Manager, ref, cwd string, created time.Time) *session {
	ctx, cancel := context.WithCancel(m.ctx)
	s := &session{ctx: ctx, cancel: cancel, pane: discovery.Pane{CWD: cwd}, created: created, ready: make(chan struct{}), done: make(chan struct{}), w: newWorker(nil)}
	s.w.setMode(ModeTUI)
	close(s.ready)
	m.sessions[ref] = s
	go func() { <-ctx.Done(); s.w.shutdown(); close(s.done) }()
	return s
}

func TestPruneReleasesClosedTUIBridgeCapacityAndCompletesCleanup(t *testing.T) {
	m := NewManager()
	defer m.Close()
	before := time.Unix(200, 0)
	var retired []*session
	for i := 0; i < maxBridges; i++ {
		retired = append(retired, retainedTUI(m, fmt.Sprint(i), "/one", before.Add(-time.Second)))
	}
	m.Prune("", before, map[string]struct{}{})
	if len(m.sessions) != 0 {
		t.Fatal("closed TUI refs still consume bridge capacity")
	}
	for _, s := range retired {
		if s.ctx.Err() == nil {
			t.Fatal("retired session is still live")
		}
		select {
		case <-s.done:
		default:
			t.Fatal("retirement returned before cleanup")
		}
	}
}

func TestPruneHonorsScopeLiveRefsAndInventoryCutoff(t *testing.T) {
	m := NewManager()
	defer m.Close()
	before := time.Unix(200, 0)
	retainedTUI(m, "gone", "/one", before.Add(-time.Second))
	retainedTUI(m, "live", "/one", before.Add(-time.Second))
	retainedTUI(m, "other-workspace", "/two", before.Add(-time.Second))
	retainedTUI(m, "newer-than-scan", "/one", before.Add(time.Second))
	m.Prune("/one", before, map[string]struct{}{"live": {}})
	for _, ref := range []string{"live", "other-workspace", "newer-than-scan"} {
		if !m.Available(ref) {
			t.Fatalf("prune canceled protected ref %s", ref)
		}
	}
	if m.Available("gone") {
		t.Fatal("prune kept missing scoped ref")
	}
}
