package api

import "time"

type workspaceCatalog struct {
	started time.Time
	catalog *sessionCatalog
}

// All fields below are protected by Server.snapMu. This is a routing overlay,
// not a second source of L1 listing sequence numbers or cached L2 responses.
func (s *Server) publishWorkspaceCatalog(cwd string, started time.Time, catalog *sessionCatalog) {
	s.snapMu.Lock()
	defer s.snapMu.Unlock()
	if s.catalogStarted.After(started) {
		return
	}
	if len(catalog.list()) == 0 {
		_, previous := s.workspaceCatalogs[cwd]
		listed := false
		if s.snapshot != nil {
			_, listed = s.snapshot.byCWD[cwd]
		}
		if !previous && !listed {
			return // arbitrary unknown paths do not accumulate empty overlays
		}
	}
	if s.workspaceCatalogs == nil {
		s.workspaceCatalogs = make(map[string]workspaceCatalog)
	}
	if previous, ok := s.workspaceCatalogs[cwd]; ok && previous.started.After(started) {
		return
	}
	s.workspaceCatalogs[cwd] = workspaceCatalog{started: started, catalog: catalog}
	s.pruneNativeBridgesLocked(cwd, started)
}

// A global scan that started BEFORE a scoped refresh cannot resurrect removed
// scoped refs or hide new ones. Only a later successful global generation may
// retire that overlay. Caller holds snapMu for the complete publication.
func (s *Server) pruneWorkspaceCatalogsLocked(started time.Time) {
	s.catalogStarted = started
	for cwd, scoped := range s.workspaceCatalogs {
		if !scoped.started.After(started) {
			delete(s.workspaceCatalogs, cwd)
		}
	}
	s.pruneNativeBridgesLocked("", started)
}

// Only accepted publications retire native bridges. Scoped inventories cover
// their own cwd; global inventories honor fresher workspace overlays. Caller
// holds snapMu so a rejected/stale generation can never cancel a live ref.
func (s *Server) pruneNativeBridgesLocked(cwd string, started time.Time) {
	if s.conversations == nil {
		return
	}
	keep := make(map[string]struct{})
	if cwd != "" {
		for _, entry := range s.workspaceCatalogs[cwd].catalog.list() {
			keep[entry.ref] = struct{}{}
		}
	} else {
		for _, entry := range s.catalog.list() {
			if _, covered := s.workspaceCatalogs[entry.pane.CWD]; !covered {
				keep[entry.ref] = struct{}{}
			}
		}
		for _, scoped := range s.workspaceCatalogs {
			for _, entry := range scoped.catalog.list() {
				keep[entry.ref] = struct{}{}
			}
		}
	}
	s.conversations.Prune(cwd, started, keep)
}

func (s *Server) scopedCatalogEntryLocked(ref string) (*sessionEntry, bool) {
	var newest *sessionEntry
	var observed time.Time
	for _, scoped := range s.workspaceCatalogs {
		if entry := scoped.catalog.entry(ref); entry != nil && (newest == nil || scoped.started.After(observed)) {
			newest, observed = entry, scoped.started
		}
	}
	if newest != nil {
		return newest, true
	}
	if entry := s.catalog.entry(ref); entry != nil {
		if _, authoritative := s.workspaceCatalogs[entry.pane.CWD]; authoritative {
			return nil, true // includes a confirmed empty workspace
		}
	}
	return nil, false
}
