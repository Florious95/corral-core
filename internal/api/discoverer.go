package api

// discoverer.go declares the tmux-scanning seam. The default implementation
// enumerates every server socket on the host (discovery.Discover); tests and
// future scoped deployments inject a Discoverer that returns a fixed or
// scripted snapshot without touching any real socket.

import (
	"context"
	"log/slog"

	"github.com/remote-agent/agentmirror/internal/discovery"
)

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
	logger *slog.Logger
}

func (d tmuxDiscoverer) Discover(ctx context.Context) (*discovery.Model, error) {
	return discovery.Discover(ctx, d.logger)
}
