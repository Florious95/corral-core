package discovery

import (
	"context"
	"fmt"
	"log/slog"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"testing"
)

// --- isolated tmux helpers -------------------------------------------------
//
// Test safety red line: every tmux server a test starts is bound to a unique
// socket inside a t.TempDir() tree, and every child tmux gets TMUX stripped
// from its environment. Tests therefore never connect to, interfere with, or
// kill any real tmux server on the host — even when the test suite itself runs
// attached to tmux (which this environment does).

// testSocketDir returns the tmux-<uid> socket directory inside tmp, matching
// where tmux places sockets when TMUX_TMPDIR=tmp.
func testSocketDir(t *testing.T, tmp string) string {
	t.Helper()
	return filepath.Join(tmp, "tmux-"+strconv.Itoa(os.Getuid()))
}

// testSocketRoot creates a short, isolated root directory for tmux sockets.
// t.TempDir() lives under /var/folders/... and is far longer than the 104-byte
// unix sun_path limit, which makes tmux fail with "File name too long", so the
// socket tree is rooted in a short path under /private/tmp (macOS) or /tmp.
func testSocketRoot(t *testing.T) string {
	t.Helper()
	base := "/tmp"
	if _, err := os.Stat("/private/tmp"); err == nil {
		base = "/private/tmp"
	}
	root, err := os.MkdirTemp(base, "disc-")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(root) })
	return root
}

// mkdirTmp creates a subdirectory of tmp and returns its path; used to give
// test panes a real CWD.
func mkdirTmp(t *testing.T, tmp, name string) string {
	t.Helper()
	d := filepath.Join(tmp, name)
	if err := os.Mkdir(d, 0o755); err != nil {
		t.Fatal(err)
	}
	return d
}

// discardLogger returns a logger that drops everything, keeping test output
// readable (debug logs for skipped sockets are exercised silently).
func discardLogger() *slog.Logger {
	return slog.New(slog.DiscardHandler)
}

// runTMUX runs tmux with TMUX stripped and TMUX_TMPDIR pinned to tmp, so a
// freshly started server can never escape the isolated tree.
func runTMUX(t *testing.T, tmp string, args ...string) {
	t.Helper()
	cmd := exec.Command("tmux", args...)
	cmd.Env = append(envWithout(os.Environ(), "TMUX"), "TMUX_TMPDIR="+tmp)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("tmux %v: %v\n%s", args, err, out)
	}
}

// startTestServer starts a detached tmux server on a unique isolated socket
// inside tmp and registers cleanup that kills it. It returns the socket path.
// extra are extra args passed after "-c cwd" (e.g. "-s", "session").
func startTestServer(t *testing.T, tmp, caseName, cwd string, extra ...string) string {
	t.Helper()
	dir := testSocketDir(t, tmp)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	sockName := fmt.Sprintf("test-disc-%d-%s", os.Getpid(), caseName)
	socket := filepath.Join(dir, sockName)

	args := append([]string{"-S", socket, "new-session", "-d", "-c", cwd}, extra...)
	runTMUX(t, tmp, args...)

	t.Cleanup(func() {
		// The server may already be gone (leaving a stale socket); ignore errors.
		cmd := exec.Command("tmux", "-S", socket, "kill-server")
		cmd.Env = append(envWithout(os.Environ(), "TMUX"), "TMUX_TMPDIR="+tmp)
		_ = cmd.Run()
	})
	return socket
}

// createStaleSocket leaves a unix socket inode with no listener behind, exactly
// like a tmux server that exited without unlinking its socket.
func createStaleSocket(t *testing.T, path string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatal(err)
	}
	ln, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	if ul, ok := ln.(*net.UnixListener); ok {
		ul.SetUnlinkOnClose(false)
	}
	if err := ln.Close(); err != nil {
		t.Fatal(err)
	}
}

// --- tests -----------------------------------------------------------------

// TestDiscoverAggregatesSameCWDAcrossSockets is the non-empty control: two
// independent tmux servers (distinct sockets), each holding a session whose
// pane shares one CWD, must aggregate into a single workspace of count 2
// (requirement 002).
func TestDiscoverAggregatesSameCWDAcrossSockets(t *testing.T) {
	root := testSocketRoot(t)
	tmp := t.TempDir()
	cwd := mkdirTmp(t, tmp, "ws-shared")
	// tmux reports pane_current_path in canonical form (/var -> /private/var
	// on macOS), so compare against the symlink-resolved path.
	cwdWant, err := filepath.EvalSymlinks(cwd)
	if err != nil {
		t.Fatal(err)
	}
	startTestServer(t, root, "srv-a", cwd, "-s", "alpha")
	startTestServer(t, root, "srv-b", cwd, "-s", "beta")

	model, err := DiscoverWithDirs(context.Background(), discardLogger(), []string{testSocketDir(t, root)})
	if err != nil {
		t.Fatalf("DiscoverWithDirs: %v", err)
	}
	if len(model.Workspaces) != 1 {
		t.Fatalf("got %d workspaces, want 1 aggregated by cwd %q", len(model.Workspaces), cwdWant)
	}
	ws := model.Workspaces[0]
	if ws.CWD != cwdWant {
		t.Fatalf("workspace cwd = %q, want %q", ws.CWD, cwdWant)
	}
	if ws.Count() != 2 {
		t.Fatalf("workspace count = %d, want 2 (one pane per socket)", ws.Count())
	}
}

