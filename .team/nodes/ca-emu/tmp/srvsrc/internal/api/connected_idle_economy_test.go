package api

// connected_idle_economy_test.go pins the two server-side prerequisites for
// measuring connected-idle economy without touching a user's tmux fleet:
// discovery must accept an explicit socket-directory scope, and state samples
// must enter one fleet-wide scheduler instead of fanning out per pane.
//
// 060 uproot (2026-08-15): the agent-state sampling scheduler was removed
// wholesale with the state pipeline (requirement 060: 二级菜单改为实时流并取代
// 状态判定). The sampling-fairness test is archived under scratch/ (uproot bundle).
// The discovery-isolation tests below are kept — they pin the scoped-discovery
// seam, which the level-1 menu still needs.

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
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
