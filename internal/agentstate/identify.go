package agentstate

import (
	"context"
	"os/exec"
	"path"
	"strconv"
	"strings"
	"time"
)

// This file implements the wrapper-scene agent identifier (task
// state-ident-wrapper). A team-agent pane runs its CLI under a wrapper shell
// (pane_current_command=bash), so the command-keyed registry degrades to
// unknown for the whole fleet — the exact gap the state-parser seat measured
// (its CLAUDE.md §5). Identify closes it by walking the pane's process tree:
// one bounded `ps` snapshot, then a pure table→tree→argv match for a
// claude/codex descendant. The pane title is a secondary heuristic, used only
// when the tree cannot decide.
//
// Isolation law (requirement 008): the identifier may do bounded I/O (ps is
// single-shot with a ≤500ms budget) but every failure — timeout, parse error,
// empty or partial table, absent PID — degrades to AgentKindUnknown. The
// decision core (identifyFromTable/classifyArgv) is pure: zero I/O, zero
// clocks, so it is fully unit-testable with fake process tables.

// identifyTimeout bounds one Identify call's process-tree I/O. Exceeding it
// degrades to unknown — identification is a hint for state dispatch, never a
// blocking dependency for mirroring or input (requirement 008).
const identifyTimeout = 500 * time.Millisecond

// AgentKind names which agent CLI a pane runs. It bridges the wrapper scene
// (pane_current_command=bash) to the per-agent rule tables: Identify produces a
// kind, and Registry.DetectForKind dispatches on it. The zero value is
// AgentKindUnknown — the first-class "could not identify" state that never
// blocks anything.
type AgentKind string

const (
	// AgentKindUnknown is the fallback when no signal identifies the pane.
	// A pane we cannot classify is mirrored normally, just without a state hint.
	AgentKindUnknown AgentKind = ""
	// AgentKindClaude is a Claude Code pane.
	AgentKindClaude AgentKind = "claude"
	// AgentKindCodex is a Codex pane.
	AgentKindCodex AgentKind = "codex"
)

// Command returns the pane_current_command key the per-agent registry keys on
// for this kind, or ok=false for an unknown kind. It lets one adapter table
// serve both the direct-pane path (command-keyed) and the wrapper path
// (kind-keyed) — an additive bridge, not a second set of rules.
func (k AgentKind) Command() (string, bool) {
	switch k {
	case AgentKindClaude:
		return "claude", true
	case AgentKindCodex:
		return "codex", true
	}
	return "", false
}

// IdentifyInput is the bounded-IO input for pane identification, assembled by
// the upper layer (ws-api) from tmux format strings:
//   - PanePID     — tmux #{pane_pid}, the PID of the pane's first process.
//   - PaneTitle   — tmux #{pane_title}, used as a secondary heuristic.
//   - PaneCommand — tmux #{pane_current_command}, the pane's foreground command.
type IdentifyInput struct {
	PanePID     int
	PaneTitle   string
	PaneCommand string
}

// Identify classifies the agent CLI running in a pane. Order of evidence:
//  1. PaneCommand already names the CLI (direct pane) — no I/O, fast path.
//  2. Process-tree descent: one bounded ps snapshot, then the pure
//     identifyFromTable match over the pane's descendants.
//  3. PaneTitle heuristic, only when the tree cannot decide.
//
// Every failure path (pane without a PID, ps timeout/error, no descendant
// match) degrades to AgentKindUnknown — never an error, never a block
// (requirement 008: a failed identification must not affect mirroring).
//
// @contract
// @pre in 为任意 IdentifyInput；无非法输入（PanePID=0 视为无 pane）
// @post 返回合法 AgentKind；识别失败时恒为 AgentKindUnknown，从不返回错误
// @err none — 所有失败路径降级为 AgentKindUnknown，错误不向调用方泄漏
// @inv 进程树 I/O 单次 ps 且受 ≤500ms 预算约束（identifyTimeout）；决策核心 identifyFromTable/classifyArgv 纯函数
func Identify(ctx context.Context, in IdentifyInput) AgentKind {
	if k := kindFromCommand(in.PaneCommand); k != AgentKindUnknown {
		return k
	}
	if in.PanePID > 0 {
		dctx, cancel := context.WithTimeout(ctx, identifyTimeout)
		defer cancel()
		if table, err := readProcTable(dctx); err == nil {
			if k := identifyFromTable(table, in.PanePID); k != AgentKindUnknown {
				return k
			}
		}
	}
	return kindFromTitle(in.PaneTitle)
}

// kindFromCommand is the direct-pane fast path: pane_current_command is the CLI
// binary name (tmux reports the basename). No I/O is performed, so a direct
// pane never pays for a process snapshot.
func kindFromCommand(cmd string) AgentKind {
	switch strings.ToLower(strings.TrimSpace(path.Base(cmd))) {
	case "claude":
		return AgentKindClaude
	case "codex":
		return AgentKindCodex
	}
	return AgentKindUnknown
}

// kindFromTitle classifies a pane title (tmux #{pane_title}) as a secondary
// heuristic. tmux sets pane_title to the foreground program by default (and the
// CLI's OSC title otherwise); matching the exact basename keeps "claude" /
// "codex" / "/usr/bin/codex" resolving while an arbitrary description never
// does. It runs only when the process tree cannot decide, so a stale or wrong
// title can never override a tree match.
func kindFromTitle(title string) AgentKind {
	switch strings.ToLower(strings.TrimSpace(path.Base(title))) {
	case "claude":
		return AgentKindClaude
	case "codex":
		return AgentKindCodex
	}
	return AgentKindUnknown
}

