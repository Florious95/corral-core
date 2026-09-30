package api

// scroll_forward_scenario_test.go: 缺陷④场景红测（第7轮回炉，w-scroll-test）。
//
// 判据（HANDOFF-leader-20260814.md §4.1 + leader msg_ff56e4451190）：
// 唯一合法的断言对象是"客户端经产品自己的 Subscribe/WebSocket 通道最终收到的
// 画面字节变了没有"。不断言 capture-pane、不断言 #{scroll_position}、不断言
// "调用了 InjectScroll"——这三条在本文件里没有出现。
//
// 前情（三次假 PASS 的教训，见 .team/evidence/fix-scrollback-history-d36.json）：
// 前三轮验证用的通道都不是产品送画面给用户的那条通道。本文件的每一条测试都
// 只经 wsEnv 的真实 WebSocket 连接读取 KindSnapshot/KindDelta 二进制帧或
// PaneModeChanged 控制帧——这就是 App 实际会收到的字节。
//
// 隔离基线（2026-08-14 手工实证，先于本文件落盘）：在隔离 tmux 上直接验证
// "copy-mode 进入 + send-keys -X scroll-up" 期间 pipe-pane FIFO 收到 0 字节。
// 也就是说 InjectScroll 让 tmux 客户端视图滚动，但从不产生任何新的 pty 输出
// 字节——而 Subscribe 的镜像流只由 pipe-pane 驱动（stream.go）。这正是本文件
// 断言会红的根因，不是猜测。
//
// 覆盖三档场景（HANDOFF §4.1）：
//   T-A：非 alt-screen TUI（Claude Code 类比）——必须能看到自己的上文
//   T-B：裸 shell —— 同一机制的最小复现
//   T-C：alt-screen 应用（vim）—— "不支持"必须是客户端可分辨的明确信号，
//        不能和"确实滚动成功"发同一个信号（那就是未定义行为）
//   T-D：裸 shell 进 copy-mode 后打字 —— 必须真的落到 shell 里执行并经镜像
//        流可见，不能只满足"发了 PaneModeChanged{false}"这个元信号

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

// collectedFrame is one frame observed on the real client connection during a
// drain window — either a binary mirror chunk (Kind != 0) or a decoded
// control envelope (Kind == 0).
type collectedFrame struct {
	isBinary bool
	kind     protocol.BinaryKind
	data     []byte // binary payload's Data, or the raw control frame bytes
	ctlType  string
}

// rawWSMsg is one undecoded message off the wire, or the terminal read error.
type rawWSMsg struct {
	typ  websocket.MessageType
	data []byte
	err  error
}

// framePump is a single persistent background reader for one wsEnv
// connection. coder/websocket closes the underlying connection the instant a
// Read's context is canceled (that is documented behavior, not a bug) — so a
// naive "read with a short timeout, loop" pattern permanently kills the
// connection on its first empty poll. The pump instead issues exactly one
// live, uncancelled Read at a time and republishes every message (or the
// terminal error) on a channel; every consumer in this file waits on that
// channel with time.After instead of touching conn.Read directly. This is
// scoped to this file's tests only — scroll_api_test.go's direct
// te.wsEnv.conn.Read() callers are untouched and must not be mixed with a
// pump on the same connection.
type framePump struct {
	msgs chan rawWSMsg
}

func newFramePump(te *tmuxEnv) *framePump {
	p := &framePump{msgs: make(chan rawWSMsg, 256)}
	go func() {
		for {
			typ, data, err := te.wsEnv.conn.Read(context.Background())
			p.msgs <- rawWSMsg{typ: typ, data: data, err: err}
			if err != nil {
				return
			}
		}
	}()
	return p
}

// next waits up to timeout for the next raw message.
func (p *framePump) next(timeout time.Duration) (rawWSMsg, bool) {
	select {
	case m := <-p.msgs:
		return m, true
	case <-time.After(timeout):
		return rawWSMsg{}, false
	}
}

func decodeFrame(t *testing.T, m rawWSMsg) collectedFrame {
	t.Helper()
	if m.typ == websocket.MessageBinary {
		payload, decErr := protocol.DecodeBinary(m.data)
		if decErr != nil {
			t.Fatalf("decode binary %q: %v", m.data, decErr)
		}
		return collectedFrame{isBinary: true, kind: payload.Kind, data: payload.Data}
	}
	var env rawEnvelope
	if err := json.Unmarshal(m.data, &env); err != nil {
		t.Fatalf("unmarshal control %q: %v", m.data, err)
	}
	return collectedFrame{isBinary: false, data: env.Payload, ctlType: env.Type}
}