// TestDiscoverSeparatesDistinctCWDs checks that panes in different CWDs become
// separate workspaces, and that the session name is carried onto each pane as
// its display label.
func TestDiscoverSeparatesDistinctCWDs(t *testing.T) {
	root := testSocketRoot(t)
	tmp := t.TempDir()
	cwdA := mkdirTmp(t, tmp, "ws-a")
	cwdB := mkdirTmp(t, tmp, "ws-b")
	// tmux reports canonical paths (/var -> /private/var on macOS).
	cwdAwant, err := filepath.EvalSymlinks(cwdA)
	if err != nil {
		t.Fatal(err)
	}
	cwdBwant, err := filepath.EvalSymlinks(cwdB)
	if err != nil {
		t.Fatal(err)
	}
	startTestServer(t, root, "srv-a", cwdA, "-s", "alpha")
	startTestServer(t, root, "srv-b", cwdB, "-s", "beta")

	model, err := DiscoverWithDirs(context.Background(), discardLogger(), []string{testSocketDir(t, root)})
	if err != nil {
		t.Fatalf("DiscoverWithDirs: %v", err)
	}
	if len(model.Workspaces) != 2 {
		t.Fatalf("got %d workspaces, want 2", len(model.Workspaces))
	}
	sessions := map[string]string{model.Workspaces[0].Panes[0].Session: model.Workspaces[0].CWD,
		model.Workspaces[1].Panes[0].Session: model.Workspaces[1].CWD}
	if sessions["alpha"] != cwdAwant || sessions["beta"] != cwdBwant {
		t.Fatalf("session->cwd mapping = %v, want alpha=%q beta=%q", sessions, cwdAwant, cwdBwant)
	}
}

// TestDiscoverCarriesSocketThrough is the additive red test for the Socket
// field: a pane discovered through a real isolated server must retain the
// exact socket path it lives on, because the ws-api consumer addresses the
// pane's bridge with it (bridge.NewPane(Socket, PaneID)). A pane whose socket
// went blank between scan and model would be unmirrorable on a multi-server
// host (requirement 001).
func TestDiscoverCarriesSocketThrough(t *testing.T) {
	root := testSocketRoot(t)
	tmp := t.TempDir()
	cwd := mkdirTmp(t, tmp, "ws-sock")
	sock := startTestServer(t, root, "srv", cwd, "-s", "alpha")

	model, err := DiscoverWithDirs(context.Background(), discardLogger(), []string{testSocketDir(t, root)})
	if err != nil {
		t.Fatalf("DiscoverWithDirs: %v", err)
	}
	if len(model.Workspaces) != 1 || len(model.Workspaces[0].Panes) != 1 {
		t.Fatalf("want exactly one pane, got %+v", model.Workspaces)
	}
	got := model.Workspaces[0].Panes[0].Socket
	if got != sock {
		t.Fatalf("pane Socket = %q, want %q (the isolated server socket)", got, sock)
	}
}

// TestDiscoverCarriesWindowNameThrough is the additive red test for the
// WindowName field (task fix-session-alias): a pane discovered through a real
// isolated server must retain the tmux window name it lives in, because the
// listing layer renders it as the session's display label (window names carry
// the meaningful per-window labels, session names the whole-team name). The
// session name must stay intact alongside it.
func TestDiscoverCarriesWindowNameThrough(t *testing.T) {
	root := testSocketRoot(t)
	tmp := t.TempDir()
	cwd := mkdirTmp(t, tmp, "ws-winname")
	sock := startTestServer(t, root, "srv", cwd, "-s", "alpha")

	// Rename the window to the meaningful label the client should render.
	cmd := exec.Command("tmux", "-S", sock, "rename-window", "-t", "alpha:0", "wiki-r5-acceptance-tester")
	cmd.Env = append(envWithout(os.Environ(), "TMUX"), "TMUX_TMPDIR="+root)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("rename-window: %v\n%s", err, out)
	}

	model, err := DiscoverWithDirs(context.Background(), discardLogger(), []string{testSocketDir(t, root)})
	if err != nil {
		t.Fatalf("DiscoverWithDirs: %v", err)
	}
	if len(model.Workspaces) != 1 || len(model.Workspaces[0].Panes) != 1 {
		t.Fatalf("want exactly one pane, got %+v", model.Workspaces)
	}
	got := model.Workspaces[0].Panes[0]
	if got.WindowName != "wiki-r5-acceptance-tester" {
		t.Fatalf("pane WindowName = %q, want %q", got.WindowName, "wiki-r5-acceptance-tester")
	}
	if got.Session != "alpha" {
		t.Fatalf("pane Session = %q, want alpha (session name must be preserved)", got.Session)
	}
}

