package api

import (
	"context"
	"errors"
	"os"

	"github.com/agentmirror/agentmirror/internal/discovery"
)

// WorkspaceDiscoverer is an optional refinement of the existing seam. Legacy
// injected discoverers retain the original coordinator semantics. The default
// production tmux discoverer implements both paths through indexedDiscoverer.
// Implementations must support concurrent host and workspace queries.
type WorkspaceDiscoverer interface {
	Discoverer
	DiscoverWorkspace(context.Context, string) (*discovery.Model, error)
}

type indexedDiscoverer struct {
	tmuxDiscoverer
	index *discovery.WorkspaceIndex
}

func (d *indexedDiscoverer) Discover(ctx context.Context) (*discovery.Model, error) {
	model, err := d.tmuxDiscoverer.Discover(ctx)
	if err != nil {
		return nil, err
	}
	if model == nil {
		return nil, errors.New("discovery: nil host inventory")
	}
	var removed []string
	for _, socket := range d.index.KnownSockets() {
		if _, err := os.Lstat(socket); os.IsNotExist(err) {
			removed = append(removed, socket)
		}
	}
	// Publish routing before the caller starts the expensive global status
	// sampling. Unrelated sampler failures cannot hide a healthy workspace.
	d.index.Observe(model, removed)
	return model, nil
}

func (d *indexedDiscoverer) DiscoverWorkspace(ctx context.Context, cwd string) (*discovery.Model, error) {
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-d.index.Ready():
	}
	model, err := discovery.DiscoverSockets(ctx, d.logger, d.index.Sockets(cwd))
	if err != nil {
		return nil, err
	}
	out := &discovery.Model{}
	if ws := model.Workspace(cwd); ws != nil {
		out.Workspaces = []discovery.Workspace{*ws}
	}
	return out, nil
}
