package api

// state_wiring_test.go — red-first integration spec for the state-wiring
// assembly (task fix-state-wiring, defect D-1). Before the wiring, the
// StateProvider seam defaulted to always-unknown, so every session rendered
// grey and the 012 aggregate had nothing ranked to work on. These tests pin
// the wiring end to end:
//
//   - a wrapper-shaped fake claude process tree (bash → claude descendant) in
//     an ISOLATED tmux, sampled through the REAL provider (real ps + real
//     capture-pane), must resolve state≠unknown in listing and drive the 012
//     aggregate;
//   - the pre-fix default (no provider) must still render all-unknown, so the
//     wiring test is not vacuously green;
//   - the provider's cache must serve State() without blocking on IO and
//     degrade to unknown on sample failure (requirement 008 isolation law).

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

// startWiredEnv starts an isolated tmux whose pane runs a wrapper-shaped fake
// claude process tree (bash pane → claude-named descendant) and prints the
// blocked permission box on screen — the exact two signals the pipeline needs:
// the tree for identify, the screen text for the blocked rule. It returns the
// tmux env plus the real pane_pid resolved from the live pane.
func startWiredEnv(t *testing.T, paneCmd string) (*tmuxEnv, int) {
	t.Helper()
	dir, err := os.MkdirTemp("", "wsapi-state")
	if err != nil {
		t.Fatalf("MkdirTemp: %v", err)
	}
	sock := filepath.Join(dir, "sock")
	env := scrubbedEnv()

	name := fmt.Sprintf("wsstate%d", tmuxSeq)
	// The pane command is the wrapper shape: bash (pane_pid) forks a child
	// that execs a claude-named binary, so the process-tree descent in
	// Identify finds a claude descendant below a bash root (state-ident-wrapper
	// §5 real shape). The blocked marker is printed at startup so capture-pane
	// samples it.
	if out, err := runTmuxCmd(env, sock, "new-session", "-d", "-x", "80", "-y", "24", "-s", name, "-c", t.TempDir(), paneCmd); err != nil {
		t.Fatalf("new-session: %v\n%s", err, out)
	}
	t.Cleanup(func() {
		_, _ = runTmuxCmd(env, sock, "kill-server")
		_ = os.RemoveAll(dir)
	})

	// Resolve the bare pane id (the exact-existence-check form).
	out, err := runTmuxCmd(env, sock, "list-panes", "-t", name, "-F", "#{pane_id}|#{pane_pid}")
	if err != nil {
		t.Fatalf("resolve pane: %v\n%s", err, out)
	}
	parts := strings.SplitN(strings.TrimSpace(out), "|", 2)
	if len(parts) != 2 {
		t.Fatalf("unexpected list-panes output %q", out)
	}
	paneID, pid := parts[0], parts[1]
	panePID := 0
	if _, err := fmt.Sscanf(pid, "%d", &panePID); err != nil {
		t.Fatalf("parse pane_pid %q: %v", pid, err)
	}

	// Cleanup: the fake claude process tree must not outlive the test (process
	// hygiene). kill-server tears the session down (SIGHUP to pane processes);
	// best-effort kill of the pane tree too so a sleeping fake claude can never
	// leak. Scoped strictly to our own pane root's descendants — never a broad
	// pkill.
	t.Cleanup(func() {
		if panePID > 0 {
			for _, pid := range descendantPIDs(panePID) {
				_ = exec.Command("kill", "-9", fmt.Sprint(pid)).Run()
			}
		}
	})

	return &tmuxEnv{t: t, sock: sock, paneID: paneID, env: env}, panePID
}

// descendantPIDs returns the given pid and every process whose ancestor chain
// reaches it, from one bounded ps snapshot. Used only to reap a test's own
// fake process tree — never a broad match.
func descendantPIDs(root int) []int {
	out, err := exec.Command("ps", "-axo", "pid=,ppid=").Output()
	if err != nil {
		return []int{root}
	}
	children := map[int][]int{}
	for _, line := range strings.Split(string(out), "\n") {
		fields := strings.Fields(line)
		if len(fields) != 2 {
			continue
		}
		var pid, ppid int
		if _, err := fmt.Sscanf(fields[0], "%d", &pid); err != nil {
			continue
		}
		if _, err := fmt.Sscanf(fields[1], "%d", &ppid); err != nil {
			continue
		}
		children[ppid] = append(children[ppid], pid)
	}
	ids := []int{root}
	queue := []int{root}
	for len(queue) > 0 {
		p := queue[0]
		queue = queue[1:]
		for _, c := range children[p] {
			ids = append(ids, c)
			queue = append(queue, c)
		}
	}
	return ids
}

