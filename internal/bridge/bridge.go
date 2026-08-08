package bridge

// bridge.go implements the single-pane terminal bridge primitive: first-frame
// snapshot (capture-pane -e), scrollback paging (capture-pane -S/-E), whole
// input injection with a decidable ack (send-keys / paste-buffer, requirement
// 003), and resize (window-size latest + resize-window, requirement 005).
//
// A Pane is mirror-and-inject only: it never kills, detaches, or otherwise
// mutates the target pane's runtime state beyond what the caller explicitly
// requests. That is the hard red line of this task.

import (
	"context"
	"fmt"
	"strconv"
	"strings"
	"time"
)

// Pane bridges a single tmux pane. The pane is addressed by its bare pane id
// (e.g. "%0") as produced by the discovery layer; bare ids are required
// because tmux resolves session:window.pane targets to every pane in the
// window, which breaks exact existence checks. All methods share the pane's
// per-command timeout.
type Pane struct {
	socket  string
	target  string // bare pane id "%N"
	timeout time.Duration
}

// NewPane returns a Pane bound to a bare pane id on the given tmux socket.
// The empty socket means the tmux default socket. The default per-command
// timeout applies unless overridden via WithTimeout.
func NewPane(socket, paneID string) *Pane {
	return &Pane{socket: socket, target: paneID, timeout: defaultTimeout}
}

// WithTimeout returns a copy of p with a custom per-command timeout. Useful
// for slow clients whose pane operations legitimately take longer.
func (p *Pane) WithTimeout(d time.Duration) *Pane {
	cp := *p
	cp.timeout = d
	return &cp
}

// Snapshot captures the pane's visible screen as raw terminal bytes, ANSI
// color escapes preserved (capture-pane -e). It is the first frame a
// subscriber draws before switching to the incremental stream (requirement
// 006's "video fast-open").
func (p *Pane) Snapshot(ctx context.Context) ([]byte, error) {
	return runTmux(ctx, p.socket, p.timeout, "capture-pane", "-e", "-p", "-t", p.target)
}

// Scrollback fetches one page of history strictly above the visible screen.
// start and end are negative line offsets relative to the screen bottom
// (e.g. -30..-21 for the ten lines just above the top of the screen); paging
// parameters come from the caller per requirement 006. Returns raw bytes.
func (p *Pane) Scrollback(ctx context.Context, start, end int) ([]byte, error) {
	return runTmux(ctx, p.socket, p.timeout,
		"capture-pane", "-e", "-p", "-t", p.target,
		"-S", strconv.Itoa(start), "-E", strconv.Itoa(end))
}

// Inject sends the whole message in one shot and presses Enter, then returns
// a decidable ack: a non-nil error means the input did not go in (pane was
// already gone or the server unreachable), which is requirement 003's
// "发送必达". Single-line text goes through send-keys -l (literal); multi-line
// text goes through load-buffer + paste-buffer, which tmux handles more
// reliably for embedded newlines. Carriage returns are normalized away so a
// phone line-ending cannot inject stray keystrokes.
func (p *Pane) Inject(ctx context.Context, text string) error {
	if err := p.requirePane(ctx); err != nil {
		return err
	}
	text = strings.ReplaceAll(text, "\r", "")

	var err error
	if strings.Contains(text, "\n") {
		err = p.pasteMultiline(ctx, text)
	} else {
		_, err = runTmux(ctx, p.socket, p.timeout, "send-keys", "-t", p.target, "-l", "--", text)
	}
	if err != nil {
		return err
	}
	// One Enter commits the whole injected message (whole-input paradigm).
	_, err = runTmux(ctx, p.socket, p.timeout, "send-keys", "-t", p.target, "Enter")
	return err
}

// pasteMultiline injects a multi-line message via a named tmux buffer: it is
// pasted verbatim (bracketed paste, so the target CLI treats it as one paste
// rather than a burst of keystrokes) and the buffer is deleted on the spot.
func (p *Pane) pasteMultiline(ctx context.Context, text string) error {
	buf := newBufferName()
	// load-buffer -b <name> - reads the buffer from stdin.
	if err := p.loadBuffer(ctx, buf, text); err != nil {
		return err
	}
	if _, err := runTmux(ctx, p.socket, p.timeout,
		"paste-buffer", "-b", buf, "-t", p.target, "-d", "-p"); err != nil {
		return err
	}
	return nil
}

