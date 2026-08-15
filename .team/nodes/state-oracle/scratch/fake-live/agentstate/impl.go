// Package agentstate 是「满足探针判据」的最小模拟实现(fake-live)。
// 仅供 t.oracle 验证判据命令可运行、可变异。零字形白名单;判据=导数(块变没变)+存在(工作条有没有)。
package agentstate

import (
	"strings"
	"time"

	"stateoracleprobe/protocol"
)

type AgentKind string

const (
	AgentKindUnknown AgentKind = ""
	AgentKindClaude  AgentKind = "claude"
	AgentKindCodex   AgentKind = "codex"
)

func (k AgentKind) Command() (string, bool) { return "", false }

type IdentifyInput struct {
	PanePID     int
	PaneTitle   string
	PaneCommand string
}

func Identify(in IdentifyInput) AgentKind { return AgentKindUnknown }

type Sample struct {
	PaneCommand   string
	PaneTitle     string
	RecentOutput  []byte
	LastOutputAge time.Duration
}

type State struct {
	State      protocol.AgentState
	Confidence int
}

type Confidence int

type Registry map[string]Adapter
type Adapter interface{ Detect(sample Sample) State }

func DefaultRegistry() Registry { return Registry{"claude": &claudeAdapter{}} }

// claudeAdapter: 单帧判「存在性」(esc to interrupt 工作条);diff 交给 Track。
type claudeAdapter struct{}

func (a *claudeAdapter) Detect(s Sample) State {
	t := strings.ToLower(string(s.RecentOutput))
	if strings.Contains(t, "esc to interrupt") {
		return State{protocol.StateWorking, 1}
	}
	return State{protocol.StateUnknown, 0}
}

func (r Registry) Detect(s Sample) State {
	if a, ok := r[s.PaneCommand]; ok {
		return a.Detect(s)
	}
	return State{protocol.StateUnknown, 0}
}

// lastFrame: 有状态 diff 的模拟。key = PaneID/title,value = 最近一帧内容。
// 导数判据: 当前帧 ≠ 上一帧 → 在动(working);连续帧相同 → 停了(非 working)。
var lastFrame = map[string]string{}

func frameKey(s Sample) string { return s.PaneCommand }

func Track(prev protocol.AgentState, s Sample) State {
	key := frameKey(s)
	cur := string(s.RecentOutput) + "\n" + s.PaneTitle
	old, seen := lastFrame[key]
	lastFrame[key] = cur
	if seen && cur != old {
		// 两帧在动: 有活动。若 prev 是 idle → 进入 working。
		if prev == protocol.StateIdle {
			return State{protocol.StateWorking, 1}
		}
		return State{protocol.StateWorking, 1}
	}
	if !seen {
		return State{protocol.StateIdle, 1}
	}
	// 两帧全同: 停了 → idle(完成态就是全同,§10.5)
	return State{protocol.StateIdle, 1}
}
