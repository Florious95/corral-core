package discovery

import (
	"sort"
	"sync"
)

// WorkspaceIndex caches routing, never session rows or observations. A socket
// can serve several workspaces and a workspace can span several sockets.
// Missing rows in a best-effort host discovery are NOT evidence of deletion:
// unreachable servers keep their routes until a fresh query or filesystem
// inventory can establish absence. Callers must still query the routed sockets.
type WorkspaceIndex struct {
	mu        sync.RWMutex
	bySocket  map[string]map[string]struct{}
	byCWD     map[string]map[string]struct{}
	ready     chan struct{}
	readyOnce sync.Once
}

func NewWorkspaceIndex() *WorkspaceIndex {
	return &WorkspaceIndex{
		bySocket: make(map[string]map[string]struct{}),
		byCWD:    make(map[string]map[string]struct{}),
		ready:    make(chan struct{}),
	}
}

// Ready closes only after an actual successful host discovery, including an
// authoritative empty inventory. Cold callers can wait with their own deadline.
func (x *WorkspaceIndex) Ready() <-chan struct{} { return x.ready }

// Observe replaces routes for sockets represented in model. removed must only
// contain sockets proven absent, not sockets that timed out or failed sampling.
// A failed discovery must not call Observe at all.
func (x *WorkspaceIndex) Observe(model *Model, removed []string) {
	if model == nil {
		return
	}
	next := make(map[string]map[string]struct{})
	for _, ws := range model.Workspaces {
		for _, p := range ws.Panes {
			if next[p.Socket] == nil {
				next[p.Socket] = make(map[string]struct{})
			}
			next[p.Socket][ws.CWD] = struct{}{}
		}
	}
	x.mu.Lock()
	defer x.mu.Unlock()
	forget := func(socket string) {
		for cwd := range x.bySocket[socket] {
			delete(x.byCWD[cwd], socket)
			if len(x.byCWD[cwd]) == 0 {
				delete(x.byCWD, cwd)
			}
		}
		delete(x.bySocket, socket)
	}
	for _, socket := range removed {
		forget(socket)
	}
	for socket, cwds := range next {
		forget(socket)
		x.bySocket[socket] = cwds
		for cwd := range cwds {
			if x.byCWD[cwd] == nil {
				x.byCWD[cwd] = make(map[string]struct{})
			}
			x.byCWD[cwd][socket] = struct{}{}
		}
	}
	x.readyOnce.Do(func() { close(x.ready) })
}

// Sockets is a detached, deterministic list. It is never widened to all host
// sockets when cwd is unknown; discovery of new servers belongs to the host loop.
func (x *WorkspaceIndex) Sockets(cwd string) []string {
	x.mu.RLock()
	defer x.mu.RUnlock()
	out := make([]string, 0, len(x.byCWD[cwd]))
	for socket := range x.byCWD[cwd] {
		out = append(out, socket)
	}
	sort.Strings(out)
	return out
}

func (x *WorkspaceIndex) KnownSockets() []string {
	x.mu.RLock()
	defer x.mu.RUnlock()
	out := make([]string, 0, len(x.bySocket))
	for socket := range x.bySocket {
		out = append(out, socket)
	}
	sort.Strings(out)
	return out
}
