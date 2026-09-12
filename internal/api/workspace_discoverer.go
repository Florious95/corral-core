package api

import (
	"context"
	"errors"
	"os"
	"time"

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
	model, err := discovery.DiscoverIndexed(ctx, d.logger, d.socketDirs, d.index)
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
	d.index.Observe(&discovery.Model{}, removed)
	return model, nil
}

func (d *indexedDiscoverer) DiscoverWorkspace(ctx context.Context, cwd string) (*discovery.Model, error) {
	sockets, err := d.index.WaitSockets(ctx, cwd)
	if err != nil {
		return nil, err
	}
	started := time.Now()
	model, err := discovery.DiscoverSockets(ctx, d.logger, sockets)
	if err != nil {
		return nil, err
	}
	// Enumeration sees all workspaces on these shared sockets. Publish their
	// fresh routes too, so discovering B during an A refresh is not discarded.
	d.index.ObservePartial(model, started)
	out := &discovery.Model{}
	if ws := model.Workspace(cwd); ws != nil {
		out.Workspaces = []discovery.Workspace{*ws}
	}
	return out, nil
}
