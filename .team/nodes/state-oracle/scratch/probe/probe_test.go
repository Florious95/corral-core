// Package probe 是 t.oracle 的根因探针。它把归档决策层（stateoracleprobe/agentstate，
// 逻辑与 docs/archive/agentstate-round4/ 逐字一致）喂给真实判据语料，断言「目标行为」。
//
// 红测契约（058 / [[054]] 回炉流程第 2 步）：
//   - 在「回退后的旧实现」上跑，探针必须命中（FAIL/红）→ 诊断正确；
//   - 若探针在旧实现上不红，说明诊断错了，不许往下走。
//
// 诊断（058 + 025 + herdr 调研 §9/§10）：
//   - 信息不在字形里，在导数里。三次修复都在调一张不含答案的白名单（spinnerFrames）。
//   - 实证：字形改版（braille → ◐/✳），白名单静默失明；同字形 ✻ 无法分辨完成态与工作中。
//   - 判据必须落在「那一段变没变 / 有没有」，不是「里面有没有某个字符」→ 零字形白名单。
//
// 探针清单：
//   R1 新字形 ◐（U+25D0）工作标题 → 必须 working（旧实现认不出 → idle，红）
//   R2 导数：连续两帧底部块在动（◐→◑，无文本信号）→ 必须 working（旧实现两帧都 idle，红）
//   G1 守卫：真实 working 版式（esc to interrupt 在锚点区）→ 必须仍 working（不红）
//   G2 守卫：真实完成态（✻ Churned for 4s，无工作信号）→ 必须非 working（不红）
package probe

import (
	"testing"

	"stateoracleprobe/agentstate"
	"stateoracleprobe/protocol"
)

// idleBar 是 herdr 调研 §10.1 真实 idle 版式（裸 ❯ + 空闲状态栏）。
const idleBar = "────────────────────────────────────────────\n❯\n  ? for shortcuts · ← for agents          ● high · /effort\n"

// R1 —— 新字形工作失明（title 差分路径）
//
// 用户 2026-08-15 截图：Claude Code 工作中吐 ◐（U+25D0），空闲吐 ✳。
// 旧 stateFromTitle 是「认字形」的单帧匹配：只认 braille（U+2800-28FF）与 ✳ 前缀，
// ◐ 不在白名单 → 静默失明（这也正是被归档的化石测试 TestStateProviderTitleSignalDrivesState
// 的形态：喂静态 ⠙→working / ✳→idle，换新字形就瞎）。
// 目标（导数判据）：标题在两帧之间在动（◐→◑）的 pane 是工作中 → 必须判 working。
// 旧实现：两帧都单帧认字形 → idle（红）。零字形实现：diff 标题即可，不需认识 ◐。
func TestProbeRotatingTitleReadsWorking(t *testing.T) {
	t1 := agentstate.Sample{PaneCommand: "claude", PaneTitle: "◐ w-librarian", RecentOutput: []byte(idleBar)}
	t2 := agentstate.Sample{PaneCommand: "claude", PaneTitle: "◑ w-librarian", RecentOutput: []byte(idleBar)}

	d1 := agentstate.DefaultRegistry().Detect(t1).State
	d2 := agentstate.DefaultRegistry().Detect(t2).State
	t.Logf("R1: 单帧 Detect 标题◐=%v 标题◑=%v", d1, d2)
	if d1 == protocol.StateWorking || d2 == protocol.StateWorking {
		t.Logf("R1: 单帧已能判 working，不需要时序（不红）")
		return
	}
	// 单帧都读非 working → 标题差分/时序判据必须接管（同一保留缝 Track）。
	prev := agentstate.Track(protocol.StateIdle, t1)
	got := agentstate.Track(prev.State, t2)
	t.Logf("R1: 时序 Track 标题 ◐→◑ 后 → %v", got.State)
	if got.State != protocol.StateWorking {
		t.Fatalf("RED R1: 标题在动（◐→◑）的工作 pane 经时序判 %v（要 working）——字形白名单对字形改版静默失明", got.State)
	}
}

// R2 —— 导数：那一块「变没变」，不是「有没有某个字符」
//
// 同一 pane 连续两帧，底部块在动（◐→◑），且无任何文本工作信号（无 esc to interrupt、
// 无 braille）。块在动 = 工作中。单帧也许读不清，但时序/导数判据必须接管。
// 旧实现：Track = working→idle⇒done 的近似，两帧都单帧判 idle → 永远答不出 working（红）。
func TestProbeRotatingBottomBlockReadsWorking(t *testing.T) {
	s1 := agentstate.Sample{PaneCommand: "claude", RecentOutput: []byte("◐\n────────────────────────────────────────────\n❯\n")}
	s2 := agentstate.Sample{PaneCommand: "claude", RecentOutput: []byte("◑\n────────────────────────────────────────────\n❯\n")}

	d1 := agentstate.DefaultRegistry().Detect(s1).State
	d2 := agentstate.DefaultRegistry().Detect(s2).State
	t.Logf("R2: 单帧 Detect ◐=%v ◑=%v", d1, d2)
	if d1 == protocol.StateWorking || d2 == protocol.StateWorking {
		t.Logf("R2: 单帧已能判 working，不需要时序（不红）")
		return
	}
	// 单帧都读非 working → 时序/导数判据必须接管。Track 是保留的缝（placeholder 仍在）。
	prev := agentstate.Track(protocol.StateIdle, s1)
	got := agentstate.Track(prev.State, s2)
	t.Logf("R2: 时序 Track 底部块 ◐→◑ 后 → %v", got.State)
	if got.State != protocol.StateWorking {
		t.Fatalf("RED R2: 底部块在动（◐→◑）经时序判 %v（要 working）——判据在算字形，不在算导数", got.State)
	}
}

// G1 —— 守卫：真实 working 版式必须仍判 working（防过度修正成「永远空闲」）
//
// herdr 调研 §10.2 真实语料：esc to interrupt 在最后一个 ❯ 提示符之后。
const realWorking = "❯ Please list 5 US states, one per line.\n✻ Galloping…\n  ⎿ Tip: Name your conversations with /rename ...\n────────────────────────────────────────────\n❯\n  esc to interrupt\n"

func TestGuardRealWorkingStillWorking(t *testing.T) {
	s := agentstate.Sample{PaneCommand: "claude", RecentOutput: []byte(realWorking)}
	got := agentstate.DefaultRegistry().Detect(s)
	t.Logf("G1: 真实 working 版式 → %v", got.State)
	if got.State != protocol.StateWorking {
		t.Fatalf("G1 守卫: 真实工作中版式被判 %v（要 working）——探针误伤真实工作", got.State)
	}
}

// G2 —— 守卫：真实完成态不得误判 working（同字形 ✻ 实锤）
//
// herdr 调研 §10.5 实测：完成态 ✻ Churned for 4s 连续 10 次 capture 内容完全相同
// （秒数不自涨）。无 esc to interrupt。完成态 = 非工作。零字形判据靠「没有工作信号」判 idle。
const realDone = "✻ Churned for 4s\n✻ Sautéed for 4s\n────────────────────────────────────────────\n❯\n  ? for shortcuts · ← for agents          ● high · /effort\n"

func TestGuardRealDoneNotWorking(t *testing.T) {
	s := agentstate.Sample{PaneCommand: "claude", RecentOutput: []byte(realDone)}
	got := agentstate.DefaultRegistry().Detect(s)
	t.Logf("G2: 真实完成态 → %v", got.State)
	if got.State == protocol.StateWorking {
		t.Fatalf("G2 守卫: 真实完成态（✻ 前缀, 无工作信号）被判 working（判据在认字形）")
	}
}
