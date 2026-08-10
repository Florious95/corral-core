package api

// connected_idle_economy_test.go pins the two server-side prerequisites for
// measuring connected-idle economy without touching a user's tmux fleet:
// discovery must accept an explicit socket-directory scope, and state samples
// must enter one fleet-wide scheduler instead of fanning out per pane.

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

// TestConnectedIdleEconomyScopedDiscoverySeamExists is the isolation red
// test. The real-process probe cannot rely on TMUX_TMPDIR: default discovery
// deliberately adds the host's default socket directories. An explicit nil
// versus non-nil option is required so the probe can fail closed on only its
// own socket directory while production callers retain the default scan.
func TestConnectedIdleEconomyScopedDiscoverySeamExists(t *testing.T) {
	if _, ok := reflect.TypeOf(Options{}).FieldByName("DiscoverySocketDirs"); !ok {
		t.Fatal("Options has no DiscoverySocketDirs seam; an isolated daemon would still scan host default tmux sockets")
	}
}

// TestConnectedIdleEconomyProductionDiscoveryDefaultIsUnchanged pins the nil
// distinction without performing a scan: no option and no e2e env must return
// nil, which is the exact branch that still calls discovery.Discover.
func TestConnectedIdleEconomyProductionDiscoveryDefaultIsUnchanged(t *testing.T) {
	old, hadOld := os.LookupEnv(scopedDiscoveryDirsEnv)
	if err := os.Unsetenv(scopedDiscoveryDirsEnv); err != nil {
		t.Fatalf("unset scoped discovery env: %v", err)
	}
	t.Cleanup(func() {
		if hadOld {
			_ = os.Setenv(scopedDiscoveryDirsEnv, old)
		} else {
			_ = os.Unsetenv(scopedDiscoveryDirsEnv)
		}
	})
	if got := resolvedDiscoverySocketDirs(nil); got != nil {
		t.Fatalf("production default dirs = %v, want nil discovery.Discover branch", got)
	}

	configured := []string{"/isolated/only"}
	got := resolvedDiscoverySocketDirs(configured)
	configured[0] = "/mutated"
	if !reflect.DeepEqual(got, []string{"/isolated/only"}) {
		t.Fatalf("explicit scope was not copied: %v", got)
	}
	if got := resolvedDiscoverySocketDirs([]string{}); got == nil || len(got) != 0 {
		t.Fatalf("explicit empty scope = %#v, want non-nil fail-closed empty slice", got)
	}
}

