// 已归档，2026-08-19 用户令暂不介入；展示不完全问题未修。
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
	// ScratchSession is the dedicated choose-tree host. It is the observation
	// apparatus (requirement 065) and must never appear in overlay frames.
	ScratchSession = "am-overlay"
	ScratchCols    = 80
	ScratchRows    = 24
	scratchFilter  = "#{!=:#{session_name}," + ScratchSession + "}"

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
	started  bool
	spin     int
	wantCols uint16
	wantRows uint16
	cols     uint16
	rows     uint16

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

func (t *Tmux) viewSize() (uint16, uint16) {
	cols, rows := t.wantCols, t.wantRows
	if cols < 20 {
		cols = ScratchCols
	}
	if rows < 8 {
		rows = ScratchRows
	}
	return cols, rows
}

// WantSize sets the scratch client PTY size from the overlay panel
// (pixel width/height ÷ cell). 0 leaves the last requested size.
func (t *Tmux) WantSize(cols, rows uint16) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if cols >= 20 {
		t.wantCols = cols
	}
	if rows >= 8 {
		t.wantRows = rows
	}
}

func (t *Tmux) Start(ctx context.Context, requested string) error {
	t0 := time.Now()
	t.mu.Lock()
	defer t.mu.Unlock()
	cols, rows := t.viewSize()
	if t.started && t.cmd != nil && t.cmd.Process != nil && t.cmd.ProcessState == nil && sameSocketPath(t.sock, requested) {
		if t.cols == cols && t.rows == rows {
			return nil
		}
		if err := t.resizeLocked(ctx, cols, rows); err != nil {
			return err
		}
		return nil
	}
	if t.started && t.sock != requested {
		t.log.Info("overlay: switch socket",
			"from", t.sock,
			"to", requested,
			"from_eq_to", t.sock == requested,
		)
		t.teardownLocked()
	} else {
		t.teardownLocked()
	}

	sock, err := t.resolveRequested(ctx, requested)
	pickDur := time.Since(t0)
	if err != nil {
		t.log.Warn("overlay: resolveRequested failed",
			"requested", requested,
			"dirs", t.socketDirs(),
			"dir_count", len(t.socketDirs()),
			"dur", pickDur,
			"err", err,
		)
		return err
	}
	t.log.Info("overlay: socket selected",
		"requested", requested,
		"used", sock,
		"match", requested == sock || sameSocketPath(requested, sock),
	)
	t.sock = sock
	t.cols, t.rows = cols, rows
	t1 := time.Now()
	if err := t.ensureScratch(ctx, sock, cols, rows); err != nil {
		return err
	}
	scratchDur := time.Since(t1)

	// Attach must outlive this Start call: bind it to ctx (the overlay loop
	// lifetime), not a per-command timeout. Stop() kills the process.
	cmd := exec.CommandContext(ctx, "tmux", "-S", sock, "attach-session", "-t", ScratchSession)
	cmd.Env = overlayChildEnv()
	t2 := time.Now()
	ptmx, err := pty.StartWithSize(cmd, &pty.Winsize{Rows: rows, Cols: cols})
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
	// -N: start without the preview pane (man tmux choose-tree).
	if err := runTmux(ctx, sock, chooseTreeArgs()...); err != nil {
		t.teardownLocked()
		return fmt.Errorf("overlay choose-tree: %w", err)
	}
	t.log.Info("overlay: scratch client started",
		"socket", sock,
		"session", ScratchSession,
		"client", t.client,
		"winsize", fmt.Sprintf("%dx%d", cols, rows),
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

	title := fmt.Sprintf("%c %d", spinner[spin%len(spinner)], spin)
	// Only our scratch pane title — never a user pane. Do not hold t.mu
	// across these calls: a full PTY used to deadlock here (tmux blocked
	// writing the client, Snapshot blocked in refresh-client holding mu
	// so nobody could Read). Title must not contain scratch tokens
	// (am-overlay / tree / sleep / ov-spin) — 065 forbids self-reflection.
	errPane := runTmux(ctx, sock, "select-pane", "-t", ScratchSession+":0.0", "-T", title)
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
	out = stripObserver(out)
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

func (t *Tmux) resolveRequested(ctx context.Context, requested string) (string, error) {
	requested = strings.TrimSpace(requested)
	if requested == "" {
		return "", fmt.Errorf("overlay: requested socket empty (refuse first-found)")
	}
	if err := runTmux(ctx, requested, "list-sessions"); err != nil {
		return "", fmt.Errorf("overlay: requested socket not reachable requested=%s err=%w", requested, err)
	}
	if !t.socketAllowed(requested) {
		return "", fmt.Errorf("overlay: requested socket %s outside allowed dirs %v", requested, t.socketDirs())
	}
	return requested, nil
}

func (t *Tmux) socketAllowed(sock string) bool {
	dirs := t.dirs
	if dirs == nil {
		dirs = defaultSocketDirs()
	}
	if len(dirs) == 0 {
		return false
	}
	for _, dir := range dirs {
		if socketUnderDir(dir, sock) {
			return true
		}
	}
	return false
}

func socketUnderDir(dir, sock string) bool {
	d, err := filepath.EvalSymlinks(dir)
	if err != nil {
		d = filepath.Clean(dir)
	}
	s, err := filepath.EvalSymlinks(sock)
	if err != nil {
		s = filepath.Clean(sock)
	}
	rel, err := filepath.Rel(d, s)
	if err != nil {
		return false
	}
	return rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator))
}

