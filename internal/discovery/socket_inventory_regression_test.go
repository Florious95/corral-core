package discovery

import (
	"context"
	"errors"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"
)

// TestDiscoverIncludesEveryCurrentUserSocket is a credential-free fixture for
// the existing socket-directory inventory: default plus two user-named tmux
// sockets must all contribute panes when TMUX does not select one explicitly.
func TestDiscoverIncludesEveryCurrentUserSocket(t *testing.T) {
	root := testSocketRoot(t)
	tmp := t.TempDir()
	cwd := mkdirTmp(t, tmp, "ws-all-sockets")
	dir := testSocketDir(t, root)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	start := func(name, session string) string {
		t.Helper()
		socket := filepath.Join(dir, name)
		runTMUX(t, root, "-S", socket, "new-session", "-d", "-c", cwd, "-s", session)
		t.Cleanup(func() {
			cmd := exec.Command("tmux", "-S", socket, "kill-server")
			cmd.Env = append(envWithout(os.Environ(), "TMUX"), "TMUX_TMPDIR="+root)
			_ = cmd.Run()
		})
		return socket
	}
	start("default", "default-session")
	first := start("first", "same-session")
	second := start("second", "same-session")
	createStaleSocket(t, filepath.Join(dir, "stale-user"))
	t.Setenv("TMUX", "")

	model, err := DiscoverWithDirs(context.Background(), discardLogger(), []string{testSocketDir(t, root)})
	if err != nil {
		t.Fatalf("DiscoverWithDirs: %v", err)
	}
	if len(model.Workspaces) != 1 || model.Workspaces[0].Count() != 3 {
		t.Fatalf("model=%+v, want one workspace with default and both named sockets", model)
	}
	seen := map[string]bool{}
	seenRefs := map[string]bool{}
	sameSession := 0
	for _, p := range model.Workspaces[0].Panes {
		seen[p.Socket] = true
		ref := p.Socket + "\x1f" + p.PaneID
		if seenRefs[ref] {
			t.Fatalf("duplicate pane ref %q in %+v", ref, model.Workspaces[0].Panes)
		}
		seenRefs[ref] = true
		if p.Session == "same-session" {
			sameSession++
		}
	}
	for _, socket := range []string{first, second} {
		if !seen[socket] {
			t.Fatalf("named socket %q missing from %v", socket, seen)
		}
	}
	if sameSession != 2 || len(seenRefs) != 3 {
		t.Fatalf("same-name socket panes=%d refs=%d, want 2 distinct panes and 3 total refs", sameSession, len(seenRefs))
	}
	if got := os.Getenv("TMUX"); got != "" {
		t.Fatalf("fixture must clear TMUX, got %q", got)
	}
}

// TestDiscoverIncludesTeamSocketWithSamePaneIdentity is the causal regression
// for Issue10: a current-user ta-* Team socket is a product socket, not an
// isolation fixture. Three servers intentionally reuse the session, pane id,
// and CWD so the model must preserve the socket in every distinct ref.
func TestDiscoverIncludesTeamSocketWithSamePaneIdentity(t *testing.T) {
	root := testSocketRoot(t)
	tmp := t.TempDir()
	cwd := mkdirTmp(t, tmp, "ws-team-same-pane")
	cwdWant, err := filepath.EvalSymlinks(cwd)
	if err != nil {
		t.Fatal(err)
	}
	dir := testSocketDir(t, root)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	start := func(socketName string) string {
		t.Helper()
		socket := filepath.Join(dir, socketName)
		runTMUX(t, root, "-S", socket, "new-session", "-d", "-c", cwd, "-s", "same-session")
		t.Cleanup(func() {
			cmd := exec.Command("tmux", "-S", socket, "kill-server")
			cmd.Env = append(envWithout(os.Environ(), "TMUX"), "TMUX_TMPDIR="+root)
			_ = cmd.Run()
		})
		return socket
	}
	defaultSocket := start("default")
	namedSocket := start("ordinary")
	teamSocket := start("ta-team")
	t.Setenv("TMUX", "")

	model, err := DiscoverWithDirs(context.Background(), discardLogger(), []string{dir})
	if err != nil {
		t.Fatalf("DiscoverWithDirs: %v", err)
	}
	if len(model.Workspaces) != 1 || model.Workspaces[0].CWD != cwdWant {
		t.Fatalf("model workspaces = %+v, want one workspace at %q", model.Workspaces, cwdWant)
	}
	ws := model.Workspaces[0]
	if ws.Count() != 3 {
		t.Fatalf("workspace count = %d, want 3 panes across default/ordinary/ta sockets", ws.Count())
	}
	seenSockets := make(map[string]bool, ws.Count())
	seenRefs := make(map[string]bool, ws.Count())
	for _, pane := range ws.Panes {
		if pane.CWD != cwdWant || pane.Session != "same-session" || pane.PaneID != "%0" {
			t.Fatalf("same-pane identity changed: %+v", pane)
		}
		seenSockets[pane.Socket] = true
		ref := pane.Socket + "\x1f" + pane.PaneID
		if seenRefs[ref] {
			t.Fatalf("duplicate pane ref %q: %+v", ref, ws.Panes)
		}
		seenRefs[ref] = true
	}
	for _, socket := range []string{defaultSocket, namedSocket, teamSocket} {
		if !seenSockets[socket] {
			t.Fatalf("socket %q missing from model: %v", socket, seenSockets)
		}
	}
	if len(seenRefs) != 3 {
		t.Fatalf("refs = %d, want 3 distinct socket-qualified refs: %v", len(seenRefs), seenRefs)
	}
}

