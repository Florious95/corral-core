package bridge

// tmux.go is the single execution seam for every tmux interaction in this
// package: one socket, one per-command timeout, and one error taxonomy. No
// other file here shells out to tmux directly, so the three decidable failure
// classes (pane gone / server dead / command hung) stay consistent across all
// bridge operations.

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os/exec"
	"strings"
	"time"
)

// tmuxBin is the tmux binary path. It is a package variable so tests can
// point it at a fake executable to exercise the timeout path.
var tmuxBin = "tmux"

// Sentinels are the decidable failure classes every bridge operation can
// surface (knowledge-base contract: pane missing / server dead / timeout).
var (
	// ErrPaneNotFound reports that the target pane (or its session/window)
	// does not exist.
	ErrPaneNotFound = errors.New("tmux: pane not found")

	// ErrServerUnreachable reports that the tmux server at the configured
	// socket could not be reached (missing or dead socket, connection
	// refused).
	ErrServerUnreachable = errors.New("tmux: server unreachable")

	// ErrTmuxTimeout reports that a tmux invocation exceeded its deadline
	// and was killed.
	ErrTmuxTimeout = errors.New("tmux: command timed out")
)

// defaultTimeout bounds every tmux invocation. tmux control commands on a
// live socket return in milliseconds; this is generous headroom for a loaded
// server without ever blocking a request for long.
const defaultTimeout = 10 * time.Second

// runTmux executes `tmux -S socket <args...>` with a per-call deadline and
// classifies failures into the sentinels above. A zero socket means the
// tmux default socket (`-L default`). All output is returned raw; the caller
// interprets bytes, never this function.
func runTmux(ctx context.Context, socket string, timeout time.Duration, args ...string) ([]byte, error) {
	var base []string
	if socket == "" {
		base = []string{"-L", "default"}
	} else {
		base = []string{"-S", socket}
	}

	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	cmd := exec.CommandContext(ctx, tmuxBin, append(base, args...)...)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr

	if err := cmd.Run(); err != nil {
		if ctx.Err() != nil {
			return nil, ErrTmuxTimeout
		}
		return nil, classifyTmuxError(stderr.String())
	}
	return stdout.Bytes(), nil
}

// classifyTmuxError maps tmux stderr text onto the typed error taxonomy. Text
// matching is intentionally small and stable across the supported tmux
// versions (3.6a); anything unrecognized is wrapped with the raw stderr so it
// is never silently swallowed.
func classifyTmuxError(stderr string) error {
	switch {
	case strings.Contains(stderr, "can't find pane"),
		strings.Contains(stderr, "can't find session"),
		strings.Contains(stderr, "can't find window"):
		return ErrPaneNotFound
	case strings.Contains(stderr, "no server running"),
		strings.Contains(stderr, "error connecting"),
		strings.Contains(stderr, "Connection refused"),
		strings.Contains(stderr, "No such file or directory"):
		return ErrServerUnreachable
	default:
		return fmt.Errorf("tmux failed: %s", strings.TrimSpace(stderr))
	}
}

// shellQuote wraps s in single quotes for /bin/sh so a FIFO path with spaces
// or shell metacharacters survives the pipe-pane command string.
func shellQuote(s string) string {
	return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'"
}