// TestStateWiringWrapperProcessTreeBlocksListing is the acceptance red test:
// with the production wiring, a wrapper fake-claude pane whose screen shows a
// blocked box must surface state=blocked (≠ unknown) in listing, and the 012
// aggregate of its workspace must be blocked — the blocked/done notification
// data source (requirement 003 standard four) finally reachable.
func TestStateWiringWrapperProcessTreeBlocksListing(t *testing.T) {
	// The pane command: bash (the pane root, reported as pane_current_command
	// "bash" — the wrapper scene) prints the blocked box, then forks a child
	// that becomes the claude-named fake. Identify only matches DESCENDANTS of
	// pane_pid (root argv is deliberately ignored), so the claude-named process
	// must be the child, never the pane itself. The box text keys the
	// claude-blocked-permission-box rule ("Do you want to proceed?" + "esc to
	// cancel"); the fake tree keys the wrapper Identify path.
	const paneCmd = `bash -c 'printf "Do you want to proceed?\n  (esc to cancel)\n"; sh -c "exec -a claude /bin/sleep 300" & wait'`

	te, panePID := startWiredEnv(t, paneCmd)
	if panePID == 0 {
		t.Fatal("isolated pane resolved no pane_pid; wrapper tree cannot be identified")
	}

	// Wire the REAL provider (real ps + real capture-pane) with a short TTL so
	// the first listing refresh lands within the test's patience.
	p := NewStateProvider(discardLogger())
	defer p.Close()
	p.ttl = 100 * time.Millisecond
	p.pruneAge = time.Minute

	// The discoverer points at this isolated pane only, with the wrapper
	// command (bash) and the real pane_pid — exactly what a real scan reports.
	model := &discovery.Model{
		Workspaces: []discovery.Workspace{
			{
				CWD: "/ws/wired",
				Panes: []discovery.Pane{
					{Socket: te.sock, Session: "wired", PaneID: te.paneID, CWD: "/ws/wired", Command: "bash", PanePID: panePID, Width: 80, Height: 24},
				},
			},
		},
	}
	md := &mutableDiscoverer{model: model}
	e := startWS(t, Options{Token: "test-token", Discoverer: md, StateProvider: p})
	e.auth()

	// Poll listing until the provider's background refresh lands. The first
	// listing after auth may carry unknown (cache seeded); the refresh then
	// resolves the fake claude tree and the blocked box. The listing loop also
	// pushes list_delta frames on the same connection, so each poll drains
	// frames until the requested Listing reply arrives.
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		e.sendFrame(&protocol.List{ReqID: 1})
		var l protocol.Listing
		var ok bool
		for {
			got := e.readControl()
			l, ok = got.(protocol.Listing)
			if ok {
				break // the List reply; list_delta frames are skipped below
			}
			// Not a listing (e.g. a list_delta the loop pushed): drain and
			// read the next frame. Bound the inner drain so a frame burst
			// cannot spin forever.
			if time.Now().After(deadline) {
				t.Fatal("drained non-listing frames until deadline")
			}
		}
		if len(l.Workspaces) != 1 {
			continue // not yet scanned
		}
		ws := l.Workspaces[0]
		if len(ws.Sessions) != 1 {
			t.Fatalf("workspace has %d sessions, want 1", len(ws.Sessions))
		}
		st := ws.Sessions[0].State
		t.Logf("state=%s aggregate=%s", st, ws.AggregateState)
		if st != protocol.StateBlocked {
			time.Sleep(200 * time.Millisecond)
			continue
		}
		// The pane resolved to blocked (≠ unknown) and the workspace aggregate
		// must follow (012: blocked is the highest-ranked member state).
		if ws.AggregateState != protocol.StateBlocked {
			t.Fatalf("aggregate = %q, want blocked (012 with a blocked member)", ws.AggregateState)
		}
		t.Logf("state wiring ok: session state=%s aggregate=%s", st, ws.AggregateState)
		return
	}
	t.Fatal("listing never surfaced the blocked state from the wired provider")
}