// loadBuffer pipes text into a named tmux buffer. It is a plain stdin pipe to
// tmux, not an exec-wrapped command, because the payload is the stdin stream.
func (p *Pane) loadBuffer(ctx context.Context, name, text string) error {
	cmd, stderr, ctx, cancel := newTmuxCommand(ctx, p.socket, p.timeout, "load-buffer", "-b", name, "-")
	defer cancel()
	cmd.Stdin = strings.NewReader(text)
	if err := cmd.Run(); err != nil {
		if ctx.Err() != nil {
			return ErrTmuxTimeout
		}
		return classifyTmuxError(stderr.String())
	}
	return nil
}

// requirePane is the exact existence pre-check that makes every ack
// decidable (requirement 003). Because the target is a bare pane id, tmux
// resolves it to exactly one line; a multi-pane resolution or a "can't find"
// from tmux both fail the check.
func (p *Pane) requirePane(ctx context.Context) error {
	out, err := runTmux(ctx, p.socket, p.timeout, "list-panes", "-t", p.target, "-F", "#{pane_id}")
	if err != nil {
		return err
	}
	ids := strings.Fields(string(out))
	if len(ids) != 1 || ids[0] != p.target {
		return fmt.Errorf("%w: target %q resolves to %d panes", ErrPaneNotFound, p.target, len(ids))
	}
	return nil
}

// Resize sets the pane's window to cols x rows (window-size latest + tmux
// 3.2+ resize-window) and returns the pane's actual new size as read back
// from the server, so the caller sees the truth, not the request. Resize is
// a primitive only: the "whose last operation wins" grouping policy
// (requirement 005) belongs to the layer that owns sessions.
func (p *Pane) Resize(ctx context.Context, cols, rows int) (width, height int, err error) {
	winID, err := p.windowID(ctx)
	if err != nil {
		return 0, 0, err
	}
	// window-size latest makes the resize stick instead of being overridden
	// by an attached client's dimensions.
	if _, err := runTmux(ctx, p.socket, p.timeout, "set-option", "-w", "-t", winID, "window-size", "latest"); err != nil {
		return 0, 0, err
	}
	if _, err := runTmux(ctx, p.socket, p.timeout,
		"resize-window", "-t", winID, "-x", strconv.Itoa(cols), "-y", strconv.Itoa(rows)); err != nil {
		return 0, 0, err
	}
	return p.size(ctx)
}

// windowID resolves the tmux window id ("@N") owning this pane, which is the
// target resize-window and set-option operate on.
func (p *Pane) windowID(ctx context.Context) (string, error) {
	out, err := runTmux(ctx, p.socket, p.timeout, "display-message", "-p", "-t", p.target, "#{window_id}")
	if err != nil {
		return "", err
	}
	id := strings.TrimSpace(string(out))
	if !strings.HasPrefix(id, "@") {
		return "", fmt.Errorf("tmux: could not resolve window for pane %s", p.target)
	}
	return id, nil
}

// size reads the pane's actual dimensions from the server.
func (p *Pane) size(ctx context.Context) (int, int, error) {
	out, err := runTmux(ctx, p.socket, p.timeout, "display-message", "-p", "-t", p.target, "#{pane_width}x#{pane_height}")
	if err != nil {
		return 0, 0, err
	}
	var w, h int
	if _, err := fmt.Sscanf(strings.TrimSpace(string(out)), "%dx%d", &w, &h); err != nil {
		return 0, 0, fmt.Errorf("tmux: parse pane size %q: %w", strings.TrimSpace(string(out)), err)
	}
	return w, h, nil
}

// Pane.Socket exposes the socket the pane is bound to (used by the stream
// layer to build its own commands).
func (p *Pane) Socket() string { return p.socket }

// Pane.Target exposes the bare pane id (used by the stream layer).
func (p *Pane) Target() string { return p.target }

// Pane.Timeout exposes the per-command timeout (used by the stream layer).
func (p *Pane) Timeout() time.Duration { return p.timeout }
