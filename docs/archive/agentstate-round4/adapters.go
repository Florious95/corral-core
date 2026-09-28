package agentstate

import (
	"strings"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

// This file implements the first-batch adapters: Claude Code and Codex.
//
// ## Compliance note (requirement 008, Apache-2.0)
// The detection approach — table-driven rules matching pane output with a
// descending-priority "first match wins" ordering, keyed on CLI UI markers —
// is derived from herdr (github.com/herdrdev/herdr, Apache-2.0), specifically
// its per-agent manifest rule tables (src/detect/manifests/claude.toml and
// codex.toml) and region-based matching. herdr is an explicit reference
// knowledge base for this project (requirement 008). We retain the herdr
// copyright notice in this file's provenance and mark the modification: this
// implementation is rewritten for the agentmirror contract (tail-window
// RecentOutput instead of a full screen, no OSC-title dependency, Go instead
// of Rust), the rule tables are our own reduced/updated set keyed to the UI
// elements we observe today, and no herdr source code is copied verbatim.
// herdr LICENSE (Apache-2.0) is preserved in its repository.

// ClaudeCodeAdapter decides the state of a Claude Code pane (command "claude")
// from its RecentOutput tail. Rules key on the on-screen status action bar
// (which a capture-pane -e window exposes), with a braille-spinner fallback.
//
// Today's Claude Code pane surfaces three stable UI markers:
//   - working: the bottom action bar shows "esc to interrupt" and a stop
//     button ("⏹ for agents"), and the OSC title carries a braille spinner.
//     tmux consumes the OSC title, so the action bar is the reliable carrier.
//   - blocked: a permission confirmation box ("Do you want to proceed?")
//     rendered above the prompt, always paired with an action hint such as
//     "(esc to cancel)".
//   - idle: the rest-state action bar "bypass permissions on · N shell" with
//     no "esc to interrupt", and a bare "❯" prompt line.
//
// The rules are ordered so a strong blocked/working signal outranks idle.
type ClaudeCodeAdapter struct{}

// claudeRules is the Claude Code rule table. Edit here when the CLI changes
// wording — each rule documents the UI element it keys on (task contract §4:
// the table is the maintenance surface).
var claudeRules = []rule{
	{
		id: "claude-blocked-permission-box", priority: 1000,
		state: protocol.StateBlocked, confidence: ConfidenceHigh,
		comment:  "Permission confirmation box: 'Do you want to proceed?' plus an action hint ('esc to cancel' / 'tab to amend' / 'ctrl+e to explain' / 'enter to confirm'). The action hint proves it is an interactive box, not a doc page mentioning the phrase. Breaks if the box wording changes.",
		contains: []string{"do you want to proceed?"},
		anyContains: []string{
			"esc to cancel", "tab to amend", "ctrl+e to explain",
			"enter to confirm", "y/n", "bash command",
		},
	},
	{
		id: "claude-working-action-bar", priority: 900,
		state: protocol.StateWorking, confidence: ConfidenceMedium,
		comment:     "Bottom action bar while working: 'esc to interrupt' and the stop button '⏹ for agents' appear only while a task is running. The bare 'for agents' is deliberately NOT a trigger: an idle pane's navigation hint '← for agents' (subagents) contains it, so matching it alone false-positives idle as working (D-26 fleet measurement). The stop glyph '⏹' or the interrupt hint is the real working marker. Breaks if the interrupt hint wording changes.",
		anyContains: []string{"esc to interrupt", "⏹ for agents"},
		notContains: []string{"do you want to proceed?", "allow command?"},
	},
	{
		id: "claude-working-spinner", priority: 800,
		state: protocol.StateWorking, confidence: ConfidenceLow,
		comment:     "Fallback: a braille spinner frame on any line while the action bar is cut out of the tail window. Deliberately excludes the '⠤' idle separator (not in spinnerFrames).",
		spinnerLine: true,
	},
	{
		id: "claude-idle-rest-bar", priority: 500,
		state: protocol.StateIdle, confidence: ConfidenceLow,
		comment:     "Rest-state action bar 'bypass permissions on · N shell'. The 'esc to interrupt' exclusion keeps a working pane from reading idle (its bar also shows 'bypass permissions on').",
		contains:    []string{"bypass permissions on", "shell"},
		notContains: []string{"esc to interrupt", "do you want to proceed?", "allow command?"},
	},
	{
		id: "claude-idle-prompt", priority: 400,
		state: protocol.StateIdle, confidence: ConfidenceLow,
		comment:      "Bare '❯' input prompt with no interactive box around it. Lowest-priority idle signal because the input line is always present; the action-bar/spinner rules above win when the pane is actually working.",
		linePrefixes: []string{"❯"},
		notContains:  []string{"do you want to proceed?", "esc to cancel", "press enter to confirm", "allow command?"},
	},
}

// Detect implements Adapter. It is a pure function of the sample's bytes: it
// strips ANSI, runs the rule table, and always returns a State (unknown when
// nothing matches). It never blocks, never errors, and never performs I/O.
//
// The pane_title OSC signal is checked FIRST for Claude Code, because it is a
// stronger and more current signal than screen text (herdr keys its claude
// manifest on the title spinner/star): a braille spinner means working, a ✳
// (U+2733) prefix means idle. The screen-rule blocked check still runs first
// — a permission box needs attention even while the title spins — then the
// title, then the screen table as fallback (codex and edge cases).
func (a *ClaudeCodeAdapter) Detect(sample Sample) State {
	text := stripANSI(string(sample.RecentOutput))
	// Blocked outranks everything: an interactive box is the strongest signal
	// (requirement 003: a false idle on a blocked pane is a missed wake-up).
	if state := blockedStateFromScreen(text); state != protocol.StateUnknown {
		return State{State: state, Confidence: ConfidenceHigh}
	}
	// The title is authoritative for working/idle when it carries the marker.
	if s := stateFromTitle(sample.PaneTitle); s.State != protocol.StateUnknown {
		return s
	}
	return evaluateRules(claudeRules, text)
}

// CodexAdapter decides the state of a Codex pane (command "codex") from its
// RecentOutput tail. Codex's UI markers differ from Claude Code's: it asks
// "Allow command?" with a y/n selector for approvals and shows a
// "• Working (…) · esc to interrupt" status line while running.
type CodexAdapter struct{}

// codexRules is the Codex rule table (same maintenance contract as claudeRules).
var codexRules = []rule{
	{
		id: "codex-blocked-approval", priority: 900,
		state: protocol.StateBlocked, confidence: ConfidenceHigh,
		comment: "Approval prompt: 'Allow command?' / 'press enter to confirm or esc to cancel' / a 'Yes (y)' selector. Breaks if the approval wording changes.",
		anyContains: []string{
			"allow command?", "press enter to confirm or esc to cancel",
			"[y/n]", "yes (y)", "action required",
		},
	},
	{
		id: "codex-working-status", priority: 700,
		state: protocol.StateWorking, confidence: ConfidenceMedium,
		comment:     "Status line '• Working (…) · esc to interrupt' while a task runs. Breaks if the status wording changes.",
		contains:    []string{"working", "esc to interrupt"},
		notContains: []string{"allow command?", "press enter to confirm"},
	},
	{
		id: "codex-working-spinner", priority: 650,
		state: protocol.StateWorking, confidence: ConfidenceLow,
		comment:     "Fallback: braille spinner frame on any line (same frame set as Claude Code).",
		spinnerLine: true,
	},
	{
		id: "codex-idle-prompt", priority: 400,
		state: protocol.StateIdle, confidence: ConfidenceLow,
		comment:      "Bare '❯' prompt with no approval box. The notContains list keeps an approval prompt from reading idle.",
		linePrefixes: []string{"❯"},
		notContains:  []string{"allow command?", "press enter to confirm", "[y/n]", "yes (y)"},
	},
}

// Detect implements Adapter (same purity contract as ClaudeCodeAdapter).
func (a *CodexAdapter) Detect(sample Sample) State {
	return evaluateRules(codexRules, stripANSI(string(sample.RecentOutput)))
}

// stateFromTitle classifies a Claude Code pane title (OSC title) into a state.
// It is the title half of the D-26 fix (task fix-state-detection): Claude Code
// draws a braille spinner (U+2800–U+28FF) while working and a ✳ (U+2733)
// prefix while idle. A title without either marker — codex's directory name,
// a plain shell, or empty — returns StateUnknown so the screen table decides.
//
// The markers are verified against the live fleet (2026-08-12, D-26): every
// working claude pane carries a rotating braille frame; every idle one a ✳.
func stateFromTitle(title string) State {
	if title == "" {
		return State{State: protocol.StateUnknown, Confidence: ConfidenceUnknown}
	}
	// A working title is a braille spinner frame (U+2800–U+28FF). It may appear
	// anywhere in the title (Claude Code prefixes it before the window label).
	for _, r := range title {
		if r >= 0x2800 && r <= 0x28FF {
			return State{State: protocol.StateWorking, Confidence: ConfidenceHigh}
		}
	}
	// An idle title starts with ✳ (U+2733). Prefix match avoids a bare
	// "sparkle" appearing later in a window label that happens to contain it.
	if strings.HasPrefix(title, "✳") {
		return State{State: protocol.StateIdle, Confidence: ConfidenceHigh}
	}
	return State{State: protocol.StateUnknown, Confidence: ConfidenceUnknown}
}

// blockedStateFromScreen is a narrow screen-text check for interactive boxes
// that must outrank any title signal: a permission/approval prompt rendered
// while the agent waits. It is deliberately minimal — just the box phrases the
// rule tables already key on — and returns StateUnknown when no box is present
// so the caller falls through to the title/table path.
func blockedStateFromScreen(text string) protocol.AgentState {
	lower := strings.ToLower(text)
	switch {
	case strings.Contains(lower, "do you want to proceed?"),
		strings.Contains(lower, "allow command?"),
		strings.Contains(lower, "press enter to confirm or esc to cancel"),
		strings.Contains(lower, "press enter to confirm"),
		strings.Contains(lower, "enter to confirm or esc to cancel"):
		return protocol.StateBlocked
	}
	return protocol.StateUnknown
}
