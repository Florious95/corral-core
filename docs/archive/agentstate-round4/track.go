package agentstate

import "github.com/agentmirror/agentmirror/internal/protocol"

// Track computes the state to publish given the previously published state and
// a fresh sample. It exists to implement the "done ≈ working→idle edge" part of
// the task contract: a single sample cannot distinguish "finished the task"
// from "paused mid-task", but the transition out of working is the strongest
// approximation available from output alone.
//
// The tracker is a pure function of (prev, sample) — the upper layer owns the
// memory of the previous published state — so a stateless server can replay
// the exact same transitions after a reconnect (requirement 003 stateless
// replay: no hidden in-process memory to lose).
//
// Edge semantics: only working→idle yields done. working→unknown or
// working→working stays working/unknown (an unreadable sample is not proof of
// completion); any other previous state that lands on idle stays idle (a
// blocked→idle move is the user answering, not the agent finishing).
//
// @contract
// @pre prev 为 protocol.AgentState 的合法值，sample 任意（未知输出合法）
// @post 仅当 prev=working 且当前判定 idle 时返回 done；其余情况返回当前规则判定
// @err none — 永不 error；未知输入降级为 StateUnknown
// @inv 纯函数：(prev, sample) 完全决定结果，无 I/O、无隐藏内存，可无状态重放
func Track(prev protocol.AgentState, sample Sample) State {
	cur := DefaultRegistry().Detect(sample)

	// A done edge must be well-grounded: previous working and now idle. If the
	// current decision is unknown (e.g. the tail window went blank) we cannot
	// claim completion, so we fall through to the raw decision.
	if prev == protocol.StateWorking && cur.State == protocol.StateIdle {
		return State{State: protocol.StateDone, Confidence: ConfidenceMedium}
	}
	return cur
}
