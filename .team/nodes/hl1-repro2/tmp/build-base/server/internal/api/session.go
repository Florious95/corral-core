package api

// session.go implements the server-side session catalog: the map from a
// stable session ref to the tmux pane it mirrors, plus the bridge instance
// bound to that pane. A ref must survive reconnects and listing deltas, so it
// is derived from the pane's stable identity (socket + pane id), never from a
// transient index or the display name.

import (
	"sync"

	"github.com/agentmirror/agentmirror/internal/bridge"
	"github.com/agentmirror/agentmirror/internal/discovery"
)

// sessionRef returns the stable, opaque session ref for a discovered pane.
// It is built from the two fields that together uniquely identify a pane
// across the host — the tmux server socket and the bare pane id (a pane id is
// only unique within one server, and this host runs many, requirement 001).
// The unit separator keeps the two fields unambiguous in the joined string.
// A ref stays valid across reconnect because a pane keeps its socket and id
// for its whole lifetime.
func sessionRef(p discovery.Pane) string {
	return p.Socket + "\x1f" + p.PaneID
}

// sessionEntry is one mirrorable pane known to the catalog: its stable ref
// and the bridge bound to the pane's bare id on its socket. A Pane bound to a
// bare id is the only tmux addressing form that passes the exact existence
// check (term-bridge knowledge base §5).
type sessionEntry struct {
	ref    string
	pane   discovery.Pane
	bridge *bridge.Pane
}

// sessionCatalog holds every pane currently discovered on the host, keyed by
// stable ref. It is the service's in-memory index from client-facing ref to
// the tmux instance behind it; nothing here is persisted and nothing here
// depends on connection state (requirement 004: the server keeps no client
// session state, only the host tmux is the source of truth).
type sessionCatalog struct {
	mu    sync.RWMutex
	byRef map[string]*sessionEntry
}

func newSessionCatalog() *sessionCatalog {
	return &sessionCatalog{byRef: make(map[string]*sessionEntry)}
}

// rebuild replaces the catalog contents with the latest discovery model. It
// is called by the listing loop after each scan; panes that vanished are
// dropped (their bridge stream readers see EOF and wind down), new panes are
// indexed, and existing panes keep their identity. The bridge binding is
// (re)constructed from the pane's socket and id — never from anything a
// client supplied.
func (c *sessionCatalog) rebuild(model *discovery.Model) {
	next := make(map[string]*sessionEntry)
	for i := range model.Workspaces {
		ws := &model.Workspaces[i]
		for j := range ws.Panes {
			p := ws.Panes[j]
			e := &sessionEntry{
				ref:    sessionRef(p),
				pane:   p,
				bridge: bridge.NewPane(p.Socket, p.PaneID),
			}
			next[e.ref] = e
		}
	}
	c.mu.Lock()
	c.byRef = next
	c.mu.Unlock()
}

// entry returns the session entry for a client-facing ref, or nil.
func (c *sessionCatalog) entry(ref string) *sessionEntry {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.byRef[ref]
}

// list returns all current entries in stable ref order (deterministic output
// for tests and for diffing).
func (c *sessionCatalog) list() []*sessionEntry {
	c.mu.RLock()
	defer c.mu.RUnlock()
	out := make([]*sessionEntry, 0, len(c.byRef))
	for _, e := range c.byRef {
		out = append(out, e)
	}
	return out
}
