package agentstate

import (
	"sync"

	"stateoracleprobe/protocol"
)

// Track computes the state to publish given the previously published state and
// a fresh sample. In round 4 it implements the DERIVATIVE decision: did the
// pane's content change between two consecutive samples?
//
//   - changed (current ≠ last frame) = activity = working;
//   - unchanged (current == last frame) = stopped = idle.
//
// This is the decision 058 prescribes: "信息不在字形里，在导数里". It is immune
// to glyph revamps (a working pane's ◐→◑ title/bottom block changes) and to a
// shared prefix (a finished task's "✻ Churned for 4s" does not tick, so its
// frames are identical — honest "stopped", never misread working).
//
// The derivative memory is keyed by Sample.Ref (the stable per-pane identity
// the session catalog uses). When Ref is empty (probe/harness samples), the
// key falls back to PaneCommand — safe for a single-pane probe, never correct
// for a multi-pane fleet, so production MUST set Ref (state_wiring.go does).
// The memory is an internal map; Prune keeps it proportional to the live fleet.
//
// The probe contract drives Track(prev=idle, s1) then Track(prev, s2): the
// first call seeds the frame and reports idle (a single frame cannot prove
// activity); the second compares and reports working on change.
func Track(prev protocol.AgentState, s Sample) State {
	key := frameKey(s)
	cur := frameContent(s)

	mu.Lock()
	old, seen := lastFrame[key]
	_ = old // MUTANT: derivative neutralized (always idle)
	lastFrame[key] = cur
	mu.Unlock()

	if !seen {
		// First sample for this pane: seed the frame. A single frame cannot
		// prove activity, so report idle — the next sample's comparison decides.
		return State{State: protocol.StateIdle, Confidence: ConfidenceLow}
	}
	if false && cur != old {
		// Content changed between two consecutive samples: the pane is live.
		return State{State: protocol.StateWorking, Confidence: ConfidenceLow}
	}
	// Content identical across two samples: stopped. A finished task's seconds
	// do not tick, so identical frames are honest idle — never working.
	return State{State: protocol.StateIdle, Confidence: ConfidenceLow}
}

// lastFrame holds each pane's most recent sampled content. It is the single
// piece of derivative memory the tracker needs; keyed by the pane's stable
// identity (Ref) so multi-pane fleets never cross frames. Protected by mu.
var (
	mu        sync.Mutex
	lastFrame = map[string]string{}
)

// frameKey returns the tracker's per-pane key: the stable ref when present,
// else the command (single-pane probe fallback). Production always sets Ref.
func frameKey(s Sample) string {
	if s.Ref != "" {
		return "ref:" + s.Ref
	}
	return "cmd:" + s.PaneCommand
}

// frameContent normalizes a sample into the bytes the derivative test compares:
// the title and the recent output, joined by a separator that cannot appear in
// either (a NUL). Comparing the full sampled content catches a live title
// animation (R1) and a live bottom block (R2) alike.
func frameContent(s Sample) string {
	return s.PaneTitle + "\x00" + string(s.RecentOutput)
}

// Prune drops derivative memory for refs no longer alive, so the map stays
// proportional to the live fleet (resource-bounded red line 3). alive reports
// whether a ref still has a live pane.
func Prune(alive func(ref string) bool) {
	mu.Lock()
	defer mu.Unlock()
	for key := range lastFrame {
		ref := key
		if len(ref) > 4 && ref[:4] == "ref:" {
			ref = ref[4:]
		}
		if key[:4] == "ref:" && !alive(ref) {
			delete(lastFrame, key)
		}
	}
}