func sameSocketPath(a, b string) bool {
	if a == b {
		return true
	}
	ea, errA := filepath.EvalSymlinks(a)
	eb, errB := filepath.EvalSymlinks(b)
	if errA != nil {
		ea = filepath.Clean(a)
	}
	if errB != nil {
		eb = filepath.Clean(b)
	}
	return ea == eb
}

func chooseTreeArgs() []string {
	return []string{"choose-tree", "-N", "-f", scratchFilter, "-t", ScratchSession + ":0.0"}
}

func (t *Tmux) resizeLocked(ctx context.Context, cols, rows uint16) error {
	if t.ptmx != nil {
		if err := pty.Setsize(t.ptmx, &pty.Winsize{Rows: rows, Cols: cols}); err != nil {
			return fmt.Errorf("overlay pty resize: %w", err)
		}
	}
	if err := t.ensureScratch(ctx, t.sock, cols, rows); err != nil {
		return err
	}
	t.cols, t.rows = cols, rows
	if t.client != "" {
		_ = runTmux(ctx, t.sock, "refresh-client", "-t", t.client)
	}
	return nil
}

func (t *Tmux) ensureScratch(ctx context.Context, sock string, cols, rows uint16) error {
	if err := runTmux(ctx, sock, "has-session", "-t", ScratchSession); err != nil {
		if err := runTmux(ctx, sock, "new-session", "-d", "-s", ScratchSession, "-n", "tree",
			"-x", fmt.Sprintf("%d", cols), "-y", fmt.Sprintf("%d", rows),
			"sleep", "3600"); err != nil {
			return fmt.Errorf("overlay new-session scratch: %w", err)
		}
	}
	_ = runTmux(ctx, sock, "set-option", "-t", ScratchSession, "-w", "window-size", "manual")
	_ = runTmux(ctx, sock, "set-option", "-t", ScratchSession, "status", "off")
	_ = runTmux(ctx, sock, "resize-window", "-t", ScratchSession+":0",
		"-x", fmt.Sprintf("%d", cols), "-y", fmt.Sprintf("%d", rows))
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
		if ok && sess == ScratchSession && name != "" {
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

// stripObserver drops observation-apparatus tokens from a captured frame
// (requirement 065). The scratch session and its helper pane names must not
// appear in output even if choose-tree filter misses a status/title remnant.
func stripObserver(raw []byte) []byte {
	if len(raw) == 0 {
		return raw
	}
	banned := []string{ScratchSession, "ov-spin"}
	// Split on CR/LF without using bufio; keep non-banned pieces joined by \n.
	parts := bytes.FieldsFunc(raw, func(r rune) bool { return r == '\n' || r == '\r' })
	var keep [][]byte
	for _, p := range parts {
		s := string(p)
		drop := false
		for _, tok := range banned {
			if strings.Contains(s, tok) {
				drop = true
				break
			}
		}
		if !drop {
			keep = append(keep, p)
		}
	}
	if len(keep) == 0 {
		return nil
	}
	return bytes.Join(keep, []byte{'\n'})
}