// Proc is one process snapshot row: pid, parent pid, and the full argv string.
type Proc struct {
	PID  int
	PPID int
	Args string
}

// readProcTable runs the one-line process snapshot once and parses its rows
// into a Proc table. It is the only I/O in the identifier; the caller bounds it
// with the context deadline. A row that fails to parse is skipped, never fatal
// — a malformed snapshot degrades to a partial table, and a partial table
// degrades to unknown, never a failure (requirement 008).
//
// The full argv (not just the process name / comm) is required because the CLIs
// are reached through wrapper binaries: a node-wrapped claude has comm "node"
// while its argv names the agent script (state-parser §5 measurement).
func readProcTable(ctx context.Context) ([]Proc, error) {
	out, err := exec.CommandContext(ctx, "ps", "-axo", "pid=,ppid=,command=").Output()
	if err != nil {
		return nil, err
	}
	return parseProcTable(string(out)), nil
}

// parseProcTable turns ps output lines into a Proc table, skipping malformed
// rows. Each line is "<pid> <ppid> <command...>"; the first two whitespace
// fields are integers and the remainder (if any) is the argv string.
func parseProcTable(out string) []Proc {
	var table []Proc
	for _, line := range strings.Split(out, "\n") {
		if p, ok := parseProcLine(line); ok {
			table = append(table, p)
		}
	}
	return table
}

// parseProcLine parses one ps line into a Proc. It returns ok=false for a blank
// or malformed line (non-numeric pid/ppid). A command-less row is still kept —
// a process with an empty argv simply never matches a CLI.
func parseProcLine(line string) (Proc, bool) {
	line = strings.TrimSpace(line)
	if line == "" {
		return Proc{}, false
	}
	pidS, rest, ok := cutField(line)
	if !ok {
		return Proc{}, false
	}
	ppidS, argv, _ := cutField(rest)
	pid, err := strconv.Atoi(pidS)
	if err != nil {
		return Proc{}, false
	}
	ppid := 0
	if ppidS != "" {
		if ppid, err = strconv.Atoi(ppidS); err != nil {
			return Proc{}, false
		}
	}
	return Proc{PID: pid, PPID: ppid, Args: argv}, true
}

// cutField splits off the first whitespace-delimited field, returning the
// field, the trimmed remainder, and whether a field was present.
func cutField(s string) (field, rest string, ok bool) {
	s = strings.TrimLeft(s, " ")
	if s == "" {
		return "", "", false
	}
	if i := strings.IndexByte(s, ' '); i >= 0 {
		return s[:i], strings.TrimLeft(s[i:], " "), true
	}
	return s, "", true
}

// identifyFromTable decides a pane's agent kind from a process table alone. It
// is a pure function of (table, panePID): no I/O, no clocks, so the whole
// table→tree→match logic is unit-testable with fake tables (task contract: the
// decision core must be testable without real processes). Descendants are
// walked breadth-first so the pane's direct CLI is seen before the tools it
// spawned; the first descendant whose argv names an agent CLI decides the kind.
func identifyFromTable(table []Proc, panePID int) AgentKind {
	for _, p := range descendantsOf(table, panePID) {
		if k := classifyArgv(p.Args); k != AgentKindUnknown {
			return k
		}
	}
	return AgentKindUnknown
}

// descendantsOf returns every process reachable from root by walking PPID
// links, excluding root itself, in breadth-first order. The ps snapshot can be
// inconsistent with the pane (a process may exit or be spawned between the
// snapshot and the walk), so a missing PPID simply ends that branch — never an
// error (a partial table degrades to unknown, requirement 008).
func descendantsOf(table []Proc, root int) []Proc {
	byParent := make(map[int][]Proc, len(table))
	for _, p := range table {
		byParent[p.PPID] = append(byParent[p.PPID], p)
	}
	var out []Proc
	queue := byParent[root]
	for len(queue) > 0 {
		p := queue[0]
		queue = queue[1:]
		out = append(out, p)
		queue = append(queue, byParent[p.PID]...)
	}
	return out
}

// classifyArgv returns the AgentKind a process argv names, or unknown. Only the
// executable (fields[0]) and, for node-style wrappers, its direct launch target
// (fields[1]) are candidates. Scanning the whole argv would let an instruction
// flag that merely mentions "codex" (e.g. `-c developer_instructions="... codex
// ..."`) false-match — ps re-joins the args without shell quoting, so the word
// appears as a bare field. The executable and its launch target are
// path-basename-accurate, so matching them keeps `claude`, `/usr/bin/codex` and
// `node /opt/homebrew/bin/codex` resolving while a plain shell's launch text
// (`sh -lc "...codex..."`) never does. A .js/.cjs form is recognized so a
// node-wrapped `node claude.js` still resolves.
func classifyArgv(argv string) AgentKind {
	fields := strings.Fields(argv)
	for _, f := range fields[:min(len(fields), 2)] {
		switch base := path.Base(f); {
		case base == "claude" || base == "claude.js" || base == "claude.cjs":
			return AgentKindClaude
		case base == "codex" || base == "codex.js" || base == "codex.cjs":
			return AgentKindCodex
		}
	}
	return AgentKindUnknown
}
