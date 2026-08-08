package discovery

import "sort"

// Pane is the level-2 entry of the two-level workspace model (requirement 002):
// one tmux pane inside a session — i.e. one Agent CLI running on the host. It
// is a pure value describing a single terminal that can later be mirrored.
type Pane struct {
	// Session is the tmux session name the pane belongs to. It is a display
	// label only and never participates in grouping (requirement 002).
	Session string

	// WindowIndex is the index of the tmux window the pane lives in.
	WindowIndex int

	// PaneID is tmux's unique pane identifier for this server (e.g. "%0").
	PaneID string

	// CWD is the pane's current working directory (pane_current_path). It is
	// the level-1 grouping key of the model.
	CWD string

	// Command is the pane's current foreground command (pane_current_command),
	// e.g. "zsh" while a shell is in the foreground.
	Command string

	// Width and Height are the pane's character-cell dimensions.
	Width  int
	Height int
}

// Workspace is the level-1 grouping of the two-level model (requirement 002):
// all panes whose CWD is equal belong to one workspace. The workspace key is
// the CWD; it is aggregated, not named by a session.
type Workspace struct {
	// CWD is the working directory that identifies this workspace.
	CWD string

	// Panes holds every pane running under this CWD, one entry per Agent CLI.
	// It is the level-2 list; the entry point into a terminal mirror.
	Panes []Pane
}

// Count returns the number of panes (Agent CLIs) in this workspace. This is
// the "session count" the home page shows per workspace (requirement 002).
func (w *Workspace) Count() int { return len(w.Panes) }

// Model is the complete snapshot produced by one scan: a pure data structure
// consumed by the API layer. The package performs no caching; every scan
// returns one fresh snapshot (polling/streaming cadence is decided upstream).
type Model struct {
	// Workspaces lists every distinct CWD found across all tmux servers,
	// sorted by CWD so the output is deterministic.
	Workspaces []Workspace
}

// Workspace returns the workspace whose CWD matches, or nil if absent.
func (m *Model) Workspace(cwd string) *Workspace {
	for i := range m.Workspaces {
		if m.Workspaces[i].CWD == cwd {
			return &m.Workspaces[i]
		}
	}
	return nil
}

// buildModel aggregates a flat pane list into the two-level Model: panes are
// grouped by CWD (requirement 002), workspaces sorted by CWD and panes sorted
// by session name, window index, then pane id, for deterministic output.
func buildModel(panes []Pane) *Model {
	byCWD := make(map[string][]Pane)
	for _, p := range panes {
		byCWD[p.CWD] = append(byCWD[p.CWD], p)
	}

	cwds := make([]string, 0, len(byCWD))
	for cwd := range byCWD {
		cwds = append(cwds, cwd)
	}
	sort.Strings(cwds)

	m := &Model{Workspaces: make([]Workspace, 0, len(cwds))}
	for _, cwd := range cwds {
		ps := byCWD[cwd]
		sort.Slice(ps, func(i, j int) bool {
			if ps[i].Session != ps[j].Session {
				return ps[i].Session < ps[j].Session
			}
			if ps[i].WindowIndex != ps[j].WindowIndex {
				return ps[i].WindowIndex < ps[j].WindowIndex
			}
			return ps[i].PaneID < ps[j].PaneID
		})
		m.Workspaces = append(m.Workspaces, Workspace{CWD: cwd, Panes: ps})
	}
	return m
}
