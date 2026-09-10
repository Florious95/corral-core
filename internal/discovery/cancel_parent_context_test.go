package discovery

import (
	"context"
	"errors"
	"sync"
	"testing"
)

// TestDiscoverParentContextCancelDoesNotPoisonLiveSocket uses a real isolated
// tmux server and the scanServer test seam to cancel only the first scan. The
// socket remains live, so a fresh discovery must not inherit the transient
// parent-cancellation failure as a stale-socket verdict.
func TestDiscoverParentContextCancelDoesNotPoisonLiveSocket(t *testing.T) {
	resetStaleCache()
	t.Cleanup(func() {
		scanServerHook = nil
		resetStaleCache()
	})

	root := testSocketRoot(t)
	tmp := t.TempDir()
	cwd := mkdirTmp(t, tmp, "ws-cancel")
	sock := startTestServer(t, root, "live", cwd, "-s", "live")

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	var once sync.Once
	scanServerHook = func(path string) {
		if path == sock {
			once.Do(cancel)
		}
	}

	first, firstErr := DiscoverWithDirs(ctx, discardLogger(), []string{testSocketDir(t, root)})
	// A parent cancellation is an operation-level error, not an unreachable
	// socket. The fixed contract returns (nil, context.Canceled). Keep these
	// checks non-fatal so the old implementation still reaches the immediate
	// fresh discovery below and exposes both regressions in one run.
	if !errors.Is(firstErr, context.Canceled) {
		t.Errorf("first DiscoverWithDirs error = %v, want context.Canceled", firstErr)
	}
	if ctx.Err() != context.Canceled {
		t.Errorf("controlled scan cancellation did not reach parent context: %v", ctx.Err())
	}
	if first != nil {
		t.Errorf("canceled generation returned a non-nil model: %+v", first.Workspaces)
	}

	// The tmux server is still alive. Disable only the one-shot cancellation
	// seam, then perform an immediate fresh discovery without waiting staleTTL.
	scanServerHook = nil
	second, err := DiscoverWithDirs(context.Background(), discardLogger(), []string{testSocketDir(t, root)})
	if err != nil {
		t.Fatalf("fresh DiscoverWithDirs: %v", err)
	}
	if len(second.Workspaces) != 1 || second.Workspaces[0].Count() != 1 {
		t.Fatalf("live socket disappeared after parent cancellation: socket=%q second=%+v", sock, second.Workspaces)
	}
	pane := second.Workspaces[0].Panes[0]
	if pane.Socket != sock {
		t.Errorf("fresh discovery pane socket = %q, want live socket %q", pane.Socket, sock)
	}
	if pane.PaneID == "" {
		t.Errorf("fresh discovery returned an empty pane ID for live socket %q", sock)
	}
}