// TestConnectedIdleEconomyScopedDiscoveryConsumesOnlyExplicitDirs exercises
// the real API consumer and the real discovery scanner against one isolated
// tmux socket. A PATH wrapper records every tmux target and refuses to exec
// anything outside the scope, so even a regression cannot connect to a host
// default or Team Agent private socket.
func TestConnectedIdleEconomyScopedDiscoveryConsumesOnlyExplicitDirs(t *testing.T) {
	realTmux, err := exec.LookPath("tmux")
	if err != nil {
		t.Skip("tmux not installed")
	}
	realTmux, err = filepath.Abs(realTmux)
	if err != nil {
		t.Fatalf("absolute tmux path: %v", err)
	}

	// Keep the explicit socket path below macOS's short AF_UNIX limit.
	root, err := os.MkdirTemp("/tmp", "amcid-")
	if err != nil {
		t.Fatalf("MkdirTemp: %v", err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(root) })
	socketDir := filepath.Join(root, "sockets")
	if err := os.Mkdir(socketDir, 0o700); err != nil {
		t.Fatalf("mkdir socket dir: %v", err)
	}
	socket := filepath.Join(socketDir, "only-this-server")
	name := fmt.Sprintf("scoped-%d", os.Getpid())
	start := exec.Command(realTmux, "-S", socket, "new-session", "-d", "-s", name, "-c", t.TempDir(), "cat")
	start.Env = scrubbedEnv()
	if out, err := start.CombinedOutput(); err != nil {
		t.Fatalf("start isolated tmux: %v: %s", err, out)
	}
	t.Cleanup(func() {
		stop := exec.Command(realTmux, "-S", socket, "kill-server")
		stop.Env = scrubbedEnv()
		_ = stop.Run()
	})

	binDir := filepath.Join(root, "bin")
	if err := os.Mkdir(binDir, 0o700); err != nil {
		t.Fatalf("mkdir wrapper dir: %v", err)
	}
	audit := filepath.Join(root, "tmux-targets.log")
	const wrapper = `#!/bin/sh
target=
take_next=0
for arg in "$@"; do
  if [ "$take_next" = 1 ]; then
    target=$arg
    take_next=0
    continue
  fi
  if [ "$arg" = "-S" ]; then
    take_next=1
  fi
done
printf '%s\n' "$target" >> "$SCOPED_DISCOVERY_AUDIT"
case "$target" in
  "$SCOPED_DISCOVERY_ALLOWED"/*) exec "$SCOPED_DISCOVERY_REAL_TMUX" "$@" ;;
  *) exit 97 ;;
esac
`
	if err := os.WriteFile(filepath.Join(binDir, "tmux"), []byte(wrapper), 0o700); err != nil {
		t.Fatalf("write tmux audit wrapper: %v", err)
	}
	t.Setenv("PATH", binDir+string(os.PathListSeparator)+os.Getenv("PATH"))
	t.Setenv("SCOPED_DISCOVERY_AUDIT", audit)
	t.Setenv("SCOPED_DISCOVERY_ALLOWED", socketDir)
	t.Setenv("SCOPED_DISCOVERY_REAL_TMUX", realTmux)
	t.Setenv(scopedDiscoveryDirsEnv, socketDir)

	// Use the environment bridge consumed by an unchanged cmd/agentmirrord,
	// not the direct Go option, so the real e2e daemon path is covered here.
	s := NewServer(Options{})
	defer s.Close()
	td, ok := s.discoverer.(tmuxDiscoverer)
	if !ok {
		t.Fatalf("default discoverer type = %T, want tmuxDiscoverer", s.discoverer)
	}
	if !reflect.DeepEqual(td.socketDirs, []string{socketDir}) {
		t.Fatalf("consumer socket dirs = %v, want only %q", td.socketDirs, socketDir)
	}

	model, err := s.discoverer.Discover(context.Background())
	if err != nil {
		t.Fatalf("scoped Discover: %v", err)
	}
	if len(model.Workspaces) != 1 || model.Workspaces[0].Count() != 1 {
		t.Fatalf("scoped model = %+v, want exactly one pane", model.Workspaces)
	}
	if got := model.Workspaces[0].Panes[0].Socket; got != socket {
		t.Fatalf("discovered socket = %q, want %q", got, socket)
	}

	raw, err := os.ReadFile(audit)
	if err != nil {
		t.Fatalf("read tmux audit: %v", err)
	}
	targets := strings.Fields(string(raw))
	if len(targets) != 1 || targets[0] != socket {
		t.Fatalf("tmux targets = %q, want only isolated socket %q", targets, socket)
	}
	for _, forbidden := range []string{
		filepath.Join("/tmp", fmt.Sprintf("tmux-%d", os.Getuid())),
		filepath.Join("/private/tmp", fmt.Sprintf("tmux-%d", os.Getuid())),
	} {
		if strings.HasPrefix(targets[0], forbidden+string(os.PathSeparator)) {
			t.Fatalf("scoped discovery touched host default tree %q", forbidden)
		}
	}
	if teamSocket := strings.SplitN(os.Getenv("TMUX"), ",", 2)[0]; teamSocket != "" {
		teamTree := filepath.Dir(teamSocket)
		if strings.HasPrefix(targets[0], teamTree+string(os.PathSeparator)) {
			t.Fatalf("scoped discovery touched Team Agent private tree %q", teamTree)
		}
	}
}