// drainWindow collects every frame the pump delivers over the given duration
// — including "none at all", which for the tests in this file is the
// expected (and asserted) outcome. This is the one legal way to observe
// "what did the client actually receive" — it never touches tmux.
func drainWindow(t *testing.T, p *framePump, dur time.Duration) []collectedFrame {
	t.Helper()
	var out []collectedFrame
	deadline := time.Now().Add(dur)
	for {
		remaining := time.Until(deadline)
		if remaining <= 0 {
			return out
		}
		m, ok := p.next(remaining)
		if !ok {
			return out
		}
		if m.err != nil {
			t.Fatalf("pump read error: %v", m.err)
		}
		out = append(out, decodeFrame(t, m))
	}
}

// mirrorBytes concatenates every binary chunk's Data across frames — this is
// exactly the byte stream the App's terminal renderer would have consumed.
func mirrorBytes(frames []collectedFrame) []byte {
	var buf bytes.Buffer
	for _, f := range frames {
		if f.isBinary {
			buf.Write(f.data)
		}
	}
	return buf.Bytes()
}

// generateBacklogAndWait types a marker line followed by `fillerLines` more
// lines into the pane via send-keys, then blocks (via a filesystem done-file,
// not tmux state) until the shell has actually finished running the script.
// This runs BEFORE the test ever subscribes, so the marker is already buried
// in tmux scrollback history the first time the client connects — the first
// snapshot the client receives must NOT contain it. That is what makes "the
// marker later appears via the mirror channel" a real proof that scrolling
// delivered it, rather than the marker having streamed past live.
func generateBacklogAndWait(t *testing.T, te *tmuxEnv, marker string, fillerLines int) {
	t.Helper()
	doneDir := t.TempDir()
	donePath := filepath.Join(doneDir, "done")
	script := fmt.Sprintf(
		"echo %s; for i in $(seq 1 %d); do echo cli-output-line-$i; done; touch %s",
		marker, fillerLines, donePath,
	)
	if out, err := runTmuxCmd(te.env, te.sock, "send-keys", "-t", te.paneID, script, "Enter"); err != nil {
		t.Fatalf("send-keys backlog script: %v\n%s", err, out)
	}
	deadline := time.Now().Add(5 * time.Second)
	for {
		if _, err := os.Stat(donePath); err == nil {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("backlog script never finished (done-file %q missing)", donePath)
		}
		time.Sleep(20 * time.Millisecond)
	}
}

// subscribeAndGetSnapshot sends Subscribe and returns the decoded snapshot
// bytes (the client's very first view of the pane), reading exclusively
// through the pump so it never races the pump's own background Read.
func subscribeAndGetSnapshot(t *testing.T, te *tmuxEnv, p *framePump) []byte {
	t.Helper()
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	m, ok := p.next(5 * time.Second)
	if !ok {
		t.Fatal("timed out waiting for snapshot")
	}
	if m.err != nil {
		t.Fatalf("read snapshot: %v", m.err)
	}
	f := decodeFrame(t, m)
	if !f.isBinary || f.kind != protocol.KindSnapshot {
		t.Fatalf("expected KindSnapshot, got isBinary=%v kind=%v", f.isBinary, f.kind)
	}
	return f.data
}

// ---------------------------------------------------------------------------
// T-A: 非 alt-screen TUI（Claude Code 类比）—— 上滑后必须能在真实通道里看到
// 被挤出屏幕的历史内容。
// ---------------------------------------------------------------------------
//
// 复现的是用户 2026-08-14 的原始定义：「我在屏幕里面向上滑的时候……要类似于
// 我在这个界面鼠标滚轮也向上滑，它才能配合看到上面的内容。」这里的 pane 不跑
// 真的 claude 二进制（CI 没有），但用同一种"非 alt-screen、持续向下追加输出"
// 的行为模式模拟 Claude Code 的终端行为——tmux/copy-mode 对两者一视同仁，
// 分辨它们的唯一维度是 alt-screen（T-C 覆盖），这里刻意用不同的 filler 文案
// 和更大的行数与 T-B（裸 shell）区分开来，避免把两档场景混成同一条用例。
func TestScrollForward_NonAltScreenTUI_HistoryReachesRealChannel(t *testing.T) {
	te := startTmuxEnv(t, "sh")
	const marker = "CLAUDE_CODE_CONTEXT_MARKER_9f3a"
	generateBacklogAndWait(t, te, marker, 120) // pane 高 24 行，120 行远超一屏

	pump := newFramePump(te)
	snap := subscribeAndGetSnapshot(t, te, pump)
	if bytes.Contains(snap, []byte(marker)) {
		t.Fatalf("setup invalid: marker already visible in first snapshot (pane not actually scrolled off) — snapshot=%q", snap)
	}

	// 上滑：delta 为负、往历史方向；给足档位覆盖全部 120+1 行 backlog。
	te.wsEnv.sendFrame(&protocol.ScrollWheel{Ref: te.ref(), Delta: -140})

	frames := drainWindow(t, pump, 2*time.Second)
	got := mirrorBytes(frames)
	if !bytes.Contains(got, []byte(marker)) {
		t.Errorf("FAIL（判据：唯一合法断言——marker 必须经 Subscribe/WS 镜像通道到达客户端）：\n"+
			"上滑 140 档后，%d 个窗口内到达的帧中未出现 marker %q。\n"+
			"实际收到的二进制字节（可能为空——InjectScroll 只驱动 tmux 客户端视图，从不产生新的 pipe-pane 字节）：\n%q\n"+
			"收到的帧列表：%s",
			len(frames), marker, got, describeFrames(frames))
	}
}

