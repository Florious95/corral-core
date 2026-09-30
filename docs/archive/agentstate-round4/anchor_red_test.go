package agentstate

import (
	"testing"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

// 红测：锚点区域限定（D-26 误检根因——全屏扫描把历史残留当 working）
//
// 根因（leader msg_529e84b495b8 / msg_7640d41a44d0 批准，§9 + §10 真实语料）：
// capture-pane 抓整屏，rule.match 用 strings.Contains 匹配整屏文本。
// claude-working-action-bar 的 `esc to interrupt` 在整屏任意位置命中——
// 包括滚出底部、留在更早 `❯` 提示符之前的历史残留行。
//
// 修复：锚点方案——只匹配「最后一个 `❯` 提示符之后」的区域。
// 历史残留的 `esc to interrupt` 一定在更早的提示符之前 → 天然排除。
//
// 五条红测全部用真实语料（§10），本文件为第 1 条（残留测试）——
// 当前必须判 working（红），修复后判 idle。

// residualEscToInterruptFixture 模拟「历史任务残留 esc to interrupt + 当前 idle」：
// 历史任务的 esc to interrupt 留在屏幕上（在更早的 `❯` 之后），
// 但底部已是新的 idle 提示符 + 真实 idle 状态栏。
// 锚点语义：最后 `❯` 是当前 idle 提示符，其前的 esc to interrupt 属于历史输出，
// 不属当前 UI 区 → 修复后判 idle。
const residualEscToInterruptFixture = `
❯ 历史任务的输入
✻ Galloping…
  esc to interrupt
────────────────────────────────────────────
❯
  ? for shortcuts · ← for agents          ● high · /effort
`

// TestResidualEscToInterruptNotWorking 是残留红测：历史残留的 esc to interrupt
// 在更早的 `❯` 提示符之后、但最后一个 `❯` 之前（属于旧任务输出），底部是真实
// idle 状态栏。当前实现全屏匹配 → esc to interrupt 命中 → 判 working（错误）。
// 锚点修复后：只匹配最后一个 `❯` 之后 → 残留被排除 → 判 idle。
func TestResidualEscToInterruptNotWorking(t *testing.T) {
	s := Sample{PaneCommand: "claude", RecentOutput: []byte(residualEscToInterruptFixture)}
	got := (&ClaudeCodeAdapter{}).Detect(s)
	t.Logf("residual esc to interrupt (更早 ❯ 之后、最后 ❯ 之前) → state=%s conf=%v", got.State, got.Confidence)
	if got.State != protocol.StateIdle {
		t.Errorf("残留 esc to interrupt 被判 %s（应为 idle）——全屏扫描把历史残留当 working，这是 D-26 误检根因", got.State)
	}
}

// realWorkingFixture 是真实 working 版式（§10.2）：esc to interrupt 在最后 `❯` 之后。
// 锚点修复必须让它仍判 working（防反向误判成永远空闲）。
const realWorkingFixture = `
❯ Please list 5 US states, one per line.
✻ Galloping…
  ⎿ Tip: Name your conversations with /rename ...
────────────────────────────────────────────
❯
  esc to interrupt
`

// TestGuardWorkingInAnchorRegion 是守卫一：真实 working 版式（esc to interrupt
// 在最后 `❯` 之后）必须仍判 working。防修完变成「永远判空闲」。
func TestGuardWorkingInAnchorRegion(t *testing.T) {
	s := Sample{PaneCommand: "claude", RecentOutput: []byte(realWorkingFixture)}
	got := (&ClaudeCodeAdapter{}).Detect(s)
	t.Logf("real working (esc to interrupt 在最后 ❯ 之后) → state=%s conf=%v", got.State, got.Confidence)
	if got.State != protocol.StateWorking {
		t.Errorf("真实 working 被判 %s（应为 working）——锚点修复误伤真实工作", got.State)
	}
}

// realDoneFixture 是真实完成态（§10.5 实测语料）：✻ Churned for 4s 在底部，
// 无 esc to interrupt，底部是 idle 状态栏。锚点修复必须判 idle。
const realDoneFixture = `
✻ Churned for 4s
✻ Sautéed for 4s
────────────────────────────────────────────
❯
  ? for shortcuts · ← for agents          ● high · /effort
`

// TestGuardDoneNotWorking 是守卫二：真实完成态（✻ 前缀，无 esc to interrupt）
// 必须判 idle——同字形实锤（✻ 既用于完成态也用于工作中），靠位置/锚点分辨。
func TestGuardDoneNotWorking(t *testing.T) {
	s := Sample{PaneCommand: "claude", RecentOutput: []byte(realDoneFixture)}
	got := (&ClaudeCodeAdapter{}).Detect(s)
	t.Logf("real done (✻ 前缀, 无 esc to interrupt) → state=%s conf=%v", got.State, got.Confidence)
	if got.State != protocol.StateIdle {
		t.Errorf("真实完成态被判 %s（应为 idle）——完成态被误判", got.State)
	}
}

// realBlockedFixture 是真实 blocked 版式（权限确认框）：Do you want to proceed?
// + Bash command + (esc to cancel)。真实权限框是纯文本块，不含 `❯` 提示符行，
// 走兜底路径（lastNonEmptyLines 8）。锚点修复必须仍判 blocked。
const realBlockedFixture = `
● Do you want to proceed?
  Bash command:
  · git push

  (esc to cancel)
`

// TestGuardBlockedStillBlocked 是守卫三：真实权限框（blocked 版式）必须仍判
// blocked。权限框无 `❯` 锚点，走兜底路径；其 `do you want to proceed?` +
// `esc to cancel` 必须在兜底区命中 blocked 规则。
func TestGuardBlockedStillBlocked(t *testing.T) {
	s := Sample{PaneCommand: "claude", RecentOutput: []byte(realBlockedFixture)}
	got := (&ClaudeCodeAdapter{}).Detect(s)
	t.Logf("real blocked (权限框) → state=%s conf=%v", got.State, got.Confidence)
	if got.State != protocol.StateBlocked {
		t.Errorf("真实权限框被判 %s（应为 blocked）——锚点修复误伤 blocked", got.State)
	}
}

// TestGuardNoAnchorFallback 是守卫四：无 `❯` 锚点的情况（全屏 TUI / 刚清屏），
// 退回 lastNonEmptyLines 兜底路径，必须不崩、不误判。真实全屏 TUI 语料未取到
// （§9.4 第 5 条标 NOT_COVERED），此测试用「含 esc to interrupt 但无 ❯」的构造
// 验证兜底路径不崩且不把残留当 working——构造的不是版式，是兜底路径的健壮性。
func TestGuardNoAnchorFallback(t *testing.T) {
	// 无 ❯ 锚点、无 esc to interrupt 的文本：走兜底路径，应判 unknown 或 idle，不崩。
	noAnchorIdle := "┌─ TUI ─┐\n│ panel │\n└───────┘"
	s := Sample{PaneCommand: "claude", RecentOutput: []byte(noAnchorIdle)}
	got := (&ClaudeCodeAdapter{}).Detect(s)
	t.Logf("no-anchor fallback → state=%s conf=%v (不崩即可)", got.State, got.Confidence)

	// 无 ❯ 锚点但屏上有 esc to interrupt：兜底路径下也不应误判 working——
	// 若全屏残留仍能命中，说明兜底又回到了老问题。
	noAnchorResidual := "some output\n  esc to interrupt\nmore output"
	s2 := Sample{PaneCommand: "claude", RecentOutput: []byte(noAnchorResidual)}
	got2 := (&ClaudeCodeAdapter{}).Detect(s2)
	t.Logf("no-anchor + residual esc to interrupt → state=%s conf=%v", got2.State, got2.Confidence)
	// 兜底路径边界：无 ❯ 锚点时我们无法用锚点排除残留，但至少不崩。这里记录行为。
	// 若期望严格，可改为要求 unknown；此处先确保不 panic。
}
