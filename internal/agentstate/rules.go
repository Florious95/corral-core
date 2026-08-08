package agentstate

import (
	"strings"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

// This file is the shared table-driven rule engine used by every adapter.
// A rule is one pattern→state mapping that documents which CLI UI element it
// keys on. Rules are evaluated in descending priority; the first rule that
// matches decides the state. This mirrors the priority-wins model of the herdr
// detection engine (see adapters.go header for the attribution), but adapted
// to this package's contract: a RecentOutput tail window instead of a full
// screen, and no dependency on OSC-title state (tmux consumes the title, so
// screen text is the reliable carrier).
//
// Why tables and not if/else chains (experience rule, task contract §4): every
// CLI is versioned and its wording changes over releases. A table keeps the
// mapping to UI elements visible and one edit away, so a CLI redesign is a
// routine maintenance task, not a code archaeology exercise.

// spinnerFrames is the set of braille spinner frame characters Claude Code and
// Codex draw while working. It deliberately excludes the dotted separator
// "⠤" that idle status bars use, so a line containing a spinner frame is a
// working signal, not an idle one.
const spinnerFrames = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"

// rule is one entry in an adapter's rule table. Match conditions are ANDed
// within each list and the lists are ANDed together; an empty list imposes no
// constraint. See the field comments for the semantics.
type rule struct {
	// id is a stable identifier used in test failures and logs.
	id string

	// comment documents which CLI UI element this rule keys on and when it may
	// stop matching (i.e. what a CLI redesign would need to update).
	comment string

	// priority orders the rules: higher wins. A blocked/working signal must
	// outrank an idle signal, since idle is the weakest conclusion.
	priority int

	// state is the normalized state this rule decides when it matches.
	state protocol.AgentState

	// confidence grades the strength of the UI signal (see Confidence).
	confidence Confidence

	// contains: every substring must be present (case-insensitive).
	contains []string

	// anyContains: at least one substring must be present (case-insensitive).
	// An empty list means "no constraint".
	anyContains []string

	// linePrefixes: at least one line (after trimming leading whitespace) must
	// start with one of these prefixes. Used for prompt markers like "❯".
	linePrefixes []string

	// spinnerLine: a working signal — at least one line must contain a braille
	// spinner frame character from spinnerFrames.
	spinnerLine bool

	// notContains: if any substring is present (case-insensitive), the rule is
	// skipped. Used to keep a weak idle rule away from a strong blocked box.
	notContains []string
}

// match evaluates the rule against the stripped output text and its lines.
func (r rule) match(text string, lines []string) bool {
	for _, sub := range r.contains {
		if !strings.Contains(strings.ToLower(text), strings.ToLower(sub)) {
			return false
		}
	}
	if len(r.anyContains) > 0 && !anyFold(text, r.anyContains) {
		return false
	}
	if len(r.linePrefixes) > 0 && !anyLinePrefix(lines, r.linePrefixes) {
		return false
	}
	if r.spinnerLine && !anyLineContains(text, lines, spinnerFrames) {
		return false
	}
	if anyFold(text, r.notContains) {
		return false
	}
	return true
}

// anyFold reports whether text contains any of subs (case-insensitive).
func anyFold(text string, subs []string) bool {
	lower := strings.ToLower(text)
	for _, s := range subs {
		if strings.Contains(lower, strings.ToLower(s)) {
			return true
		}
	}
	return false
}

// anyLinePrefix reports whether any line (leading whitespace trimmed) starts
// with any of the prefixes.
func anyLinePrefix(lines []string, prefixes []string) bool {
	for _, ln := range lines {
		t := strings.TrimLeft(ln, " \t")
		for _, p := range prefixes {
			if strings.HasPrefix(t, p) {
				return true
			}
		}
	}
	return false
}

// anyLineContains reports whether any line contains any of the runes in set.
func anyLineContains(text string, lines []string, set string) bool {
	for _, ln := range lines {
		if strings.ContainsAny(ln, set) {
			return true
		}
	}
	return strings.ContainsAny(text, set) // fall back for single-line inputs
}

// splitLines splits stripped text into its logical lines for line-anchored
// rules. A final empty line from a trailing newline is dropped.
func splitLines(text string) []string {
	raw := strings.Split(text, "\n")
	out := raw[:0:len(raw)]
	for _, ln := range raw {
		out = append(out, ln)
	}
	// Trim one trailing empty element produced by a trailing "\n".
	if n := len(out); n > 0 && out[n-1] == "" {
		out = out[:n-1]
	}
	return out
}

// evaluateRules runs the rule table in descending priority order and returns
// the first match. No match yields the unknown fallback (StateUnknown with
// unknown confidence), which is a first-class result, never an error.
func evaluateRules(rules []rule, text string) State {
	lines := splitLines(text)
	for _, r := range rules {
		if r.match(text, lines) {
			return State{State: r.state, Confidence: r.confidence}
		}
	}
	return State{State: protocol.StateUnknown, Confidence: ConfidenceUnknown}
}
