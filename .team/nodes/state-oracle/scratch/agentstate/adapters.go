package agentstate

import (
	"strings"

	"stateoracleprobe/protocol"
)

// This file implements the round-4 adapters: Claude Code and Codex.
//
// Round 1-3's table-driven glyph-whitelist rules (docs/archive/agentstate-round4/
// rules.go + adapters.go) were archived under 058: three repairs all edited the
// same glyph-whitelist, which cannot answer the question — the glyph set
// changed (braille → ◐/✳) and the whitelist went silently blind, and two states
// share the same prefix glyph (✻ Brewed / ✻ Galloping), so any glyph/prefix
// match is structurally unable to separate done from working.
//
// Round 4 replaces the rule table with TWO zero-glyph decisions:
//
//  1. Existence (this file, single-frame Detect): is there a STRUCTURAL work
//     signal — the "esc to interrupt" action bar, which Claude Code renders
//     below the last prompt marker while a task runs? Presence = working.
//     Absence of any work signal = idle. Blocked (an interactive permission
//     box) still outranks: a box needs attention even while the bar shows.
//  2. Derivative (track.go, time-series Track): did the pane's content change
//     between two consecutive samples? Changed = activity = working; identical
//     = stopped = idle. This is what survives a glyph revamp and a shared
//     prefix (a finished task's "✻ Churned for 4s" does not tick, §10.5).

// ClaudeCodeAdapter decides the state of a Claude Code pane (command "claude")
// from its RecentOutput tail, on the existence signal only.
//
// Today's Claude Code pane surfaces three stable UI markers:
//   - working: the bottom action bar shows "esc to interrupt" and a stop
//     button ("⏹ for agents"), below the last prompt marker.
//   - blocked: a permission confirmation box ("Do you want to proceed?")
//     rendered above the prompt, always paired with an action hint.
//   - idle: the rest-state action bar with no "esc to interrupt", and a bare
//     "❯" prompt line.
//
// The prompt-anchor region (last "❯" marker and below) is where the live UI
// lives; residual historical markers above an earlier prompt are excluded, so
// a stale "esc to interrupt" from a previous task can never read as working
// (the D-26 misjudgment, herdr study §9).
type ClaudeCodeAdapter struct{}

// workSignal is the structural work marker: the action bar shown only while a
// task is running. The bare "for agents" is deliberately NOT a trigger — an
// idle pane's navigation hint "← for agents" (subagents) contains it. The
// stop glyph "⏹" is included as a secondary structural marker, not a glyph
// whitelist (it is a UI affordance, not a spinner frame).
const workSignal = "esc to interrupt"

// blockedBoxSignals are the interactive-box phrases that must outrank every
// other signal: a permission/approval prompt rendered while the agent waits.
// Deliberately minimal — just the box phrases the old rule tables keyed on.
var blockedBoxSignals = []string{
	"do you want to proceed?",
	"allow command?",
	"press enter to confirm or esc to cancel",
	"press enter to confirm",
	"enter to confirm or esc to cancel",
}

// promptMarkerPrefix marks a Claude/Codex input prompt or a selectable option
// ("❯ 1. Yes, ..."). The LAST line starting with this prefix delimits "the
// current UI region": everything from that line down is the live UI. This is
// the anchor (herdr §9.2): "行数是版式的函数，锚点是语义的函数".
const promptMarkerPrefix = "❯"

// anchorFallbackBottomLines bounds the fallback region when no prompt anchor
// is found (full-screen TUI, freshly cleared screen). It is a LOW-PRIORITY
// last resort ONLY; the main path is anchored on the prompt marker.
const anchorFallbackBottomLines = 8

