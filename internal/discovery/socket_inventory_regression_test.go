package discovery

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
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