// TestStateWiringDefaultProviderStaysUnknown is the pre-fix control: without
// wiring a StateProvider, the API default must keep rendering all-unknown.
// This proves the wiring test above is not vacuously green — the unknown
// default is the D-1 defect this task removes for production wiring, and it
// must remain the safe fallback when no provider is configured.
func TestStateWiringDefaultProviderStaysUnknown(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	// The default provider (unknownState) is what startWS wires when Options
	// carries none.

	// List and assert the pane is unknown (and only unknown) regardless of
	// what the pane actually shows.
	te.wsEnv.sendFrame(&protocol.List{ReqID: 9})
	got := te.wsEnv.readControl()
	l, ok := got.(protocol.Listing)
	if !ok {
		t.Fatalf("expected listing, got %v", got.FrameType())
	}
	if len(l.Workspaces) != 1 || len(l.Workspaces[0].Sessions) != 1 {
		t.Fatalf("unexpected listing shape: %+v", l.Workspaces)
	}
	if st := l.Workspaces[0].Sessions[0].State; st != protocol.StateUnknown {
		t.Fatalf("default provider state = %q, want unknown (the pre-fix fallback)", st)
	}
}

// TestStateProviderSampleFailureDegradesUnknown pins the isolation law: a pane
// whose capture fails (here: a socket that does not exist) must degrade to
// StateUnknown, never block State(), and never affect the mirror path. The
// cached entry is served synchronously; the failed refresh stores unknown.
func TestStateProviderSampleFailureDegradesUnknown(t *testing.T) {
	p := NewStateProvider(discardLogger())
	defer p.Close()
	p.ttl = time.Millisecond // refresh eagerly

	// A pane on a socket that cannot be captured (no tmux server there).
	pn := discovery.Pane{Socket: "/nonexistent/sock", PaneID: "%0", Command: "claude", CWD: "/ws/x", Width: 80, Height: 24}
	st := p.State(context.Background(), pn)
	if st != protocol.StateUnknown {
		t.Fatalf("first sighting state = %q, want unknown (cache seed)", st)
	}
	// Give the failed refresh time to run; the next call must still be unknown.
	time.Sleep(200 * time.Millisecond)
	st = p.State(context.Background(), pn)
	if st != protocol.StateUnknown {
		t.Fatalf("post-failure state = %q, want unknown (isolation law)", st)
	}
}

// TestStateProviderCacheDoesNotBlockHotPath pins the hot-path isolation:
// State() is a synchronous cache read. A provider whose sample seam hangs must
// still return a state immediately (it returns the cached value and refreshes
// in the background), so the listing loop can never be stalled by state IO
// (requirement 008, knowledge base §0.4 D-1 red line).
func TestStateProviderCacheDoesNotBlockHotPath(t *testing.T) {
	p := NewStateProvider(discardLogger())
	defer p.Close()
	p.ttl = time.Hour // never refresh for real in this test

	// Swap in a sample seam that would block forever if called synchronously.
	p.sample = func(ctx context.Context, pn discovery.Pane) ([]byte, time.Duration, error) {
		select {
		case <-ctx.Done():
			return nil, 0, ctx.Err()
		}
	}

	pn := discovery.Pane{Socket: "/s", PaneID: "%0", Command: "claude", CWD: "/ws/x", Width: 80, Height: 24}
	// Seed the cache first so State() has something to serve.
	_ = p.State(context.Background(), pn)
	// A second call within the TTL must return from cache — no refresh, no IO.
	start := time.Now()
	st := p.State(context.Background(), pn)
	if elapsed := time.Since(start); elapsed > 50*time.Millisecond {
		t.Fatalf("State() took %v; hot-path cache read must not block on state IO", elapsed)
	}
	if st != protocol.StateUnknown {
		t.Fatalf("cache-seeded state = %q, want unknown", st)
	}
}