// Detect implements Adapter. It is a pure function of the sample's bytes: it
// strips ANSI, anchors to the current UI region, and always returns a State
// (idle when no work signal, unknown when the pane is not recognizably
// Claude). It never blocks, never errors, and never performs I/O.
//
// @contract
// @pre sample 任意；PaneTitle 不参与（只在 track.go 差分里用）
// @post 屏面含工作条/blocked 盒 → 对应状态；否则 idle；无可识别信号 → unknown
// @err none — 永不 error；未知输入降级
// @inv 无 I/O；Detect 是 sample 的纯函数；零字形白名单
func (a *ClaudeCodeAdapter) Detect(sample Sample) State {
	text := stripANSI(string(sample.RecentOutput))
	region := anchorRegion(text)

	if blocked := blockedStateFromScreen(region); blocked != protocol.StateUnknown {
		return State{State: blocked, Confidence: ConfidenceHigh}
	}
	// The work signal is the structural "is the agent running" marker. It is
	// checked within the anchored region, so a stale bar above the last prompt
	// cannot false-positive (the D-26 fix).
	if strings.Contains(region, workSignal) {
		return State{State: protocol.StateWorking, Confidence: ConfidenceMedium}
	}
	return State{State: protocol.StateIdle, Confidence: ConfidenceLow}
}

// CodexAdapter decides the state of a Codex pane (command "codex") from its
// RecentOutput tail. Codex's markers differ: "Allow command?" / y-n selector
// for approvals and a "• Working (…) · esc to interrupt" status line.
type CodexAdapter struct{}

// Detect implements Adapter (same purity contract as ClaudeCodeAdapter).
func (a *CodexAdapter) Detect(sample Sample) State {
	text := stripANSI(string(sample.RecentOutput))
	region := anchorRegion(text)

	if blocked := blockedStateFromScreen(region); blocked != protocol.StateUnknown {
		return State{State: blocked, Confidence: ConfidenceHigh}
	}
	// Codex's working signal is the status line. Both markers are structural
	// ("esc to interrupt" while running, "Allow command?" while waiting).
	if strings.Contains(region, "working") && strings.Contains(region, "esc to interrupt") {
		return State{State: protocol.StateWorking, Confidence: ConfidenceMedium}
	}
	return State{State: protocol.StateIdle, Confidence: ConfidenceLow}
}

// blockedStateFromScreen is a narrow screen-text check for interactive boxes
// that must outrank any other signal: a permission/approval prompt rendered
// while the agent waits. Returns StateUnknown when no box is present.
func blockedStateFromScreen(text string) protocol.AgentState {
	lower := strings.ToLower(text)
	for _, phrase := range blockedBoxSignals {
		if strings.Contains(lower, phrase) {
			return protocol.StateBlocked
		}
	}
	return protocol.StateUnknown
}

// anchorRegion returns the region the existence rules should inspect: from the
// last prompt marker line down to the end of the screen. It returns the
// trimmed text so callers pass it to the Contains checks.
//
// If no prompt marker is found (full-screen TUI, freshly cleared screen), it
// falls back to the last anchorFallbackBottomLines non-empty lines, so the
// path never scans the whole screen (which would re-import the residual-text
// misjudgment) and never crashes.
func anchorRegion(text string) string {
	lines := strings.Split(text, "\n")
	lastPrompt := -1
	for i, ln := range lines {
		if strings.HasPrefix(strings.TrimLeft(ln, " \t"), promptMarkerPrefix) {
			lastPrompt = i
		}
	}
	if lastPrompt < 0 {
		return strings.Join(lastNonEmptyLines(lines, anchorFallbackBottomLines), "\n")
	}
	return strings.Join(lines[lastPrompt:], "\n")
}

// lastNonEmptyLines returns the last n non-empty lines (in screen order). Used
// only by the no-anchor fallback in anchorRegion.
func lastNonEmptyLines(lines []string, n int) []string {
	var nonEmpty []string
	for i := len(lines) - 1; i >= 0 && len(nonEmpty) < n; i-- {
		if strings.TrimSpace(lines[i]) != "" {
			nonEmpty = append([]string{lines[i]}, nonEmpty...)
		}
	}
	return nonEmpty
}
