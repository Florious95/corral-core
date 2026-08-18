package overlay

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/creack/pty"
)

const (
	scratchSession = "am-overlay"
	ScratchCols    = 80
	ScratchRows    = 24

	tmuxCmdTimeout   = 1500 * time.Millisecond
	snapshotWait     = 80 * time.Millisecond
	maxFrameBytes    = 256 * 1024
	keepFrameTail    = 128 * 1024
	slowSnapshotWarn = 200 * time.Millisecond
)

var spinner = []rune{'⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏'}

// Tmux captures choose-tree by attaching a dedicated client to a scratch
// session on the same tmux server as the user's panes. It never attaches a
// user session. SocketDirs nil means production default dirs; a non-nil
// slice (including empty) is an isolation boundary.
type Tmux struct {
	log  *slog.Logger
	dirs []string

	mu      sync.Mutex
	cmd     *exec.Cmd
	ptmx    *os.File
	sock    string
	client  string
	started bool
	spin    int

	frameMu sync.Mutex
	latest  []byte

	captures atomic.Int64
	clients  atomic.Int64
}

func NewTmux(log *slog.Logger, socketDirs []string) *Tmux {
	if log == nil {
		log = slog.New(slog.DiscardHandler)
	}
	return &Tmux{log: log, dirs: socketDirs}
}

func (t *Tmux) CaptureCount() int64 { return t.captures.Load() }
func (t *Tmux) ClientCount() int64  { return t.clients.Load() }