// ---------------------------------------------------------------------------
// T-B: 裸 shell —— 同一断链机制的最小复现（HANDOFF §4.1 第③档）。
// ---------------------------------------------------------------------------
func TestScrollForward_BareShell_HistoryReachesRealChannel(t *testing.T) {
	te := startTmuxEnv(t, "sh")
	const marker = "BARE_SHELL_HISTORY_MARKER_2c71"
	generateBacklogAndWait(t, te, marker, 60)

	pump := newFramePump(te)
	snap := subscribeAndGetSnapshot(t, te, pump)
	if bytes.Contains(snap, []byte(marker)) {
		t.Fatalf("setup invalid: marker already visible in first snapshot — snapshot=%q", snap)
	}

	te.wsEnv.sendFrame(&protocol.ScrollWheel{Ref: te.ref(), Delta: -80})

	frames := drainWindow(t, pump, 2*time.Second)
	got := mirrorBytes(frames)
	if !bytes.Contains(got, []byte(marker)) {
		t.Errorf("FAIL：裸 shell 上滑后，marker %q 未经真实镜像通道到达客户端。收到字节：%q。帧列表：%s",
			marker, got, describeFrames(frames))
	}
}

// ---------------------------------------------------------------------------
// T-C: alt-screen 应用（vim）—— "不支持"必须是客户端可分辨的明确信号。
// ---------------------------------------------------------------------------
//
// 判据来自任务书原文："当前是'不支持'，红测要把'不支持'钉成明确行为，不许是
// 未定义"。leader 二次裁定（回炉，v2.1）：不为此新增 wire 字段/kind——
// "不支持"必须靠已有的 SNAPSHOT 内容本身可分辨，不能引入协议改动。
//
// 机制（bridge.Pane.ScrollState 契约）：alt-screen pane 的 historySize 恒被
// 强制为 0（不管 tmux 自己的 #{history_size} 报什么，因为 capture-pane -S/-E
// 在 alternate_on=1 时结构上读不到那段历史）。handleScrollWheel 对 offset
// 的 clamp 逻辑会让它落回 0，走"实时"分支：推回 pane 当前未变的画面——诚实地
// 告诉客户端"没有更早的内容"，而不是假装滚动成功。
//
// 红测断言：对照组（有真实历史）滚动后收到的 SNAPSHOT 内容包含被滚出屏幕的
// marker（证明真的翻到了历史）；实验组（alt-screen）滚动后收到的 SNAPSHOT
// 内容与滚动前的画面一致（没有任何"翻到别的地方"的痕迹）——两者在客户端能
// 实际观察到的字节上必须不同，不能撞成同一个信号。
func TestScrollForward_AltScreenApp_MustBeDistinguishableFromWorkingScroll(t *testing.T) {
	// 对照组：非 alt-screen，能产生真实滚动效果的 pane（复用 T-B 同款 setup）。
	working := startTmuxEnv(t, "sh")
	const workingMarker = "CONTROL_GROUP_MARKER_5a91"
	generateBacklogAndWait(t, working, workingMarker, 60)
	workingPump := newFramePump(working)
	subscribeAndGetSnapshot(t, working, workingPump)
	working.wsEnv.sendFrame(&protocol.ScrollWheel{Ref: working.ref(), Delta: -80})
	workingFrames := drainWindow(t, workingPump, 2*time.Second)
	workingSnap, workingHasSnap := firstSnapshot(workingFrames)
	if !workingHasSnap {
		t.Fatalf("setup invalid: 对照组（非 alt-screen）滚动后未收到任何 SNAPSHOT，无法建立“有效滚动”的基线信号")
	}
	if !bytes.Contains(workingSnap, []byte(workingMarker)) {
		t.Fatalf("setup invalid: 对照组滚动后的 SNAPSHOT 不含 marker，基线本身不是“有效滚动”: %q", workingSnap)
	}

	// 实验组：alt-screen（vim -c 'set mouse=a'，与设计文档实测项一致）。
	alt := startTmuxEnv(t, "vim -c 'set mouse=a' /dev/null")
	time.Sleep(1500 * time.Millisecond) // 让 vim 完成绘制、进入 alternate screen（时间等待，非断言）
	altPump := newFramePump(alt)
	altInitial := subscribeAndGetSnapshot(t, alt, altPump)
	alt.wsEnv.sendFrame(&protocol.ScrollWheel{Ref: alt.ref(), Delta: -10})
	altFrames := drainWindow(t, altPump, 2*time.Second)
	altSnap, altHasSnap := firstSnapshot(altFrames)
	if !altHasSnap {
		t.Fatalf("alt-screen 场景滚动后未收到任何 SNAPSHOT——服务端必须显式回应（哪怕是“没变”），不能沉默")
	}

	if !bytes.Equal(altSnap, altInitial) {
		t.Errorf("FAIL（判据：alt-screen 的'不支持'必须诚实地表现为“画面没变”，不能悄悄呈现出别的内容）：\n"+
			"滚动前画面：%q\n滚动后画面：%q\n两者应完全一致（vim 在 alternate screen 上没有可达的 scrollback）。",
			altInitial, altSnap)
	}
	if bytes.Contains(altSnap, []byte(workingMarker)) {
		t.Errorf("FAIL: alt-screen 场景的 SNAPSHOT 不应包含对照组的 marker（串场）：%q", altSnap)
	}

	// 核心判据：两种质上不同的结果，在客户端实际收到的字节上必须不同——
	// 对照组翻出了历史（含 marker），实验组画面原地不动，二者不可能相同。
	if bytes.Equal(workingSnap, altSnap) {
		t.Errorf("FAIL（判据：alt-screen 的'不支持'必须是客户端可分辨的明确信号，不许和真滚动撞成同一个信号）：\n"+
			"对照组与实验组滚动后收到的 SNAPSHOT 内容完全相同：%q", workingSnap)
	}
}