// TestConnectedIdleEconomySamplingDoesNotFanOutFleet is the sampling red
// test. With no pacing permit, a stable fleet may launch at most the first
// global sample, regardless of whether it contains 3, 27, or 200 panes. The
// old per-entry TTL implementation launches one sampler for every pane here.
func TestConnectedIdleEconomySamplingDoesNotFanOutFleet(t *testing.T) {
	for _, panes := range []int{3, 27, 200} {
		t.Run(fmt.Sprintf("panes_%d", panes), func(t *testing.T) {
			p := NewStateProvider(discardLogger())
			defer p.Close()
			p.ttl = time.Hour

			var samples atomic.Int64
			p.sample = func(context.Context, discovery.Pane) ([]byte, time.Duration, error) {
				samples.Add(1)
				return nil, 0, errors.New("counted sample")
			}

			fleet := make([]discovery.Pane, panes)
			for i := range fleet {
				fleet[i] = discovery.Pane{
					Socket:  "/isolated/not-opened",
					Session: fmt.Sprintf("s-%03d", i),
					PaneID:  fmt.Sprintf("%%%d", i),
					CWD:     "/isolated",
					Command: "claude",
				}
			}
			// Seed a previously sampled, unchanged fleet whose entire TTL has
			// expired. This is the exact old steady-state shape: the next
			// listing used to launch one refresh for every cached pane.
			p.mu.Lock()
			for _, pn := range fleet {
				p.cache[sessionRef(pn)] = &stateCacheEntry{
					pane:          pn,
					state:         protocol.StateWorking,
					prev:          protocol.StateWorking,
					lastRequested: p.now(),
					lastRefresh:   p.now().Add(-2 * p.ttl),
				}
			}
			p.mu.Unlock()
			for _, pn := range fleet {
				_ = p.State(context.Background(), pn)
			}

			// Wait only for evidence of a forbidden second dispatch. This is
			// below the production scheduler interval, so a fixed-rate
			// implementation stays at zero or one while the old fan-out turns
			// red without a timing-sensitive expectation that every goroutine ran.
			deadline := time.Now().Add(100 * time.Millisecond)
			for samples.Load() <= 1 && time.Now().Before(deadline) {
				time.Sleep(time.Millisecond)
			}
			if got := samples.Load(); got > 1 {
				t.Fatalf("%d-pane stable fleet dispatched %d samples without a global pacing permit; want <= 1", panes, got)
			}
		})
	}
}

