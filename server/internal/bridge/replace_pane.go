package bridge

// ReplacePaneProcess upgrades a managed worker without killing its pane.
// @contract
// @pre the caller owns this exact pane and obtained user consent to stop work
// @post old process exits before respawn; the socket/pane/cwd are unchanged
// @err a failed replacement leaves a dead pane visible instead of deleting it
// @inv no keystroke injection, no sibling pane/session changes, bounded waits

import (
	"context"
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
	"syscall"
	"time"
)

// RememberManagedCWD is lifecycle metadata, used only when tmux cannot read
// a replacing/dead process's cwd. A live process's cwd always wins.
func RememberManagedCWD(ctx context.Context, socket, pane, cwd string) error {
	_, err := runTmux(ctx, socket, defaultTimeout, "set-option", "-p", "-t", pane, "@corral_cwd", cwd)
	return err
}

func ReplacePaneProcess(ctx context.Context, socket, pane, cwd, oldIPC string, args []string) (func(), error) {
	if !strings.HasPrefix(pane, "%") || cwd == "" || len(args) == 0 {
		return nil, errors.New("invalid replacement target")
	}
	out, err := runTmux(ctx, socket, defaultTimeout, "display-message", "-p", "-t", pane, "#{pane_pid}|#{pane_dead}|#{remain-on-exit}")
	if err != nil {
		return nil, err
	}
	fields := strings.Split(strings.TrimSpace(string(out)), "|")
	if len(fields) != 3 {
		return nil, errors.New("pane identity unavailable")
	}
	pid, err := strconv.Atoi(fields[0])
	if err != nil || pid <= 1 || fields[1] != "0" {
		return nil, errors.New("managed worker is no longer running")
	}
	if err := RememberManagedCWD(ctx, socket, pane, cwd); err != nil {
		return nil, err
	}
	if _, err := runTmux(ctx, socket, defaultTimeout, "set-option", "-p", "-t", pane, "remain-on-exit", "on"); err != nil {
		return nil, err
	}
	// The pane stays while its old worker drains/reaps the Pi process group.
	if err := syscall.Kill(pid, syscall.SIGTERM); err != nil && !errors.Is(err, syscall.ESRCH) {
		return nil, fmt.Errorf("stop worker: %w", err)
	}
	deadline := time.Now().Add(8 * time.Second)
	for {
		out, err = runTmux(ctx, socket, defaultTimeout, "display-message", "-p", "-t", pane, "#{pane_pid}|#{pane_dead}")
		if err != nil {
			return nil, err
		}
		identity := strings.Split(strings.TrimSpace(string(out)), "|")
		if len(identity) != 2 || identity[0] != fields[0] {
			return nil, errors.New("pane changed during replacement")
		}
		_, ipcErr := os.Lstat(oldIPC)
		if identity[1] == "1" && errors.Is(syscall.Kill(pid, 0), syscall.ESRCH) && os.IsNotExist(ipcErr) {
			// Its defer cleanup cannot now unlink the replacement's socket.
			break
		}
		if time.Now().After(deadline) {
			return nil, errors.New("old worker did not exit; pane preserved")
		}
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(50 * time.Millisecond):
		}
	}
	quoted := make([]string, len(args))
	for i, arg := range args {
		quoted[i] = shellQuote(arg)
	}
	if _, err := runTmux(ctx, socket, defaultTimeout, "respawn-pane", "-t", pane, "-c", cwd, strings.Join(quoted, " ")); err != nil {
		return nil, err
	}
	// Restore the user's original exit policy only after the replacement is ready.
	return func() {
		_, _ = runTmux(context.Background(), socket, defaultTimeout, "set-option", "-p", "-t", pane, "remain-on-exit", fields[2])
	}, nil
}