// firstSnapshot returns the Data of the first KindSnapshot binary frame, or
// false if none arrived in the window.
func firstSnapshot(frames []collectedFrame) ([]byte, bool) {
	for _, f := range frames {
		if f.isBinary && f.kind == protocol.KindSnapshot {
			return f.data, true
		}
	}
	return nil, false
}

// ---------------------------------------------------------------------------
// T-D: 裸 shell 进 copy-mode 后打字 —— 必须真的落进 shell 并经镜像流可见，
// 不能只满足"发了 PaneModeChanged{false}"这个元信号（那不是脱困，只是通知）。
// ---------------------------------------------------------------------------
func TestScrollForward_BareShell_TypingAfterScrollLandsInShellOnRealChannel(t *testing.T) {
	te := startTmuxEnv(t, "sh")
	pump := newFramePump(te)
	subscribeAndGetSnapshot(t, te, pump)

	// 上滑进 copy-mode。
	te.wsEnv.sendFrame(&protocol.ScrollWheel{Ref: te.ref(), Delta: -3})
	_ = drainWindow(t, pump, 1*time.Second) // 排空 PaneModeChanged{true} 等信号，不断言其内容

	// 打字：发一条会在 shell 里产生可见输出的命令。
	const marker = "UNSTUCK_MARKER_b81e"
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 1, Ref: te.ref(), Text: "echo " + marker})

	frames := drainWindow(t, pump, 2*time.Second)
	got := mirrorBytes(frames)
	if !bytes.Contains(got, []byte(marker)) {
		t.Errorf("FAIL（判据：'自动脱困'必须是打的字真的到了 shell 并执行、经镜像通道可见，不是只收到一个 InCopyMode:false 的元信号）：\n"+
			"copy-mode 中发送 Input 后，marker %q 未出现在镜像字节流中。收到字节：%q。帧列表：%s",
			marker, got, describeFrames(frames))
	}
}

// describeFrames renders a compact human-readable summary of collected
// frames for failure messages — this IS the evidence, not a paraphrase of it.
func describeFrames(frames []collectedFrame) string {
	var buf bytes.Buffer
	for i, f := range frames {
		if f.isBinary {
			fmt.Fprintf(&buf, "\n  [%d] binary kind=%d len=%d data=%q", i, f.kind, len(f.data), f.data)
		} else {
			fmt.Fprintf(&buf, "\n  [%d] control type=%s payload=%s", i, f.ctlType, f.data)
		}
	}
	if buf.Len() == 0 {
		return "(空——drain 窗口内一帧都没收到)"
	}
	return buf.String()
}