// TestIssue10ScopedLifecycle follows one real, isolated directory across a
// server exit and recreation. It checks socket identity rather than counts
// alone and records the fixture's actual socket path before discovery.
func TestIssue10ScopedLifecycle(t *testing.T) {
	root := testSocketRoot(t)
	dir := testSocketDir(t, root)
	if err := os.MkdirAll(dir, 0700); err != nil {
		t.Fatal(err)
	}
	cwd := t.TempDir()
	names := []string{"default", "ordinary", "ta-owned-fixture"}
	sockets := make([]string, 0, len(names))
	tmux := func(socket string, args ...string) string {
		t.Helper()
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		cmd := exec.CommandContext(ctx, "tmux", append([]string{"-S", socket}, args...)...)
		cmd.Env = append(envWithout(os.Environ(), "TMUX"), "TMUX_TMPDIR="+root)
		out, err := cmd.CombinedOutput()
		if err != nil {
			t.Fatalf("owned tmux %v: %v: %s", args, err, out)
		}
		return strings.TrimSpace(string(out))
	}
	for _, name := range names {
		socket := filepath.Join(dir, name)
		sockets = append(sockets, socket)
		tmux(socket, "new-session", "-d", "-s", "same-session", "-c", cwd)
		actual := tmux(socket, "display-message", "-p", "#{socket_path}")
		if actual != socket {
			t.Fatalf("socket escaped: got %q want %q", actual, socket)
		}
		t.Cleanup(func() {
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			cmd := exec.CommandContext(ctx, "tmux", "-S", socket, "kill-server")
			cmd.Env = envWithout(os.Environ(), "TMUX")
			killErr := cmd.Run()
			// kill-server acknowledges the command before its listener necessarily
			// closes. Keep the existing five-second teardown budget as a barrier.
			for {
				conn, err := (&net.Dialer{Timeout: 100 * time.Millisecond}).DialContext(ctx, "unix", socket)
				if errors.Is(err, syscall.ENOENT) || errors.Is(err, syscall.ECONNREFUSED) {
					t.Logf("owned listener closed: %s; kill-server=%v", socket, killErr)
					break
				}
				if conn != nil {
					conn.Close()
				}
				select {
				case <-ctx.Done():
					t.Errorf("owned listener did not close within teardown deadline: %s; kill-server=%v; probe=%v", socket, killErr, err)
					return
				case <-time.After(10 * time.Millisecond):
				}
			}
		})
	}
	t.Setenv("TMUX", "")
	createStaleSocket(t, filepath.Join(dir, "stale"))
	check := func(want []string) {
		t.Helper()
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		model, err := DiscoverWithDirs(ctx, discardLogger(), []string{dir})
		if err != nil {
			t.Fatal(err)
		}
		got := map[string]bool{}
		for _, ws := range model.Workspaces {
			for _, pane := range ws.Panes {
				if pane.PaneID != "%0" || pane.Session != "same-session" {
					t.Fatalf("unexpected pane: %+v", pane)
				}
				ref := pane.Socket + "\x1f" + pane.PaneID
				if got[ref] {
					t.Fatalf("duplicate ref %q", ref)
				}
				got[ref] = true
			}
		}
		if len(got) != len(want) {
			t.Fatalf("refs=%v want=%v", got, want)
		}
		for _, socket := range want {
			if !got[socket+"\x1f%0"] {
				t.Fatalf("missing owned socket %s: %v", socket, got)
			}
		}
		t.Logf("exact refs=%v", got)
	}
	check(sockets)
	tmux(sockets[1], "kill-server")
	check([]string{sockets[0], sockets[2]})
	tmux(sockets[1], "new-session", "-d", "-s", "same-session", "-c", cwd)
	check(sockets)
}