// TestConnectedIdleEconomySamplingRateFairnessAndVisibility advances one
// fake clock in 2s listing rounds. Each round may dispatch the same fixed
// burst for 3, 27, and 200 panes; the FIFO must reach every pane and later
// carry a worst-position blocked change into the listing/delta source within
// the frozen 60s bound.
func TestConnectedIdleEconomySamplingRateFairnessAndVisibility(t *testing.T) {
	for _, panes := range []int{3, 27, 200} {
		t.Run(fmt.Sprintf("panes_%d", panes), func(t *testing.T) {
			base := time.Unix(1_700_000_000, 0)
			var fakeNanos atomic.Int64
			fakeNanos.Store(base.UnixNano())
			now := func() time.Time { return time.Unix(0, fakeNanos.Load()) }

			p := NewStateProvider(discardLogger())
			defer p.Close()
			p.now = now
			p.mu.Lock()
			p.lastRefill = now()
			p.tokens = 1
			p.mu.Unlock()

			model := connectedIdleModel(panes)
			catalog := newSessionCatalog()
			catalog.rebuild(model)
			targetPane := model.Workspaces[0].Panes[panes-1]
			targetRef := sessionRef(targetPane)

			var (
				blocked   atomic.Bool
				samplesMu sync.Mutex
				total     int
				firstSeen = make(map[string]time.Duration, panes)
			)
			p.sample = func(_ context.Context, pn discovery.Pane) ([]byte, time.Duration, error) {
				samplesMu.Lock()
				total++
				if _, ok := firstSeen[pn.PaneID]; !ok {
					firstSeen[pn.PaneID] = now().Sub(base)
				}
				samplesMu.Unlock()
				if blocked.Load() && pn.PaneID == targetPane.PaneID {
					return []byte("Do you want to proceed?\n(esc to cancel)\n"), 0, nil
				}
				return []byte("bypass permissions on · esc to interrupt · 1 shell\n"), 0, nil
			}

			var snap *modelSnapshot
			for round := 0; round <= 30; round++ {
				if round > 0 {
					fakeNanos.Add(int64(defaultListInterval))
				}
				samplesMu.Lock()
				before := total
				samplesMu.Unlock()
				snap = buildSnapshot(catalog, p, context.Background())
				waitConnectedIdleProvider(t, p)
				samplesMu.Lock()
				dispatched := total - before
				seen := len(firstSeen)
				samplesMu.Unlock()
				limit := defaultStateDispatchBurst
				if round == 0 {
					limit = 1
				}
				if dispatched > limit {
					t.Fatalf("round %d dispatched %d samples for %d panes, fixed limit %d", round, dispatched, panes, limit)
				}
				if seen == panes {
					break
				}
			}

			samplesMu.Lock()
			if len(firstSeen) != panes {
				t.Fatalf("first-pass fairness reached %d/%d panes in %v", len(firstSeen), panes, now().Sub(base))
			}
			for id, elapsed := range firstSeen {
				if elapsed > 60*time.Second {
					t.Fatalf("pane %s first sampled after %v, want <= 60s", id, elapsed)
				}
			}
			samplesMu.Unlock()

			// The dispatch arithmetic itself must retain room for one full
			// sample budget and the next default listing publication.
			roundsAfterFirst := (panes - 1 + defaultStateDispatchBurst - 1) / defaultStateDispatchBurst
			worstVisible := time.Duration(roundsAfterFirst)*defaultListInterval + defaultStateSamplingBudget + defaultListInterval
			if worstVisible > 60*time.Second {
				t.Fatalf("%d-pane configured worst visibility = %v, want <= 60s", panes, worstVisible)
			}

			// Refresh completion happens after State returned its cached value;
			// rebuild once at the same fake instant to publish the sampled state.
			snap = buildSnapshot(catalog, p, context.Background())
			if got := snap.byRef[targetRef].State; got != protocol.StateWorking {
				t.Fatalf("target initial state = %q, want working", got)
			}

			blocked.Store(true)
			changedAt := now()
			for round := 1; round <= 30; round++ {
				fakeNanos.Add(int64(defaultListInterval))
				samplesMu.Lock()
				before := total
				samplesMu.Unlock()

				_ = buildSnapshot(catalog, p, context.Background())
				waitConnectedIdleProvider(t, p)
				afterRefresh := buildSnapshot(catalog, p, context.Background())

				samplesMu.Lock()
				dispatched := total - before
				samplesMu.Unlock()
				if dispatched > defaultStateDispatchBurst {
					t.Fatalf("change round %d dispatched %d samples, fixed limit %d", round, dispatched, defaultStateDispatchBurst)
				}
				if afterRefresh.byRef[targetRef].State != protocol.StateBlocked {
					snap = afterRefresh
					continue
				}

				if elapsed := now().Sub(changedAt); elapsed > 60*time.Second {
					t.Fatalf("blocked target became visible after %v, want <= 60s", elapsed)
				}
				delta := afterRefresh.diff(snap)
				var changedSession, changedWorkspace bool
				for _, s := range delta.ChangedSessions {
					changedSession = changedSession || s.Ref == targetRef && s.State == protocol.StateBlocked
				}
				for _, ws := range delta.ChangedWorkspaces {
					changedWorkspace = changedWorkspace || ws.Cwd == "/isolated" && ws.AggregateState == protocol.StateBlocked
				}
				if !changedSession || !changedWorkspace {
					t.Fatalf("blocked change did not reach listing delta: %+v", delta)
				}
				return
			}
			t.Fatalf("blocked target %q did not reach listing within 60s", targetRef)
		})
	}
}

func connectedIdleModel(panes int) *discovery.Model {
	ps := make([]discovery.Pane, panes)
	for i := range ps {
		ps[i] = discovery.Pane{
			Socket:  "/isolated/socket",
			Session: fmt.Sprintf("s-%03d", i),
			PaneID:  fmt.Sprintf("%%%d", i),
			CWD:     "/isolated",
			Command: "claude",
			Width:   80,
			Height:  24,
		}
	}
	return &discovery.Model{Workspaces: []discovery.Workspace{{CWD: "/isolated", Panes: ps}}}
}

func waitConnectedIdleProvider(t *testing.T, p *wiredStateProvider) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		p.mu.Lock()
		busy := false
		for _, e := range p.cache {
			busy = busy || e.inFlight
		}
		p.mu.Unlock()
		if !busy {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal("state refreshes did not drain")
}
