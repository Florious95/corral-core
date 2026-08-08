package discovery

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// paneFormat is the tmux format string used to enumerate every pane of a
// server in one query. Fields are "|"-separated; the last field is
// "<width>x<height>". The chosen fields are the two-level model inputs:
// session name (label only), window index, pane id, cwd (grouping key),
// foreground command, and dimensions.
const paneFormat = "#{session_name}|#{window_index}|#{pane_id}|#{pane_current_path}|#{pane_current_command}|#{pane_width}x#{pane_height}"

// socketTimeout bounds a single tmux query against a single socket so a hung
// server cannot stall the whole scan. A server that does not answer within
// this budget is treated as unreachable and skipped.
const socketTimeout = 5 * time.Second

// Discover scans every tmux server socket on the host and aggregates the panes
// into the two-level workspace model (requirements 001 and 002). It walks
// DefaultSocketDirs(), skipping unreachable or stale sockets so one dead server
// never fails the scan. The returned Model is a pure-data snapshot; no caching
// is performed.
func Discover(ctx context.Context, logger *slog.Logger) (*Model, error) {
	return DiscoverWithDirs(ctx, logger, DefaultSocketDirs())
}

// DiscoverWithDirs is Discover over an explicit list of socket directories,
// used by callers that need to override the discovery surface (tests inject
// isolated TMUX_TMPDIR trees here so no real socket is ever touched).
func DiscoverWithDirs(ctx context.Context, logger *slog.Logger, socketDirs []string) (*Model, error) {
	if logger == nil {
		logger = slog.New(slog.DiscardHandler)
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}

	var panes []Pane
	for _, dir := range socketDirs {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		sockets, err := listSocketFiles(dir)
		if err != nil {
			logger.Debug("discovery: skipping socket directory", "dir", dir, "err", err)
			continue
		}
		for _, sock := range sockets {
			if err := ctx.Err(); err != nil {
				return nil, err
			}
			ps, err := scanServer(ctx, sock, logger)
			if err != nil {
				// A stale socket (server exited without unlinking) or a refused
				// connection is normal; skip it and keep scanning (red line).
				logger.Debug("discovery: skipping unreachable socket", "socket", sock, "err", err)
				continue
			}
			panes = append(panes, ps...)
		}
	}
	return buildModel(panes), nil
}

// DefaultSocketDirs returns the socket directories the scan walks:
// the $TMUX_TMPDIR override tree if set, plus the platform defaults
// /tmp/tmux-<uid> and /private/tmp/tmux-<uid> (macOS resolves /tmp to
// /private/tmp, so both spellings are scanned but de-duplicated). Duplicate
// paths that resolve to the same directory are listed once so panes are never
// double-counted.
func DefaultSocketDirs() []string {
	uid := "tmux-" + strconv.Itoa(os.Getuid())
	var raw []string
	if d := os.Getenv("TMUX_TMPDIR"); d != "" {
		raw = append(raw, filepath.Join(d, uid))
	}
	raw = append(raw, filepath.Join("/tmp", uid), filepath.Join("/private/tmp", uid))

	seen := make(map[string]bool)
	dirs := make([]string, 0, len(raw))
	for _, d := range raw {
		resolved, err := filepath.EvalSymlinks(d)
		if err != nil {
			resolved = filepath.Clean(d) // dir may not exist; keep it, scan will skip
		}
		if seen[resolved] {
			continue
		}
		seen[resolved] = true
		dirs = append(dirs, d)
	}
	return dirs
}

// listSocketFiles returns every non-directory entry of one tmux socket
// directory. A missing directory is returned as an error so the caller can
// skip it; a directory holding only stale sockets still yields entries, and
// whether they are connectable is decided by scanServer.
func listSocketFiles(dir string) ([]string, error) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, err
	}
	sockets := make([]string, 0, len(entries))
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		sockets = append(sockets, filepath.Join(dir, e.Name()))
	}
	return sockets, nil
}

// scanServer enumerates every pane of the tmux server listening on socketPath.
// Any failure to connect (stale socket, refused connection, no server running)
// is returned as an error for the caller to skip — a dead socket is expected
// (tmux does not unlink its socket on exit) and must never abort the scan.
func scanServer(ctx context.Context, socketPath string, logger *slog.Logger) ([]Pane, error) {
	// Bound one query so a hung server cannot stall the whole scan.
	ctx, cancel := context.WithTimeout(ctx, socketTimeout)
	defer cancel()

	cmd := exec.CommandContext(ctx, "tmux", "-S", socketPath, "list-panes", "-a", "-F", paneFormat)
	// Strip TMUX so tmux never trips the nested-session guard and refuses to
	// run (this daemon legitimately runs attached to tmux itself).
	cmd.Env = envWithout(os.Environ(), "TMUX")

	out, err := cmd.CombinedOutput()
	if err != nil {
		return nil, fmt.Errorf("query tmux on %s: %w: %s", socketPath, err, strings.TrimSpace(string(out)))
	}

	var panes []Pane
	for _, line := range strings.Split(strings.TrimSpace(string(out)), "\n") {
		if line == "" {
			continue
		}
		p, ok := parsePaneLine(line)
		if !ok {
			logger.Debug("discovery: skipping unparsable pane line", "socket", socketPath, "line", line)
			continue
		}
		panes = append(panes, p)
	}
	return panes, nil
}

// parsePaneLine parses one line of paneFormat output into a Pane. It returns
// ok=false for malformed lines (wrong field count or a non-integer dimension)
// so the caller can skip the offending pane without failing the whole scan.
func parsePaneLine(line string) (Pane, bool) {
	parts := strings.Split(line, "|")
	if len(parts) != 6 {
		return Pane{}, false
	}

	win, err := strconv.Atoi(parts[1])
	if err != nil {
		return Pane{}, false
	}
	dims := strings.SplitN(parts[5], "x", 2)
	if len(dims) != 2 {
		return Pane{}, false
	}
	width, err := strconv.Atoi(dims[0])
	if err != nil {
		return Pane{}, false
	}
	height, err := strconv.Atoi(dims[1])
	if err != nil {
		return Pane{}, false
	}

	return Pane{
		Session:     parts[0],
		WindowIndex: win,
		PaneID:      parts[2],
		CWD:         parts[3],
		Command:     parts[4],
		Width:       width,
		Height:      height,
	}, true
}

// envWithout returns the process environment with the named variable removed.
func envWithout(environ []string, name string) []string {
	prefix := name + "="
	out := make([]string, 0, len(environ))
	for _, kv := range environ {
		if strings.HasPrefix(kv, prefix) {
			continue
		}
		out = append(out, kv)
	}
	return out
}
