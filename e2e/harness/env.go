package main

// env.go manages the isolated runtime the harness drives: a throwaway
// TMUX_TMPDIR tree holding only our own tmux servers (never the real fleet —
// absolute red line), and a real agentmirrord daemon pointed at that tree.
// The daemon still scans the host's default socket dirs too (that is the
// product's normal fleet behavior, read-only); we simply assert on our own
// panes and never address another host's ref.

import (
	"context"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

// Env is one isolated e2e runtime. It owns the temp root, the tmux socket
// tree, the daemon process, and the upload dir.
type Env struct {
	// Root is the temp root holding everything this test touches.
	Root string
	// TmuxTmp is the TMUX_TMPDIR value for our isolated tmux servers.
	TmuxTmp string
	// Token is the pairing token the daemon was started with.
	Token string
	// WSURL is the daemon's WebSocket endpoint.
	WSURL string
	// UploadDir is where POST /upload writes.
	UploadDir string
	// UploadURL is the daemon's upload endpoint.
	UploadURL string
	// DaemonBin is the agentmirrord binary path (built by run.sh).
	DaemonBin string
	// StateDir is the daemon's single-instance state dir (isolated per test so
	// concurrent instances never collide on the pidfile flock).
	StateDir string

	// daemon is the running agentmirrord process (nil when stopped).
	daemon *exec.Cmd
	// daemonLog is the captured daemon stdout+stderr (pane captures go to
	// the artifacts dir too).
	daemonLog *os.File
	// port is the daemon's listen port.
	port int
}

// StartEnv builds an isolated environment: temp tree, one tmux server with the
// given panes (already created), and a daemon bound to the tree. onStdout, if
// non-nil, receives daemon log lines live (used to dump to artifacts).
func StartEnv(tb interface {
	Fatalf(format string, args ...any)
	Logf(format string, args ...any)
	Cleanup(func())
}, daemonBin, token string, port int) (*Env, error) {
	root, err := os.MkdirTemp("/tmp", "am-e2e-*")
	if err != nil {
		return nil, fmt.Errorf("mkdirtemp: %w", err)
	}
	tmuxTmp := filepath.Join(root, "tmux")
	if err := os.MkdirAll(tmuxTmp, 0o755); err != nil {
		os.RemoveAll(root)
		return nil, err
	}
	uploads := filepath.Join(root, "uploads")
	if err := os.MkdirAll(uploads, 0o755); err != nil {
		os.RemoveAll(root)
		return nil, err
	}
	stateDir := filepath.Join(root, "state")
	if err := os.MkdirAll(stateDir, 0o755); err != nil {
		os.RemoveAll(root)
		return nil, err
	}

	e := &Env{
		Root:      root,
		TmuxTmp:   tmuxTmp,
		Token:     token,
		UploadDir: uploads,
		DaemonBin: daemonBin,
		StateDir:  stateDir,
		port:      port,
		WSURL:     fmt.Sprintf("ws://127.0.0.1:%d/ws", port),
		UploadURL: fmt.Sprintf("http://127.0.0.1:%d/upload", port),
	}
	tb.Cleanup(func() {
		e.Cleanup()
	})
	return e, nil
}

// tmux runs a tmux command against our isolated socket tree, with TMUX
// stripped (never nest into a parent session) and TMUX_TMPDIR set so the
// server only ever lives on our own socket. It returns the trimmed stdout.
func (e *Env) tmux(ctx context.Context, args ...string) (string, error) {
	cmd := exec.CommandContext(ctx, "tmux", args...)
	cmd.Env = append(cleanEnv(os.Environ()), "TMUX_TMPDIR="+e.TmuxTmp)
	// Strip any inherited TMUX so a nested-session guard cannot trip.
	cmd.Env = stripEnv(cmd.Env, "TMUX")
	out, err := cmd.CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("tmux %v: %w: %s", args, err, strings.TrimSpace(string(out)))
	}
	return strings.TrimSpace(string(out)), nil
}

// Tmux runs tmux with a per-call timeout context.
func (e *Env) Tmux(args ...string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	return e.tmux(ctx, args...)
}

