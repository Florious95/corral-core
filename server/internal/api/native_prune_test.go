package api

import (
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
)

type nativePruneCall struct {
	cwd    string
	before time.Time
	keep   map[string]struct{}
}
type recordingNativePruner struct {
	fakeConversations
	calls []nativePruneCall
}

func (p *recordingNativePruner) Prune(cwd string, before time.Time, keep map[string]struct{}) {
	p.calls = append(p.calls, nativePruneCall{cwd, before, keep})
}

func TestAcceptedCatalogPrunesNativeBridgesWithoutDeletingOtherWorkspaces(t *testing.T) {
	one := discovery.Pane{Socket: "/synthetic/socket", PaneID: "%1", CWD: "/one"}
	two := discovery.Pane{Socket: one.Socket, PaneID: "%2", CWD: "/two"}
	catalog := newSessionCatalog()
	catalog.rebuild(&discovery.Model{Workspaces: []discovery.Workspace{{CWD: one.CWD, Panes: []discovery.Pane{one}}, {CWD: two.CWD, Panes: []discovery.Pane{two}}}}, nil)
	p := &recordingNativePruner{}
	s := &Server{catalog: catalog, conversations: p, workspaceCatalogs: map[string]workspaceCatalog{one.CWD: {started: time.Unix(100, 0), catalog: catalog}}}
	s.publishWorkspaceCatalog(one.CWD, time.Unix(300, 0), newSessionCatalog())
	if len(p.calls) != 1 || p.calls[0].cwd != one.CWD || len(p.calls[0].keep) != 0 {
		t.Fatal("scoped deletion was not reconciled")
	}
	// An older global scan cannot restore a ref deleted by the newer scoped
	// inventory, or cancel a still-live ref in the other workspace.
	s.pruneWorkspaceCatalogsLocked(time.Unix(200, 0))
	call := p.calls[1]
	if call.cwd != "" || len(call.keep) != 1 {
		t.Fatal("global retirement ignored authoritative overlays")
	}
	if _, ok := call.keep[sessionRef(two)]; !ok {
		t.Fatal("other workspace ref was not protected")
	}
	if _, ok := call.keep[sessionRef(one)]; ok {
		t.Fatal("stale global catalog resurrected deleted ref")
	}
	s.publishWorkspaceCatalog(one.CWD, time.Unix(150, 0), catalog)
	if len(p.calls) != 2 {
		t.Fatal("rejected stale publication retired native refs")
	}
}
