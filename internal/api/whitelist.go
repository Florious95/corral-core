package api

import "github.com/agentmirror/agentmirror/internal/discovery"

// identifyProvider is the single 068 identity function. Level-1 listing
// and the level-2 stream both call this (via identifyAll) so a pane cannot
// appear in one menu and vanish in the other.
func (s *Server) identifyProvider(panePID int) string {
	if s.providerFinder == nil {
		return ""
	}
	return s.providerFinder.Identify(panePID)
}

func (s *Server) identifyAll(pids []int) map[int]string {
	out := make(map[int]string, len(pids))
	if s.providerFinder == nil {
		return out
	}
	if f, ok := s.providerFinder.(interface {
		IdentifySet([]int) map[int]string
	}); ok {
		return f.IdentifySet(pids)
	}
	for _, pid := range pids {
		if id := s.identifyProvider(pid); id != "" {
			out[pid] = id
		}
	}
	return out
}

func identifyModel(s *Server, model *discovery.Model) map[int]string {
	var pids []int
	for _, ws := range model.Workspaces {
		for _, p := range ws.Panes {
			pids = append(pids, p.PanePID)
		}
	}
	return s.identifyAll(pids)
}

// filterModel drops panes that identifyProvider does not claim, then
// drops workspaces (and therefore sockets) whose remaining hit count is 0.
func filterModel(s *Server, model *discovery.Model) *discovery.Model {
	if model == nil {
		return &discovery.Model{}
	}
	hits := identifyModel(s, model)
	out := &discovery.Model{}
	for _, ws := range model.Workspaces {
		var panes []discovery.Pane
		for _, p := range ws.Panes {
			if hits[p.PanePID] == "" {
				continue
			}
			panes = append(panes, p)
		}
		if len(panes) == 0 {
			continue
		}
		out.Workspaces = append(out.Workspaces, discovery.Workspace{CWD: ws.CWD, Panes: panes})
	}
	return out
}
