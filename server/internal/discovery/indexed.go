package discovery

import (
	"context"
	"log/slog"
	"sort"
	"sync"
	"time"
)

// DiscoverIndexed uses the same allowlist and stale-socket rules as Discover,
// with four bounded enumeration workers and incremental route publication.
// It never broadens explicit directories, including an empty scope.
func DiscoverIndexed(ctx context.Context, logger *slog.Logger, dirs []string, index *WorkspaceIndex) (*Model, error) {
	if dirs == nil {
		dirs = DefaultSocketDirs()
	}
	var sockets []string
	seen := map[string]bool{}
	for _, dir := range dirs {
		if !classifySocketDirectory(dir).allowed {
			continue
		}
		paths, err := listSocketFiles(dir)
		if err != nil {
			continue
		}
		for _, socket := range paths {
			if !seen[socket] && classifySocket(socket).allowed {
				seen[socket] = true
				sockets = append(sockets, socket)
			}
		}
	}
	sort.Strings(sockets)
	jobs := make(chan string, len(sockets))
	for _, socket := range sockets {
		jobs <- socket
	}
	close(jobs)
	var mu sync.Mutex
	var panes []Pane
	var workers sync.WaitGroup
	for i := 0; i < min(4, len(sockets)); i++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			for socket := range jobs {
				if ctx.Err() != nil {
					return
				}
				ok, ident, err := probeSocket(socket)
				if err != nil || !ok {
					continue
				}
				started := time.Now()
				ps, err := scanServer(ctx, socket, logger)
				if err != nil {
					if ctx.Err() == nil {
						markStale(socket, ident)
					}
					continue
				}
				clearStale(socket)
				index.ObservePartial(buildModel(ps), started)
				mu.Lock()
				panes = append(panes, ps...)
				mu.Unlock()
			}
		}()
	}
	workers.Wait()
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	// Worker completion order must not cause inventory-signature churn.
	sort.Slice(panes, func(i, j int) bool {
		if panes[i].Socket != panes[j].Socket {
			return panes[i].Socket < panes[j].Socket
		}
		return panes[i].PaneID < panes[j].PaneID
	})
	return buildModel(panes), nil
}