func (t *Tmux) Start(ctx context.Context) error {
	t0 := time.Now()
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.started && t.cmd != nil && t.cmd.Process != nil && t.cmd.ProcessState == nil {
		return nil
	}
	t.teardownLocked()

	sock, err := t.pickSocket(ctx)
	pickDur := time.Since(t0)
	if err != nil {
		t.log.Warn("overlay: pickSocket failed",
			"dirs", t.socketDirs(),
			"dir_count", len(t.socketDirs()),
			"dur", pickDur,
			"err", err,
		)
		return err
	}
	t.sock = sock
	t1 := time.Now()
	if err := t.ensureScratch(ctx, sock); err != nil {
		return err
	}
	scratchDur := time.Since(t1)

	// Attach must outlive this Start call: bind it to ctx (the overlay loop
	// lifetime), not a per-command timeout. Stop() kills the process.
	cmd := exec.CommandContext(ctx, "tmux", "-S", sock, "attach-session", "-t", scratchSession)
	cmd.Env = overlayChildEnv()
	t2 := time.Now()
	ptmx, err := pty.StartWithSize(cmd, &pty.Winsize{Rows: ScratchRows, Cols: ScratchCols})
	if err != nil {
		return fmt.Errorf("overlay attach scratch: %w", err)
	}
	t.cmd = cmd
	t.ptmx = ptmx
	t.started = true
	t.clients.Store(1)
	t.storeLatest(nil)
	// Drain immediately so attach/choose-tree output cannot fill the PTY and
	// stall later control commands (select-pane / refresh-client / list-clients).
	go t.drainPTY(ptmx)
	attachDur := time.Since(t2)

	t3 := time.Now()
	deadline := time.Now().Add(tmuxCmdTimeout)
	for time.Now().Before(deadline) {
		name, err := t.clientName(ctx, sock)
		if err == nil && name != "" {
			t.client = name
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	clientWait := time.Since(t3)
	if t.client == "" {
		t.teardownLocked()
		return fmt.Errorf("overlay: no scratch client after attach sock=%s client_wait=%s", sock, clientWait)
	}
	t4 := time.Now()
	if err := runTmux(ctx, sock, "choose-tree", "-t", scratchSession+":0.0"); err != nil {
		t.teardownLocked()
		return fmt.Errorf("overlay choose-tree: %w", err)
	}
	t.log.Info("overlay: scratch client started",
		"socket", sock,
		"session", scratchSession,
		"client", t.client,
		"winsize", fmt.Sprintf("%dx%d", ScratchCols, ScratchRows),
		"pick_ms", pickDur.Milliseconds(),
		"scratch_ms", scratchDur.Milliseconds(),
		"attach_ms", attachDur.Milliseconds(),
		"client_wait_ms", clientWait.Milliseconds(),
		"choose_tree_ms", time.Since(t4).Milliseconds(),
		"total_ms", time.Since(t0).Milliseconds(),
	)
	return nil
}

func (t *Tmux) Snapshot(ctx context.Context) ([]byte, error) {
	t0 := time.Now()
	t.mu.Lock()
	if !t.started || t.ptmx == nil {
		t.mu.Unlock()
		return nil, fmt.Errorf("overlay: snapshot before start")
	}
	t.captures.Add(1)
	t.spin++
	spin := t.spin
	sock := t.sock
	client := t.client
	t.mu.Unlock()

	title := fmt.Sprintf("%c ov-spin %d", spinner[spin%len(spinner)], spin)
	// Only our scratch pane title — never a user pane. Do not hold t.mu
	// across these calls: a full PTY used to deadlock here (tmux blocked
	// writing the client, Snapshot blocked in refresh-client holding mu
	// so nobody could Read).
	errPane := runTmux(ctx, sock, "select-pane", "-t", scratchSession+":0.0", "-T", title)
	var errRef error
	if client != "" {
		errRef = runTmux(ctx, sock, "refresh-client", "-t", client)
	}

	deadline := time.Now().Add(snapshotWait)
	var out []byte
	for {
		out = t.loadLatest()
		if len(bytes.TrimSpace(out)) > 0 || !time.Now().Before(deadline) {
			break
		}
		time.Sleep(5 * time.Millisecond)
	}
	usedFallback := false
	if len(bytes.TrimSpace(out)) == 0 {
		// PTY sometimes yields 0 this tick; still emit the title we just
		// painted so the stream is observably dynamic (feasibility: refresh
		// is what drives redraw, not automatic title updates).
		out = []byte(title)
		usedFallback = true
	}
	dur := time.Since(t0)
	if dur >= slowSnapshotWarn || usedFallback {
		t.log.Info("overlay: snapshot",
			"title", title,
			"pty_bytes", len(out),
			"used_title_fallback", usedFallback,
			"select_pane_err", errString(errPane),
			"refresh_err", errString(errRef),
			"dur_ms", dur.Milliseconds(),
			"slow_threshold_ms", slowSnapshotWarn.Milliseconds(),
		)
	} else {
		t.log.Debug("overlay: snapshot",
			"pty_bytes", len(out),
			"dur_ms", dur.Milliseconds(),
		)
	}
	return out, nil
}

func (t *Tmux) Stop() {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.teardownLocked()
}

func (t *Tmux) teardownLocked() {
	if t.ptmx != nil {
		_ = t.ptmx.Close()
		t.ptmx = nil
	}
	if t.cmd != nil && t.cmd.Process != nil {
		_ = t.cmd.Process.Kill()
		_, _ = t.cmd.Process.Wait()
	}
	t.cmd = nil
	t.client = ""
	t.started = false
	t.clients.Store(0)
	t.storeLatest(nil)
}

func (t *Tmux) drainPTY(f *os.File) {
	tmp := make([]byte, 8192)
	var acc bytes.Buffer
	for {
		n, err := f.Read(tmp)
		if n > 0 {
			acc.Write(tmp[:n])
			if acc.Len() > maxFrameBytes {
				b := acc.Bytes()
				acc.Reset()
				acc.Write(b[len(b)-keepFrameTail:])
			}
			t.storeLatest(append([]byte(nil), acc.Bytes()...))
		}
		if err != nil {
			return
		}
	}
}

func (t *Tmux) storeLatest(b []byte) {
	t.frameMu.Lock()
	t.latest = b
	t.frameMu.Unlock()
}

func (t *Tmux) loadLatest() []byte {
	t.frameMu.Lock()
	defer t.frameMu.Unlock()
	if t.latest == nil {
		return nil
	}
	return append([]byte(nil), t.latest...)
}

func (t *Tmux) socketDirs() []string {
	if t.dirs == nil {
		return defaultSocketDirs()
	}
	return t.dirs
}

func (t *Tmux) pickSocket(ctx context.Context) (string, error) {
	dirs := t.socketDirs()
	var tried []string
	for _, dir := range dirs {
		ents, err := os.ReadDir(dir)
		if err != nil {
			tried = append(tried, dir+"(readdir:"+err.Error()+")")
			continue
		}
		for _, e := range ents {
			if e.IsDir() {
				continue
			}
			sock := filepath.Join(dir, e.Name())
			if err := runTmux(ctx, sock, "list-sessions"); err == nil {
				t.log.Debug("overlay: pickSocket",
					"sock", sock,
					"dirs", dirs,
					"dir_count", len(dirs),
				)
				return sock, nil
			}
			tried = append(tried, sock)
		}
	}
	return "", fmt.Errorf("overlay: no reachable tmux socket dirs=%d tried=%v", len(dirs), tried)
}

func (t *Tmux) ensureScratch(ctx context.Context, sock string) error {
	if err := runTmux(ctx, sock, "has-session", "-t", scratchSession); err != nil {
		if err := runTmux(ctx, sock, "new-session", "-d", "-s", scratchSession, "-n", "tree",
			"-x", fmt.Sprintf("%d", ScratchCols), "-y", fmt.Sprintf("%d", ScratchRows),
			"sleep", "3600"); err != nil {
			return fmt.Errorf("overlay new-session scratch: %w", err)
		}
	}
	_ = runTmux(ctx, sock, "set-option", "-t", scratchSession, "-w", "window-size", "manual")
	_ = runTmux(ctx, sock, "resize-window", "-t", scratchSession+":0",
		"-x", fmt.Sprintf("%d", ScratchCols), "-y", fmt.Sprintf("%d", ScratchRows))
	return nil
}

func (t *Tmux) clientName(ctx context.Context, sock string) (string, error) {
	out, err := tmuxOutput(ctx, sock, "list-clients", "-F", "#{client_name} #{session_name}")
	if err != nil {
		return "", err
	}
	for _, line := range strings.Split(out, "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		name, sess, ok := strings.Cut(line, " ")
		if ok && sess == scratchSession && name != "" {
			return name, nil
		}
	}
	return "", io.EOF
}

func runTmux(ctx context.Context, sock string, args ...string) error {
	_, err := tmuxOutput(ctx, sock, args...)
	return err
}

func tmuxOutput(ctx context.Context, sock string, args ...string) (string, error) {
	cctx, cancel := context.WithTimeout(ctx, tmuxCmdTimeout)
	defer cancel()
	all := append([]string{"-S", sock}, args...)
	cmd := exec.CommandContext(cctx, "tmux", all...)
	cmd.Env = overlayChildEnv()
	out, err := cmd.CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("tmux %s: %w", strings.Join(args, " "), err)
	}
	return string(out), nil
}

func overlayChildEnv() []string {
	out := []string{"TERM=xterm-256color"}
	for _, e := range os.Environ() {
		if strings.HasPrefix(e, "TMUX=") || strings.HasPrefix(e, "TMUX_TMPDIR=") || strings.HasPrefix(e, "TERM=") {
			continue
		}
		out = append(out, e)
	}
	return out
}

func defaultSocketDirs() []string {
	uid := fmt.Sprintf("tmux-%d", os.Getuid())
	return []string{filepath.Join("/tmp", uid), filepath.Join("/private/tmp", uid)}
}

func errString(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}
