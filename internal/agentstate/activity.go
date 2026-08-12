package agentstate

import "github.com/agentmirror/agentmirror/internal/protocol"

// activity.go implements the glyph-independent liveness fallback (D-26 layer
// ②). The per-agent rule tables key on whatever indicator characters a CLI
// draws today; when the CLI swaps them — Claude Code just did exactly this, its
// working indicator moved from braille dots to ◐-family half-fill frames, D-26 —
// a byte whitelist goes stale with no notification from upstream. The durable
// signal is change itself: a pane whose visible region repaints between
// consecutive captures is alive and working; one whose frames are all identical
// is idle. decideActivity reads that signal from a window of frames and never
// consults any particular glyph.
//
// Coexistence with the existing states (D-26 "与既有 blocked/done/unknown 如何
// 共存由你设计"): the adapters run the rule tables first and only reach this
// fallback when the tables yield unknown. A blocked permission box therefore
// always wins over a churning window, a recognized working action bar never
// reads idle-from-static, and a bare idle prompt never reads working just
// because the pane content churns underneath. Activity is the last-resort
// fill-in for UI the tables do not recognize, never an override.

// decideActivity inspects a chronological window of raw pane captures (oldest
// first) for visible change. It returns:
//
//   - working when any adjacent pair of frames differs after ANSI stripping —
//     the pane is actively redrawing (high-frequency change ⇒ alive);
//   - idle when every frame in the window strips to the same text — the pane
//     is static;
//   - unknown when fewer than two frames are available — one snapshot carries
//     no temporal information, so the fallback cannot decide (the caller
//     degrades to unknown, never an error, requirement 008).
//
// The decision is glyph-agnostic on purpose: the test suite drives it with a
// glyph family no rule table knows and it must still say working (D-26
// acceptance). Changing an unchanged frame set (identical glyph, only ANSI
// churn) strips to the same text and stays idle — visible content is the
// sampled truth, not terminal escape noise.
//
// @contract
// @pre frames 为任意帧序列（可空、可含 ANSI，元素可为任意字节）
// @post 返回一个合法 State：变化⇒working、全同⇒idle、<2 帧⇒unknown
// @err none — 永不 error；帧按 stripANSI 后的文本逐对比较
// @inv 纯函数：无 I/O、无时钟、无隐藏状态
func decideActivity(frames [][]byte) State {
	if len(frames) < 2 {
		return State{State: protocol.StateUnknown, Confidence: ConfidenceUnknown}
	}
	prev := stripANSI(string(frames[0]))
	for _, f := range frames[1:] {
		cur := stripANSI(string(f))
		if cur != prev {
			return State{State: protocol.StateWorking, Confidence: ConfidenceLow}
		}
		prev = cur
	}
	return State{State: protocol.StateIdle, Confidence: ConfidenceLow}
}

// decideWithActivity runs the rule table first and only when it yields unknown
// consults the glyph-independent activity fallback. The ordering is deliberate:
// a table rule — a blocked box, a recognized working action bar, a bare idle
// prompt — is the stronger, more specific signal; activity fills only the gap
// the tables cannot see (a CLI whose indicator glyph changed).
//
// @contract
// @pre rules 为按优先级降序的规则表；sample 任意
// @post 规则表命中则返回规则判定；否则返回 decideActivity(sample.FrameHistory)
// @err none — 恒返回 State，未知输入降级为 StateUnknown
// @inv 纯函数：无 I/O、无时钟
func decideWithActivity(rules []rule, sample Sample) State {
	text := stripANSI(string(sample.RecentOutput))
	if st := evaluateRules(rules, text); st.State != protocol.StateUnknown {
		return st
	}
	return decideActivity(sample.FrameHistory)
}
