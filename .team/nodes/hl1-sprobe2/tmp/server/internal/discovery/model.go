package discovery

import "sort"

// Pane is the level-2 entry of the two-level workspace model (requirement 002):
// one tmux pane inside a session — i.e. one Agent CLI running on the host. It
// is a pure value describing a single terminal that can later be mirrored.
type Pane struct {
	// Socket is the absolute path of the tmux server socket this pane lives
	// on (e.g. "/private/tmp/tmux-501/ta-…"). It MUST survive the scan into
	// the model: the ws-api layer consumes it to address the pane's bridge
	// (bridge.NewPane(Socket, PaneID)) and to build the stable ref, so a
	// bare pane id alone is ambiguous when more than one tmux server runs on
	// the host (requirement 001's private-socket scenario). Without it the
	// mirrored pane cannot be located at subscribe/input/scrollback/resize
	// time.
	Socket string

	// Session is the tmux session name the pane belongs to. It is a display
	// label only and never participates in grouping (requirement 002).
	Session string

	// WindowIndex is the index of the tmux window the pane lives in.
	WindowIndex int

	// WindowName is the tmux window name (#{window_name}) the pane lives in.
	// It is the display label the client renders for a session (fix-session-
	// alias): window names carry the meaningful per-window labels in the fleet
	// (e.g. "wiki-r5-acceptance-tester"), while the session name is a whole-team
	// name. It may be empty when the scan could not parse it; the listing layer
	// falls back to Session. Never a grouping key (grouping is CWD, 002).
	WindowName string

	// PaneTitle is the pane's OSC title (#{pane_title}). It is carried as
	// opaque display data (requirement 060: the title is shown verbatim, never
	// parsed for state — the agent-state pipeline was removed). A missing or
	// empty title is not a scan failure.
	PaneTitle string

	// PaneID is tmux's unique pane identifier for this server (e.g. "%0").
	PaneID string

	// CWD is the pane's current working directory (pane_current_path). It is
	// the level-1 grouping key of the model.
	CWD string

	// Command is the pane's current foreground command (pane_current_command),
	// e.g. "zsh" while a shell is in the foreground.
	Command string

	// PanePID is tmux #{pane_pid}, the PID of the pane's first process. It is
	// additive input for the state-wiring layer (task fix-state-wiring): the
	// agent identifier walks the pane's process tree from this root to decide
	// which agent CLI runs in a wrapper pane (pane_current_command=bash).
	// Zero when the format could not parse a PID (safe: identification
	// degrades to unknown, requirement 008 isolation law).
	PanePID int

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
// @contract
// @pre none（纯查询，无副作用）
// @post 返回 CWD 精确匹配的 Workspace；无匹配时返回 nil
// @err none
// @inv 不修改 Model；cwd 不变则结果不变
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