// TestDiscoverToleratesDeadSocket is the red-line test: a stale socket (inode
// present, no listener) mixed into the directory must be skipped and must not
// abort the scan or affect the result.
func TestDiscoverToleratesDeadSocket(t *testing.T) {
	root := testSocketRoot(t)
	tmp := t.TempDir()
	cwd := mkdirTmp(t, tmp, "ws-dead")
	cwdWant, err := filepath.EvalSymlinks(cwd)
	if err != nil {
		t.Fatal(err)
	}
	startTestServer(t, root, "live", cwd, "-s", "live")
	createStaleSocket(t, filepath.Join(testSocketDir(t, root), fmt.Sprintf("test-disc-%d-stale", os.Getpid())))

	model, err := DiscoverWithDirs(context.Background(), discardLogger(), []string{testSocketDir(t, root)})
	if err != nil {
		t.Fatalf("DiscoverWithDirs with a dead socket: %v", err)
	}
	if len(model.Workspaces) != 1 || model.Workspaces[0].CWD != cwdWant || model.Workspaces[0].Count() != 1 {
		t.Fatalf("dead socket affected the result: %+v", model.Workspaces)
	}
}

// TestDiscoverToleratesRegularFile checks that stray non-socket files in the
// socket directory are also skipped without aborting the scan.
func TestDiscoverToleratesRegularFile(t *testing.T) {
	root := testSocketRoot(t)
	tmp := t.TempDir()
	cwd := mkdirTmp(t, tmp, "ws-regular")
	startTestServer(t, root, "live", cwd, "-s", "live")
	if err := os.WriteFile(filepath.Join(testSocketDir(t, root), "not-a-socket"), []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}

	model, err := DiscoverWithDirs(context.Background(), discardLogger(), []string{testSocketDir(t, root)})
	if err != nil {
		t.Fatalf("DiscoverWithDirs with a stray file: %v", err)
	}
	if len(model.Workspaces) != 1 || model.Workspaces[0].Count() != 1 {
		t.Fatalf("stray file affected the result: %+v", model.Workspaces)
	}
	// Stray file must not have been counted as a pane or altered the cwd.
	if model.Workspaces[0].Panes[0].Session != "live" {
		t.Fatalf("unexpected pane after stray file: %+v", model.Workspaces)
	}
}

// TestDiscoverEmptyModel checks the empty case (no servers): an existing socket
// directory with no socket files yields an empty model and no error. Paired
// with TestDiscoverAggregatesSameCWDAcrossSockets as the non-empty control.
func TestDiscoverEmptyModel(t *testing.T) {
	root := testSocketRoot(t)
	if err := os.Mkdir(testSocketDir(t, root), 0o700); err != nil {
		t.Fatal(err)
	}
	model, err := DiscoverWithDirs(context.Background(), discardLogger(), []string{testSocketDir(t, root)})
	if err != nil {
		t.Fatalf("DiscoverWithDirs on empty dir: %v", err)
	}
	if len(model.Workspaces) != 0 {
		t.Fatalf("got %d workspaces, want 0", len(model.Workspaces))
	}
}

// TestDiscoverMissingSocketDir checks that a socket directory which does not
// exist at all is skipped gracefully rather than failing the scan.
func TestDiscoverMissingSocketDir(t *testing.T) {
	tmp := t.TempDir()
	model, err := DiscoverWithDirs(context.Background(), discardLogger(), []string{filepath.Join(tmp, "nope")})
	if err != nil {
		t.Fatalf("DiscoverWithDirs on missing dir: %v", err)
	}
	if len(model.Workspaces) != 0 {
		t.Fatalf("got %d workspaces, want 0", len(model.Workspaces))
	}
}

// TestDiscoverHonorsCancellation checks that a canceled context surfaces as an
// error instead of being swallowed as a skipped socket.
func TestDiscoverHonorsCancellation(t *testing.T) {
	root := testSocketRoot(t)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := DiscoverWithDirs(ctx, discardLogger(), []string{testSocketDir(t, root)}); err == nil {
		t.Fatal("want error when ctx is already canceled")
	}
}
