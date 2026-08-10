package api

// discoverer.go declares the tmux-scanning seam. The default implementation
// enumerates every server socket on the host (discovery.Discover); tests and
// future scoped deployments inject a Discoverer that returns a fixed or
// scripted snapshot without touching any real socket.

import (
	"context"
	"log/slog"
	"os"
	"path/filepath"

	"github.com/agentmirror/agentmirror/internal/discovery"
)

// scopedDiscoveryDirsEnv is intentionally an e2e-only bridge: cmd remains
// unchanged, while an isolated real daemon can opt into the same fail-closed
// directory list as Options.DiscoverySocketDirs. Unset means the production
// discovery.Discover path exactly as before; set-but-empty means scan none.
const scopedDiscoveryDirsEnv = "AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS"

// Discoverer produces one fresh two-level tmux snapshot. It is the polling
// primitive the listing loop drives; no caching is expected inside.
type Discoverer interface {
	// Discover scans the host's tmux servers and returns the aggregated
	// workspace model. The returned model is pure data.
	Discover(ctx context.Context) (*discovery.Model, error)
}

// tmuxDiscoverer is the production Discoverer: every tmux server socket on
// the host, with dead sockets skipped by discovery itself.
type tmuxDiscoverer struct {
	logger     *slog.Logger
	socketDirs []string
}

func (d tmuxDiscoverer) Discover(ctx context.Context) (*discovery.Model, error) {
	if d.socketDirs != nil {
		// A non-nil slice is an explicit isolation boundary. In particular,
		// do not call DefaultSocketDirs here: it deliberately adds the host
		// defaults even when TMUX_TMPDIR is set.
		return discovery.DiscoverWithDirs(ctx, d.logger, d.socketDirs)
	}
	return discovery.Discover(ctx, d.logger)
}

// resolvedDiscoverySocketDirs copies an explicit Go option, or consumes the
// e2e-only environment bridge when the option is nil. A non-nil return is an
// isolation boundary, including an intentionally empty one.
func resolvedDiscoverySocketDirs(configured []string) []string {
	if configured != nil {
		return append([]string{}, configured...)
	}
	raw, ok := os.LookupEnv(scopedDiscoveryDirsEnv)
	if !ok {
		return nil
	}
	dirs := filepath.SplitList(raw)
	if len(dirs) == 0 {
		return []string{}
	}
	return dirs
}