// StartDaemon launches agentmirrord bound to e's tree and port. It waits for
// the WS port to accept connections (the first scan of the host fleet runs
// inside the first list, so a bind is enough here).
//
// The daemon gets an explicit short TMPDIR inside the env root. This is
// critical: the bridge's pipe-pane FIFO is created under os.TempDir(), and a
// long temp path (the shell's /var/folders/...) combined with a long tmux
// socket path pushes the pipe-pane command string over the length limit, so
// tmux's cat never writes to the FIFO and no deltas flow. A short TMPDIR keeps
// the whole pipeline well under the limit (discovered 2026-08-09 e2e).
func (e *Env) StartDaemon(ctx context.Context) error {
	// The daemon's own temp root for FIFOs + pipe command strings. Created
	// fresh every start: a prior daemon's cleanup (or the test teardown) may
	// have removed it, and mkfifo fails with ENOENT if the parent is gone.
	tmpRoot := filepath.Join(e.Root, "dmtmp")
	if err := os.MkdirAll(tmpRoot, 0o755); err != nil {
		return err
	}
	cmd := exec.Command(e.DaemonBin,
		"-listen", fmt.Sprintf("127.0.0.1:%d", e.port),
		"-upload-dir", e.UploadDir,
		"-log-level", "debug",
		"-list-interval", "500ms",
	)
	cmd.Env = append(cleanEnv(os.Environ()),
		"TMUX_TMPDIR="+e.TmuxTmp,
		"TMPDIR="+tmpRoot,
		"AGENTMIRROR_TOKEN="+e.Token,
		// Isolated single-instance state: each test's daemon locks its own
		// pidfile flock, so concurrent e2e instances never collide (and the
		// real user config dir is never touched).
		"AGENTMIRROR_STATE_DIR="+e.StateDir,
	)
	logPath := filepath.Join(e.Root, "daemon.log")
	f, err := os.Create(logPath)
	if err != nil {
		return err
	}
	cmd.Stdout = f
	cmd.Stderr = f
	if err := cmd.Start(); err != nil {
		f.Close()
		return fmt.Errorf("start daemon: %w", err)
	}
	e.daemon = cmd
	e.daemonLog = f

	// Wait for the listener to come up (bounded poll).
	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		conn, err := net.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", e.port))
		if err == nil {
			conn.Close()
			return nil
		}
		select {
		case <-ctx.Done():
			return fmt.Errorf("daemon start interrupted: %w", ctx.Err())
		default:
		}
		time.Sleep(150 * time.Millisecond)
	}
	return fmt.Errorf("daemon did not open port %d (log: %s)", e.port, logPath)
}

// RestartDaemon kills and restarts the daemon, preserving the token and tree.
func (e *Env) RestartDaemon(ctx context.Context) error {
	e.StopDaemon()
	return e.StartDaemon(ctx)
}

// StopDaemon kills the daemon if running and flushes its log.
func (e *Env) StopDaemon() {
	if e.daemon != nil && e.daemon.Process != nil {
		_ = e.daemon.Process.Kill()
		_, _ = e.daemon.Process.Wait()
	}
	if e.daemonLog != nil {
		e.daemonLog.Close()
	}
	e.daemon = nil
	e.daemonLog = nil
}

// Cleanup tears down the whole environment.
func (e *Env) Cleanup() {
	e.StopDaemon()
	// Kill any tmux server we created (best-effort; the temp tree removal
	// also reaps them since their socket lives inside it).
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_, _ = e.tmux(ctx, "kill-server")
	_ = os.RemoveAll(e.Root)
}

// refsInWorkspace returns every session ref whose cwd equals cwd, from a
// decoded Listing control frame. It is the bridge between the listing model
// and the refs we subscribe to (we filter to our own isolated workspace and
// never touch anything else on the host).
func refsInWorkspace(l protocol.Listing, cwd string) []string {
	var refs []string
	for i := range l.Workspaces {
		if l.Workspaces[i].Cwd != cwd {
			continue
		}
		for j := range l.Workspaces[i].Sessions {
			refs = append(refs, l.Workspaces[i].Sessions[j].Ref)
		}
	}
	return refs
}

// workspacesPresent returns the set of cwd keys in a listing (used for the
// two-level assertion: our isolated cwds must appear).
func workspacesPresent(l protocol.Listing) map[string]bool {
	out := make(map[string]bool, len(l.Workspaces))
	for i := range l.Workspaces {
		out[l.Workspaces[i].Cwd] = true
	}
	return out
}

// refsInSession returns every session ref whose display name equals name
// (the tmux session label). It is used to single out a specific pane (e.g.
// the real-CLI session) when multiple panes share one cwd.
func refsInSession(l protocol.Listing, name string) []string {
	var refs []string
	for i := range l.Workspaces {
		for j := range l.Workspaces[i].Sessions {
			s := l.Workspaces[i].Sessions[j]
			if s.Name == name {
				refs = append(refs, s.Ref)
			}
		}
	}
	return refs
}

// cleanEnv returns a copy of the inherited environment. Every variable the
// harness sets explicitly on a child (TMUX_TMPDIR, TMPDIR, AGENTMIRROR_TOKEN)
// is stripped first: on POSIX a child's getenv sees the FIRST match in the
// array, so a duplicate inherited TMPDIR=/var/folders/... would win over the
// short override appended later and break the bridge FIFO path (discovered
// 2026-08-09 e2e).
func cleanEnv(env []string) []string {
	out := make([]string, 0, len(env))
	for _, kv := range env {
		if strings.HasPrefix(kv, "TMUX_TMPDIR=") ||
			strings.HasPrefix(kv, "TMPDIR=") ||
			strings.HasPrefix(kv, "AGENTMIRROR_TOKEN=") ||
			strings.HasPrefix(kv, "AGENTMIRROR_STATE_DIR=") {
			continue // always overwritten below
		}
		out = append(out, kv)
	}
	return out
}

// stripEnv removes any variable named name from env.
func stripEnv(env []string, name string) []string {
	prefix := name + "="
	out := env[:0]
	for _, kv := range env {
		if strings.HasPrefix(kv, prefix) {
			continue
		}
		out = append(out, kv)
	}
	return out
}
